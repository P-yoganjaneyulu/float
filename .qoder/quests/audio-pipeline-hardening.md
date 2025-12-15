# Audio Pipeline Hardening Design

## Overview

Production-grade reliability enhancement for the FLOAT real-time speech-to-speech translation pipeline. The design addresses five critical failure scenarios to ensure zero crashes, zero audio loss, and zero loud artifacts under adverse conditions including network instability, corrupted data, and backend congestion.

## Current Architecture Analysis

### Client Audio Flow
1. Audio capture → microphone input
2. Audio chunks sent via WebSocket → base64 encoded JSON
3. Translated audio received via WebSocket
4. Audio playback via StreamingAudioBuffer → AudioTrack

### Backend Translation Flow
1. WebSocket receives audio chunk
2. SeamlessM4T API processes S2S translation
3. Response sent back to client
4. Sequential processing per session

### Identified Vulnerabilities

| Scenario | Current Behavior | Risk Level |
|----------|-----------------|------------|
| Network disconnect during capture | Audio chunks lost permanently | CRITICAL |
| Out-of-order chunk arrival | No sequencing validation | HIGH |
| Corrupted PCM data | No integrity checks | HIGH |
| Buffer underrun | Playback stops abruptly | MEDIUM |
| Backend delay | Unbounded queue growth | HIGH |

## Failure Scenario Remediation

### Scenario 1: Network Jitter & Temporary Disconnects

#### Problem Statement
When the WebSocket disconnects temporarily, audio chunks captured during the disconnect period are lost because there is no client-side resend mechanism. The system does not track acknowledgments or maintain a retry buffer.

#### Design Solution

**Resendable Client-Side Audio Buffer**

Introduce a persistent send queue with acknowledgment tracking to enable automatic chunk resend after reconnection.

**Data Model Enhancements**

New fields added to OutboundMessage:
- sequenceId: Monotonically increasing identifier for each audio chunk
- clientTimestamp: Client-side capture timestamp for latency tracking
- isRetransmission: Boolean flag indicating resent chunk

New acknowledgment message type from server:
- message_type: "chunk_ack"
- last_acked_sequence: Server confirms receipt up to this sequence ID

**Send Queue Architecture**

Structure:
- CircularBuffer with maximum capacity of 50 chunks (approximately 10 seconds at 200ms per chunk)
- Each entry contains: sequenceId, audioData, timestamp, retryCount, ackReceived flag

Behavior:
- On chunk capture: add to queue with ackReceived = false
- On successful send: mark as pending acknowledgment
- On ACK receipt: mark ackReceived = true, eligible for removal
- On disconnect: retain all unacknowledged chunks
- On reconnect: resend chunks where ackReceived = false, oldest first
- Maximum 3 retries per chunk before permanent drop with logged warning

Overflow policy:
- When buffer reaches capacity, drop oldest unacknowledged chunks
- Emit congestion metric to UI layer
- Log dropped chunk sequence IDs for diagnostics

**Modified WebSocket Connection Lifecycle**

Connection establishment:
1. Client sends session initialization with last known sequence ID
2. Server responds with last processed sequence ID
3. Client resends gap chunks if any

Disconnection handling:
1. WebSocket enters RECONNECTING state
2. Audio capture continues, chunks buffered
3. Retry with exponential backoff (existing mechanism)
4. On reconnect, flush unacknowledged chunks

**Server-Side Acknowledgment Logic**

Server behavior:
- Process incoming chunk sequentially
- After successful translation processing, send chunk_ack message
- Track per-session last acknowledged sequence ID
- Detect and log duplicate chunks (idempotent processing)

Duplicate detection strategy:
- Server maintains sliding window of last 100 processed sequence IDs per session
- If duplicate detected: send ACK but skip translation processing
- Log duplicate for monitoring

**Latency Impact Analysis**

Additional overhead per chunk:
- ACK message transmission: ~50 bytes
- Client-side queue management: ~5 microseconds
- Network RTT for ACK: negligible (piggyback on next message when possible)

Estimated total latency increase: <5ms per chunk

---

### Scenario 2: Out-of-Order Chunk Arrival

#### Problem Statement
Network jitter can cause audio chunks to arrive at the playback layer out of sequence, leading to garbled or nonsensical audio output. The current implementation has no sequencing logic.

#### Design Solution

**Sequence Validation Pipeline**

Implement three-stage validation before playback:
1. Sequence number extraction and validation
2. Reordering buffer with time-bounded wait
3. Gap detection and silence insertion

**Reordering Buffer Design**

Structure:
- PriorityQueue ordered by sequence ID
- Maximum window size: 10 chunks
- Maximum wait time: 300ms per chunk

