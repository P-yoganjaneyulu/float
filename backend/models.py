"""
Data models for FLOAT Translation Backend.

Pydantic models for request/response validation and structured data handling.
"""

from datetime import datetime
from typing import Optional, Dict, Any, List
from pydantic import BaseModel, Field
from enum import Enum


class ProcessorError(Exception):
    """Custom exception for translation processor errors."""
    pass


class ModelLoadError(ProcessorError):
    """Exception raised when model loading fails."""
    pass


class MessageType(str, Enum):
    """WebSocket message types."""
    AUDIO_CHUNK = "audio_chunk"
    PARTIAL_TRANSCRIPT = "partial_transcript"
    FINAL_TRANSCRIPT = "final_transcript"
    ERROR = "error"
    KEEPALIVE = "keepalive"
    CONNECTED = "connected"
    ACK = "ack"  # Acknowledgment message


class ErrorCode(str, Enum):
    """Standard error codes."""
    INVALID_MESSAGE_FORMAT = "INVALID_MESSAGE_FORMAT"
    JSON_PARSE_ERROR = "JSON_PARSE_ERROR"
    PROCESSING_ERROR = "PROCESSING_ERROR"
    TRANSLATION_ERROR = "TRANSLATION_ERROR"
    MODEL_LOAD_ERROR = "MODEL_LOAD_ERROR"
    UNSUPPORTED_LANGUAGE = "UNSUPPORTED_LANGUAGE"
    RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED"
    CONNECTION_ERROR = "CONNECTION_ERROR"
    UNEXPECTED_ERROR = "UNEXPECTED_ERROR"


class InboundMessage(BaseModel):
    """Inbound WebSocket message from client."""
    session_id: str = Field(..., description="Unique session identifier")
    chunk_index: int = Field(..., description="Audio chunk index")
    sequence_id: Optional[int] = Field(None, description="Sequence ID for ordering")
    language_pair: Dict[str, str] = Field(..., description="Source and target languages")
    audio_chunk: str = Field(..., description="Base64 encoded audio data")
    timestamp: Optional[int] = Field(None, description="Message timestamp")


class OutboundMessage(BaseModel):
    """Outbound WebSocket message to client."""
    session_id: str = Field(..., description="Unique session identifier")
    message_type: MessageType = Field(..., description="Message type")
    chunk_index: Optional[int] = Field(None, description="Associated chunk index")
    sequence_id: Optional[int] = Field(None, description="Sequence ID for ordering")
    ack_sequence_id: Optional[int] = Field(None, description="Acknowledged sequence ID")
    partial_transcript: Optional[str] = Field(None, description="Partial translation text")
    final_transcript: Optional[str] = Field(None, description="Final translation text")
    confidence: Optional[float] = Field(None, description="Translation confidence score")
    processing_time_ms: Optional[float] = Field(None, description="Processing time in milliseconds")
    error_code: Optional[str] = Field(None, description="Error code if applicable")
    error_message: Optional[str] = Field(None, description="Error message if applicable")
    timestamp: int = Field(default_factory=lambda: int(datetime.utcnow().timestamp() * 1000))


class TranslationResult(BaseModel):
    """Translation result from processor."""
    text: str = Field(..., description="Translated text")
    is_final: bool = Field(..., description="Whether this is a final result")
    confidence: Optional[float] = Field(None, description="Confidence score (0-1)")
    source_language: str = Field(..., description="Source language code")
    target_language: str = Field(..., description="Target language code")
    processing_time_ms: Optional[float] = Field(None, description="Processing time in milliseconds")


class SessionInfo(BaseModel):
    """Session information and metadata."""
    session_id: str = Field(..., description="Unique session identifier")
    language_pair: str = Field(..., description="Language pair string")
    created_at: datetime = Field(..., description="Session creation time")
    last_activity: datetime = Field(..., description="Last activity time")
    message_count: int = Field(default=0, description="Number of messages processed")
    chunks_processed: int = Field(default=0, description="Number of audio chunks processed")
    last_acknowledged_seq: int = Field(default=0, description="Last acknowledged sequence ID")


class ServerError(BaseModel):
    """Server error information."""
    error_code: ErrorCode = Field(..., description="Error code")
    error_message: str = Field(..., description="Error description")
    session_id: str = Field(..., description="Associated session ID")
    retry_after: Optional[int] = Field(None, description="Suggested retry delay in milliseconds")
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class ProcessorStatus(BaseModel):
    """Translation processor status."""
    is_loaded: bool = Field(..., description="Whether models are loaded")
    model_name: str = Field(..., description="Current model name")
    supported_languages: List[str] = Field(..., description="Supported language codes")
    memory_usage_mb: Optional[float] = Field(None, description="Memory usage in MB")
    queue_size: int = Field(default=0, description="Current processing queue size")
    total_processed: int = Field(default=0, description="Total translations processed")
    avg_processing_time_ms: float = Field(default=0.0, description="Average processing time")


class ConnectionStats(BaseModel):
    """Server connection statistics."""
    total_connections: int = Field(default=0, description="Total connections received")
    active_connections: int = Field(default=0, description="Currently active connections")
    total_translations: int = Field(default=0, description="Total translation requests")
    successful_translations: int = Field(default=0, description="Successful translations")
    failed_translations: int = Field(default=0, description="Failed translations")
    avg_processing_time: float = Field(default=0.0, description="Average processing time in ms")
    backpressure_events: int = Field(default=0, description="Number of backpressure events")
    
    def get_success_rate(self) -> float:
        """Calculate success rate as percentage."""
        if self.total_translations == 0:
            return 0.0
        return (self.successful_translations / self.total_translations) * 100


class HealthResponse(BaseModel):
    """Health check response."""
    status: str = Field(..., description="Server status")
    timestamp: datetime = Field(..., description="Response timestamp")
    active_connections: int = Field(..., description="Active WebSocket connections")
    total_translations: int = Field(..., description="Total translations processed")
    processor_status: str = Field(..., description="Translation processor status")


class StatsResponse(BaseModel):
    """Server statistics response."""
    connections: Dict[str, int] = Field(..., description="Connection statistics")
    translations: Dict[str, Any] = Field(..., description="Translation statistics")
    processor: Optional[ProcessorStatus] = Field(None, description="Processor status")
    sessions: int = Field(..., description="Active session count")


class SessionsResponse(BaseModel):
    """Active sessions response."""
    sessions: Dict[str, Dict[str, Any]] = Field(..., description="Session information")
    total: int = Field(..., description="Total number of sessions")


class LanguageInfo(BaseModel):
    """Language information."""
    code: str = Field(..., description="ISO language code")
    name: str = Field(..., description="Language name")
    native_name: str = Field(..., description="Native language name")
    is_supported: bool = Field(..., description="Whether translation is supported")
    model_available: bool = Field(..., description="Whether model is available")


class SupportedLanguagesResponse(BaseModel):
    """Supported languages response."""
    languages: List[LanguageInfo] = Field(..., description="Supported languages")
    total: int = Field(..., description="Total number of supported languages")