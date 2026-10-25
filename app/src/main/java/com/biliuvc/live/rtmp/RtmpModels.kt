package com.biliuvc.live.rtmp

/**
 * Connection / publishing state of [RtmpPublisher].
 *
 * Lifecycle: IDLE -> CONNECTING -> CONNECTED -> PUBLISHING -> STOPPED
 * Any failure ends in ERROR. After ERROR (or STOPPED) [RtmpPublisher.connect] may be called again.
 */
enum class RtmpState { IDLE, CONNECTING, CONNECTED, PUBLISHING, STOPPED, ERROR }

/**
 * Immutable snapshot of the publisher counters. All values are monotonic except [durationMs]
 * (frozen when the publisher stops) and [state].
 */
data class RtmpStats(
    val state: RtmpState,
    val publishing: Boolean,
    val durationMs: Long,
    val videoFramesSent: Long,
    val audioFramesSent: Long,
    val droppedVideoFrames: Long,
    val droppedAudioFrames: Long,
    val bytesSent: Long,
    val bitrateBps: Long,
    val lastError: String? = null
)

/**
 * Parsed RTMP endpoint.
 *
 * @param scheme      lowercase scheme, `rtmp` (supported by the publisher) or `rtmps` (parsed only)
 * @param host        host name / IP literal
 * @param port        explicit or default port (1935 for rtmp, 443 for rtmps)
 * @param app         first path segment, e.g. `live-bvc`
 * @param streamName  everything after the app segment, **including a leading `?`** when the URL has
 *                    a query (Bilibili `code` field, e.g. `?streamname=live_123&key=abc`)
 * @param tcUrl       value used for the AMF0 `connect` command, e.g. `rtmp://host/live-bvc`
 * @param originalUrl URL exactly as supplied (trimmed)
 */
data class RtmpTarget(
    val scheme: String,
    val host: String,
    val port: Int,
    val app: String,
    val streamName: String,
    val tcUrl: String,
    val originalUrl: String
)