Algorithm:
1. Incoming chunk placed in priority queue by sequence ID
2. Playback consumes chunks in strict sequential order
3. If next expected chunk missing: wait up to 300ms
4. After timeout: insert silence frame, advance sequence counter
5. Late arrivals beyond window are dropped with warning log

**Sequence Gap Handling**

Missing chunk detection:
- Track expected next sequence ID
- On gap detection: start 300ms timer
- If chunk arrives within timeout: reorder and play
- If timeout expires: synthesize silence frame

Silence frame specification:
- Duration: match average chunk duration from recent history
- Format: PCM silence (zero amplitude)
- Metadata: mark as synthetic for metrics

**Sequence Drift Recovery**

Scenario: client and server sequence counters diverge

Recovery mechanism:
- Every 100 chunks, client sends sequence synchronization heartbeat
- Server validates against expected sequence
- If drift detected: server sends sequence reset command
- Client aligns to server's authoritative sequence counter

**Buffer State Monitoring**

Metrics exposed to UI layer:
- Reordering buffer occupancy percentage
- Number of out-of-order chunks per minute
- Number of silence insertions per minute
- Average reordering delay

Alert thresholds:
- Buffer >80% full: warn of high jitter
- >5 silence insertions per minute: suggest network diagnostics

---

### Scenario 3: Empty / Corrupted Audio Chunks

#### Problem Statement
Corrupted PCM data can reach the playback layer without validation, potentially causing loud artifacts, speaker damage, or application crashes.

#### Design Solution

**Multi-Layer Validation Pipeline**

Validation occurs at three checkpoints:
1. Server-side validation before S2S translation
2. Client-side validation upon WebSocket receipt
3. Pre-playback validation in StreamingAudioBuffer

**PCM Integrity Checks**

Length validation:
- Minimum chunk size: 1600 bytes (100ms at 16kHz mono 16-bit)
- Maximum chunk size: 32000 bytes (safety threshold)
- Size must be multiple of 2 (16-bit samples)

Amplitude validation:
- Scan for NaN or Infinity values in floating-point representation
- Check for DC offset beyond ±10000 (16-bit range)
- Detect extreme peak amplitudes (>95% of Int16 range)

Statistical sanity checks:
- Calculate RMS over chunk
- Reject if RMS = 0 (complete silence suggests corruption)
- Reject if RMS exceeds expected maximum (screaming audio suggests corruption)
- Compare with rolling average of recent chunks (sudden 10x spike suggests corruption)

**Corruption Handling Policy**

Validation failure response:
1. Log corruption details (type, sequence ID, statistics)
2. Drop corrupted chunk entirely
3. Replace with silence frame of equivalent duration
4. Emit corruption metric to UI
5. Continue processing subsequent chunks

Silence replacement strategy:
- Duration: match the corrupted chunk's declared duration
- Amplitude: pure zero-crossing PCM
- Transition: apply 5ms fade-in/out to prevent clicks

**Server-Side Preemptive Validation**

Backend validation before API call:
- Check base64 decode integrity
- Validate PCM structure before sending to SeamlessM4T
- Return validation error to client if corrupted

Benefit: prevents wasted API calls and reduces latency

**Corruption Metrics and Alerting**

Track per session:
- Total corruption events
- Corruption rate (corrupted chunks / total chunks)
- Corruption type distribution

Alert threshold:
- If corruption rate >5%: display warning banner suggesting microphone diagnostics
- If corruption rate >20%: recommend app restart

---

### Scenario 4: Playback Starvation & Buffer Underruns

#### Problem Statement
The current implementation starts playback immediately upon receiving the first chunk, leading to frequent start-stop cycles when chunks arrive sporadically. There is no jitter buffering or underrun handling.

#### Design Solution

**Adaptive Jitter Buffer**

Prefetch threshold:
- Minimum buffer depth: 3 chunks (~600ms) before playback start
- Dynamic threshold adjustment based on observed jitter

Startup behavior:
1. Client receives first chunk → enter BUFFERING state
2. Accumulate chunks until buffer depth ≥ 3
3. Transition to PLAYING state
4. Begin consuming chunks from buffer

**Underrun Prevention**

Continuous monitoring:
- Track buffer occupancy during playback
- If buffer drops to 1 chunk: enter PRE_UNDERRUN warning state
- If buffer reaches 0 chunks: enter UNDERRUN state

Underrun handling:
1. DO NOT stop playback thread
2. Insert silence frames to maintain audio stream continuity
3. Apply 10ms fade-out to current audio, 10ms fade-in when resuming
4. Log underrun event with timestamp and duration
5. Resume normal playback when buffer refills to ≥2 chunks

**Silence Padding Strategy**

Underrun silence characteristics:
- Generated in 100ms increments
- Pure PCM silence (zero amplitude)
- Smooth fade transitions to avoid clicks
- Maximum consecutive silence: 5 seconds (then stop playback with error)

