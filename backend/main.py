"""
FLOAT Translation Backend - Production-Grade WebSocket Server

High-throughput WebSocket server for real-time translation using FastAPI.
Supports hundreds of concurrent connections with asyncio for optimal performance.
Implements strict protocol enforcement and structured logging.
"""

import asyncio
import json
import logging
import os
import time
import uuid
from datetime import datetime, timedelta
from typing import Dict, Any, Optional, Set
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded

from translation_processor import SeamlessM4TProcessor, ProcessorError, ModelLoadError
from models import (
    InboundMessage, OutboundMessage, TranslationResult,
    SessionInfo, ServerError, ConnectionStats, MessageType
)

# Configure structured logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('float_backend.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Rate limiting configuration
limiter = Limiter(key_func=get_remote_address)

# Global session management
active_sessions: Dict[str, SessionInfo] = {}
connection_stats = ConnectionStats()

# Translation processor instance
translation_processor: Optional[SeamlessM4TProcessor] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager for startup and shutdown."""
    global translation_processor
    
    logger.info("Starting FLOAT Translation Backend...")
    
    try:
        # Initialize translation processor
        translation_processor = SeamlessM4TProcessor()
        
        # Get API key from environment
        api_key = os.getenv("HUGGINGFACE_API_KEY")
        if not api_key:
            raise ModelLoadError("HUGGINGFACE_API_KEY environment variable not set")
        
        await translation_processor.initialize(api_key)
        logger.info("SeamlessM4T processor initialized successfully")
        
        # Start background tasks
        asyncio.create_task(session_cleanup_task())
        asyncio.create_task(stats_reporting_task())
        
        yield
        
    except Exception as e:
        logger.error(f"Failed to initialize translation processor: {e}")
        raise
    finally:
        logger.info("Shutting down FLOAT Translation Backend...")
        if translation_processor:
            await translation_processor.cleanup()


# Create FastAPI application
app = FastAPI(
    title="FLOAT Translation API",
    description="Real-time translation service for Indian languages",
    version="1.0.0",
    lifespan=lifespan
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Configure appropriately for production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Add rate limiting
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)


class WebSocketManager:
    """Manages WebSocket connections and message routing."""
    
    def __init__(self):
        self.active_connections: Dict[str, WebSocket] = {}
        self.session_metadata: Dict[str, Dict[str, Any]] = {}
    
    async def connect(self, websocket: WebSocket, session_id: str, client_info: Dict[str, Any]):
        """Accept and register a WebSocket connection."""
        await websocket.accept()
        
        # Store connection and metadata
        self.active_connections[session_id] = websocket
        self.session_metadata[session_id] = {
            **client_info,
            "connected_at": datetime.utcnow(),
            "last_activity": datetime.utcnow(),
            "message_count": 0,
            "chunks_processed": 0,
            "last_acknowledged_seq": 0  # Track last acknowledged sequence ID
        }
        
        # Update global stats
        connection_stats.total_connections += 1
        connection_stats.active_connections = len(self.active_connections)
        
        logger.info(f"WebSocket connected: {session_id} from {client_info.get('client_ip', 'unknown')}")
    
    def disconnect(self, session_id: str):
        """Remove a WebSocket connection."""
        if session_id in self.active_connections:
            del self.active_connections[session_id]
        
        if session_id in self.session_metadata:
            metadata = self.session_metadata[session_id]
            session_duration = datetime.utcnow() - metadata["connected_at"]
            
            logger.info(f"WebSocket disconnected: {session_id}, duration: {session_duration}")
            del self.session_metadata[session_id]
        
        # Update global stats
        connection_stats.active_connections = len(self.active_connections)
    
    async def send_message(self, session_id: str, message: Dict[str, Any]):
        """Send a message to a specific WebSocket."""
        if session_id in self.active_connections:
            websocket = self.active_connections[session_id]
            try:
                await websocket.send_text(json.dumps(message))
                
                # Update activity
                if session_id in self.session_metadata:
                    self.session_metadata[session_id]["last_activity"] = datetime.utcnow()
                    self.session_metadata[session_id]["message_count"] += 1
                
            except Exception as e:
                logger.error(f"Failed to send message to {session_id}: {e}")
                self.disconnect(session_id)
    
    async def send_acknowledgment(self, session_id: str, sequence_id: int):
        """Send acknowledgment for received chunk."""
        ack_message = {
            "session_id": session_id,
            "message_type": "ack",
            "ack_sequence_id": sequence_id,
            "timestamp": int(time.time() * 1000)
        }
        
        # Update last acknowledged sequence ID
        if session_id in self.session_metadata:
            self.session_metadata[session_id]["last_acknowledged_seq"] = sequence_id
            
        await self.send_message(session_id, ack_message)
    
    async def broadcast_keepalive(self):
        """Send keepalive messages to all active connections."""
        keepalive_message = {
            "message_type": "keepalive",
            "timestamp": int(time.time() * 1000)
        }
        
        for session_id in list(self.active_connections.keys()):
            await self.send_message(session_id, keepalive_message)
    
    def get_session_info(self, session_id: str) -> Optional[Dict[str, Any]]:
        """Get metadata for a specific session."""
        return self.session_metadata.get(session_id)
    
    def get_all_sessions(self) -> Dict[str, Dict[str, Any]]:
        """Get metadata for all active sessions."""
        return self.session_metadata.copy()


# Global WebSocket manager
websocket_manager = WebSocketManager()


@app.websocket("/ws/translation/{session_id}")
async def websocket_endpoint(websocket: WebSocket, session_id: str):
    """Main WebSocket endpoint for translation requests."""
    
    # Validate session ID format
    try:
        uuid.UUID(session_id)
    except ValueError:
        await websocket.close(code=4000, reason="Invalid session ID format")
        return
    
    # Get client information
    client_ip = websocket.client.host if websocket.client else "unknown"
    client_info = {
        "client_ip": client_ip,
        "user_agent": websocket.headers.get("user-agent", "unknown"),
        "language_pair": websocket.headers.get("x-language-pair", "unknown")
    }
    
    try:
        # Connect WebSocket
        await websocket_manager.connect(websocket, session_id, client_info)
        
        # Initialize session
        active_sessions[session_id] = SessionInfo(
            session_id=session_id,
            language_pair=client_info["language_pair"],
            created_at=datetime.utcnow(),
            last_activity=datetime.utcnow(),
            last_acknowledged_seq=0
        )
        
        # Send welcome message
        welcome_message = {
            "session_id": session_id,
            "message_type": "connected",
            "timestamp": int(time.time() * 1000)
        }
        await websocket_manager.send_message(session_id, welcome_message)
        
        # Main message loop
        while True:
            try:
                # Receive message with timeout
                data = await asyncio.wait_for(websocket.receive_text(), timeout=30.0)
                
                # Update session activity
                if session_id in active_sessions:
                    active_sessions[session_id].last_activity = datetime.utcnow()
                
                # Process message
                await process_websocket_message(session_id, data)
                
            except asyncio.TimeoutError:
                # Send keepalive on timeout
                keepalive_message = {
                    "session_id": session_id,
                    "message_type": "keepalive",
                    "timestamp": int(time.time() * 1000)
                }
                await websocket_manager.send_message(session_id, keepalive_message)
                
            except WebSocketDisconnect:
                logger.info(f"WebSocket disconnected normally: {session_id}")
                break
                
            except Exception as e:
                logger.error(f"Error processing message from {session_id}: {e}")
                error_message = ServerError(
                    error_code="PROCESSING_ERROR",
                    error_message=str(e),
                    session_id=session_id
                )
                await send_error_message(session_id, error_message)
                break
    
    except Exception as e:
        logger.error(f"WebSocket connection error for {session_id}: {e}")
        error_message = ServerError(
            error_code="CONNECTION_ERROR",
            error_message=str(e),
            session_id=session_id
        )
        await send_error_message(session_id, error_message)
    
    finally:
        # Cleanup
        websocket_manager.disconnect(session_id)
        if session_id in active_sessions:
            del active_sessions[session_id]


async def process_websocket_message(session_id: str, data: str):
    """Process incoming WebSocket message."""
    
    try:
        # Parse message
        message = json.loads(data)
        
        # Validate message structure
        if not validate_inbound_message(message):
            error_message = ServerError(
                error_code="INVALID_MESSAGE_FORMAT",
                error_message="Message format is invalid",
                session_id=session_id
            )
            await send_error_message(session_id, error_message)
            return
        
        # Handle different message types
        message_type = message.get("message_type", "audio_chunk")
        
        if message_type == "audio_chunk":
            await process_audio_chunk(session_id, message)
        elif message_type == "keepalive":
            # Client keepalive, just update activity
            pass
        else:
            logger.warning(f"Unknown message type: {message_type}")
    
    except json.JSONDecodeError as e:
        logger.error(f"JSON decode error for {session_id}: {e}")
        error_message = ServerError(
            error_code="JSON_PARSE_ERROR",
            error_message="Invalid JSON format",
            session_id=session_id
        )
        await send_error_message(session_id, error_message)
    
    except Exception as e:
        logger.error(f"Error processing message from {session_id}: {e}")
        error_message = ServerError(
            error_code="PROCESSING_ERROR",
            error_message=str(e),
            session_id=session_id
        )
        await send_error_message(session_id, error_message)


async def process_audio_chunk(session_id: str, message: Dict[str, Any]):
    """Process audio chunk for translation."""
    
    try:
        # Extract audio data
        audio_chunk = message.get("audio_chunk")
        chunk_index = message.get("chunk_index", 0)
        sequence_id = message.get("sequence_id")
        language_pair = message.get("language_pair", {})
        
        if not audio_chunk:
            raise ValueError("Audio chunk data is required")
        
        # Send acknowledgment for received chunk
        if sequence_id is not None:
            await websocket_manager.send_acknowledgment(session_id, sequence_id)
        
        # Update session stats
        if session_id in websocket_manager.session_metadata:
            websocket_manager.session_metadata[session_id]["chunks_processed"] += 1
        
        # Process translation
        start_time = time.time()
        
        translation_result = await translation_processor.translate_audio(
            audio_data=audio_chunk,
            source_language=language_pair.get("source", "en"),
            target_language=language_pair.get("target", "hi"),
            session_id=session_id,
            sequence_id=sequence_id
        )
        
        processing_time = (time.time() - start_time) * 1000  # Convert to milliseconds
        
        # Send translation result
        response_message = {
            "session_id": session_id,
            "message_type": "final_transcript" if translation_result.is_final else "partial_transcript",
            "chunk_index": chunk_index,
            "sequence_id": sequence_id,
            "final_transcript" if translation_result.is_final else "partial_transcript": translation_result.text,
            "confidence": translation_result.confidence,
            "processing_time_ms": processing_time,
            "timestamp": int(time.time() * 1000)
        }
        
        await websocket_manager.send_message(session_id, response_message)
        
        # Update global stats
        connection_stats.total_translations += 1
        connection_stats.successful_translations += 1
        connection_stats.avg_processing_time = (
            (connection_stats.avg_processing_time * (connection_stats.total_translations - 1) + processing_time) /
            connection_stats.total_translations
        )
        
        # Update backpressure stats
        connection_stats.backpressure_events = translation_processor.backpressure_events
        
        logger.debug(f"Translation completed for {session_id}: {translation_result.text[:50]}...")
        
    except ProcessorError as e:
        logger.error(f"Translation processor error for {session_id}: {e}")
        connection_stats.failed_translations += 1
        
        error_message = ServerError(
            error_code="TRANSLATION_ERROR",
            error_message=str(e),
            session_id=session_id
        )
        await send_error_message(session_id, error_message)
    
    except Exception as e:
        logger.error(f"Unexpected error processing audio chunk for {session_id}: {e}")
        connection_stats.failed_translations += 1
        
        error_message = ServerError(
            error_code="UNEXPECTED_ERROR",
            error_message=str(e),
            session_id=session_id
        )
        await send_error_message(session_id, error_message)


def validate_inbound_message(message: Dict[str, Any]) -> bool:
    """Validate inbound message structure."""
    
    required_fields = ["session_id", "audio_chunk"]
    
    for field in required_fields:
        if field not in message:
            logger.error(f"Missing required field: {field}")
            return False
    
    # Validate session ID format
    try:
        uuid.UUID(message["session_id"])
    except ValueError:
        logger.error(f"Invalid session ID: {message['session_id']}")
        return False
    
    # Validate audio chunk (should be base64 string)
    audio_chunk = message.get("audio_chunk")
    if not isinstance(audio_chunk, str) or len(audio_chunk) == 0:
        logger.error("Invalid audio chunk format")
        return False
    
    return True


async def send_error_message(session_id: str, error: ServerError):
    """Send error message to client."""
    
    error_message = {
        "session_id": session_id,
        "message_type": "error",
        "error_code": error.error_code,
        "error_message": error.error_message,
        "timestamp": int(time.time() * 1000)
    }
    
    if error.retry_after:
        error_message["retry_after"] = error.retry_after
    
    await websocket_manager.send_message(session_id, error_message)


# Background tasks
async def session_cleanup_task():
    """Clean up inactive sessions periodically."""
    
    while True:
        try:
            current_time = datetime.utcnow()
            inactive_sessions = []
            
            # Check for inactive sessions (5 minutes timeout)
            for session_id, session_info in active_sessions.items():
                if (current_time - session_info.last_activity).seconds > 300:
                    inactive_sessions.append(session_id)
            
            # Clean up inactive sessions
            for session_id in inactive_sessions:
                logger.info(f"Cleaning up inactive session: {session_id}")
                websocket_manager.disconnect(session_id)
                if session_id in active_sessions:
                    del active_sessions[session_id]
            
            await asyncio.sleep(60)  # Check every minute
            
        except Exception as e:
            logger.error(f"Error in session cleanup task: {e}")
            await asyncio.sleep(60)


async def stats_reporting_task():
    """Report server statistics periodically."""
    
    while True:
        try:
            logger.info(f"Server Stats - Connections: {connection_stats.active_connections}, "
                       f"Total Translations: {connection_stats.total_translations}, "
                       f"Success Rate: {connection_stats.get_success_rate():.2%}, "
                       f"Avg Processing Time: {connection_stats.avg_processing_time:.2f}ms, "
                       f"Backpressure Events: {connection_stats.backpressure_events}")
            
            await asyncio.sleep(300)  # Report every 5 minutes
            
        except Exception as e:
            logger.error(f"Error in stats reporting task: {e}")
            await asyncio.sleep(300)


# HTTP API endpoints
@app.get("/health")
@limiter.limit("100/minute")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat(),
        "active_connections": connection_stats.active_connections,
        "total_translations": connection_stats.total_translations,
        "processor_status": translation_processor.get_status() if translation_processor else "not_initialized"
    }


@app.get("/stats")
@limiter.limit("10/minute")
async def get_stats():
    """Get server statistics."""
    return {
        "connections": {
            "active": connection_stats.active_connections,
            "total": connection_stats.total_connections
        },
        "translations": {
            "total": connection_stats.total_translations,
            "successful": connection_stats.successful_translations,
            "failed": connection_stats.failed_translations,
            "success_rate": connection_stats.get_success_rate(),
            "avg_processing_time_ms": connection_stats.avg_processing_time,
            "backpressure_events": connection_stats.backpressure_events
        },
        "processor": translation_processor.get_status() if translation_processor else None,
        "sessions": len(active_sessions)
    }


@app.get("/sessions")
@limiter.limit("10/minute")
async def get_sessions():
    """Get active session information."""
    sessions = {}
    for session_id, session_info in active_sessions.items():
        metadata = websocket_manager.get_session_info(session_id)
        if metadata:
            sessions[session_id] = {
                "language_pair": session_info.language_pair,
                "connected_at": session_info.created_at.isoformat(),
                "last_activity": session_info.last_activity.isoformat(),
                "client_ip": metadata.get("client_ip"),
                "chunks_processed": metadata.get("chunks_processed", 0),
                "last_acknowledged_seq": metadata.get("last_acknowledged_seq", 0)
            }
    
    return {"sessions": sessions, "total": len(sessions)}


if __name__ == "__main__":
    # Run the server
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8080,
        reload=False,
        log_level="info",
        access_log=True,
        workers=1  # Use single worker for WebSocket support
    )