Transition smoothing:
- Last 10ms of real audio: apply fade-out envelope
- First 10ms of resumed audio: apply fade-in envelope
- Envelope shape: quadratic (smoother than linear)

**Adaptive Buffer Sizing**

Dynamic threshold adjustment:
- Measure inter-chunk arrival interval over 30-second window
- Calculate 95th percentile arrival jitter
- Set prefetch threshold = max(3 chunks, 2 × jitter interval)
- Re-evaluate every 30 seconds

Example:
- If jitter is consistently low: reduce to 3 chunks (minimize latency)
- If jitter spikes to 400ms: increase to 5 chunks (ensure continuity)

**Playback State Machine**

States:
- STOPPED: no playback, buffer empty
- BUFFERING: accumulating chunks before start
- PLAYING: active playback, consuming buffer
- PRE_UNDERRUN: buffer critically low, warn UI
- UNDERRUN: buffer empty, inserting silence
- PAUSED: user-initiated pause

Transitions:
```
STOPPED → BUFFERING (on first chunk arrival)
BUFFERING → PLAYING (when buffer ≥ threshold)
PLAYING → PRE_UNDERRUN (when buffer ≤ 1)
PRE_UNDERRUN → UNDERRUN (when buffer = 0)
UNDERRUN → PLAYING (when buffer ≥ 2)
PLAYING → STOPPED (on user stop or session end)
```

**Metrics and Monitoring**

Track per session:
- Total underrun events
- Total underrun duration (cumulative silence inserted)
- Average buffer occupancy during playback
- Prefetch threshold adjustments count

Alert threshold:
- If underrun rate >10 events per minute: display network quality warning

---

### Scenario 5: Backend Delay & Backpressure Handling

#### Problem Statement
Sequential backend processing creates a bottleneck under load. The client has no backpressure mechanism, leading to unbounded queue growth and eventual memory exhaustion. Users receive no feedback about processing delays.

#### Design Solution

**Backend Parallel Processing**

Current limitation: one chunk processed at a time per session

Enhanced design:
- Process up to 3 chunks concurrently per session using asyncio task pool
- Preserve output order using sequence IDs
- Limit total concurrent translations across all sessions to 50 (prevents API rate limit breach)

Implementation:
- Replace sequential await with asyncio.gather for batch processing
- Introduce per-session semaphore (max=3) to limit concurrency
- Global semaphore (max=50) to limit total server load

Queue management:
- Maximum queue depth per session: 20 chunks
- When queue exceeds limit: return backpressure signal to client
- Client must pause sending until queue drains

**Client-Side Backpressure Policy**

Send throttling:
1. Client tracks number of unacknowledged chunks
2. If unacknowledged count ≥15: pause audio transmission
3. Wait for acknowledgments to arrive
4. Resume transmission when unacknowledged count <10

Overflow handling:
- If send queue exceeds 50 chunks: drop oldest chunks
- Prioritize most recent audio (better than buffering stale data)
- Emit buffer overflow metric to UI

**Latency Visibility**

Congestion indicators sent from server:
- Processing queue depth
- Estimated processing delay (queue depth × average chunk processing time)

Client displays:
- Visual indicator when latency >2 seconds
- Suggested action: "High server load, experiencing delays"

Metric exposure:
- Average end-to-end latency per chunk
- 95th percentile latency
- Current send queue depth

**Backend Queue Bounds**

Per-session queue enforcement:
- Hard limit: 20 chunks
- Soft limit: 15 chunks (send warning)
- When hard limit reached: return error code "QUEUE_FULL"

Client response to QUEUE_FULL:
- Stop sending new chunks
- Wait 500ms
- Retry sending with exponential backoff
- Display "Server busy, retrying" message

**Graceful Degradation**

When backend is overloaded:
1. Server sends congestion warning after soft limit
2. Client reduces capture quality (if applicable) or pauses transmission
3. Server prioritizes draining existing queue over accepting new chunks
4. Once queue drains below soft limit, resume normal operation

---

## Cross-Cutting Concerns

### Logging Strategy

Client-side logging:
- All chunk drops (sequence ID, reason, timestamp)
- All corruption events (statistics, validation failure type)
- All underrun events (duration, buffer state)
- All backpressure activations (queue depth, action taken)

Server-side logging:
- All duplicate chunk detections
- All queue overflow events
- All validation failures
- Latency percentiles every 60 seconds

Log level guidance:
- INFO: normal operations, metrics
- WARN: recoverable failures, backpressure activation
- ERROR: data loss, corruption, persistent failures

### Error Recovery Philosophy

Guiding principle: silence is always preferred over distortion or crashes

Failure response hierarchy:
