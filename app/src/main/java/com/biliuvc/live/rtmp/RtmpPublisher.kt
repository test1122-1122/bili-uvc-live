package com.biliuvc.live.rtmp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM RTMP publish client (RTMP 1.0, simple handshake, AMF0 commands, FLV media tags).
 *
 * Intended usage:
 * ```
 * val publisher = RtmpPublisher(listener)
 * publisher.setVideoInfo(1280, 720, 30)
 * publisher.setAudioInfo(44_100, 2)
 * publisher.connect(url)                       // asynchronous
 * publisher.sendVideoFrame(annexB, ptsUs)      // from the video encoder thread
 * publisher.sendAudioFrame(rawAac, ptsUs)      // from the audio encoder thread
 * publisher.disconnect()                       // idempotent
 * ```
 *
 * Threading: [connect] is asynchronous; all send methods are non-blocking (they enqueue into a
 * bounded queue, dropping and counting frames when full or not publishing). Listener callbacks are
 * delivered from internal worker threads. After [disconnect] the internal threads exit and
 * [stats] reports [RtmpState.STOPPED].
 *
 * Timestamps are microseconds and converted to milliseconds relative to the first media frame;
 * each track is clamped to be monotonic so slightly re-ordered encoder timestamps are tolerated.
 */
class RtmpPublisher(private val listener: Listener) {

    interface Listener {
        fun onState(state: RtmpState, detail: String?)

        fun onStats(stats: RtmpStats)

        fun onError(message: String, cause: Throwable?)
    }

    private companion object {
        const val OUT_CHUNK_SIZE = 4096
        const val DEFAULT_WINDOW_ACK_SIZE = 2_500_000
        const val HANDSHAKE_SIZE = 1536
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IO_TIMEOUT_MS = 10_000
        const val HANDSHAKE_BUDGET_MS = 20_000L
        const val RESPONSE_TIMEOUT_MS = 10_000L
        const val MAX_PENDING_MESSAGES = 120
        const val STATS_INTERVAL_MS = 500L
        const val BITRATE_WINDOW_MS = 3_000L
        const val FLASH_VERSION = "FMLE/3.0 (compatible; Lavf57.56.101)"

        const val TRANSACTION_CONNECT = 1.0
        const val TRANSACTION_CREATE_STREAM = 4.0
        const val TRANSACTION_PUBLISH = 5.0

        const val FLV_VIDEO_KEYFRAME_AVC = 0x17
        const val FLV_VIDEO_INTER_FRAME_AVC = 0x27
        const val FLV_AVC_SEQUENCE_HEADER = 0x00
        const val FLV_AVC_NALU = 0x01
        const val FLV_AAC_SEQUENCE_HEADER = 0x00
        const val FLV_AAC_RAW = 0x01
    }

    private enum class Track { VIDEO, AUDIO }

    private class OutMessage(
        val chunkStreamId: Int,
        val messageType: Int,
        val timestampMs: Long,
        val payload: ByteArray,
        val track: Track?,
        val critical: Boolean
    )

    // ---- lifecycle / state ----------------------------------------------------------------
    private val lifecycleLock = Any()
    private val failureLock = Any()
    private val writeLock = Any()
    private val pendingLock = java.lang.Object()

    @Volatile
    private var state = RtmpState.IDLE

    @Volatile
    private var publishing = false

    /**
     * Per-connection shutdown token. Worker threads capture their own token, so starting a new
     * session can never resurrect a stale thread of a previous one.
     */
    private class SessionToken {
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    @Volatile
    private var sessionToken: SessionToken? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var connectThread: Thread? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var writerThread: Thread? = null

    @Volatile
    private var statsThread: Thread? = null

    private val pending = ArrayDeque<OutMessage>()
    private val commandQueue = LinkedBlockingQueue<List<AmfValue>>(32)

    // ---- counters -------------------------------------------------------------------------
    private val videoFramesSent = AtomicLong()
    private val audioFramesSent = AtomicLong()
    private val droppedVideoFrames = AtomicLong()
    private val droppedAudioFrames = AtomicLong()
    private val bytesSent = AtomicLong()

    @Volatile
    private var publishingStartNs = 0L

    @Volatile
    private var stoppedAtNs = 0L

    private val sampleLock = Any()
    private val bitrateSamples = ArrayDeque<LongArray>()

    // ---- inbound bookkeeping --------------------------------------------------------------
    @Volatile
    private var ackWindowSize = 0L

    @Volatile
    private var bytesRead = 0L

    private var lastAckAtBytes = 0L

    // ---- stream configuration -------------------------------------------------------------
    private val videoInfoLock = Any()
    private val videoConfigLock = Any()
    private val audioLock = Any()
    private val metadataLock = Any()
    private val timestampLock = Any()

    @Volatile
    private var videoWidth = 0

    @Volatile
    private var videoHeight = 0

    @Volatile
    private var videoFps = 0

    @Volatile
    private var sampleRate = 44_100

    @Volatile
    private var audioChannels = 2

    @Volatile
    private var audioTagByte: Byte = 0xAF.toByte()

    @Volatile
    private var explicitAsc: ByteArray? = null

    private var audioConfigSent = false

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var avcConfigRecord: ByteArray? = null
    private var videoConfigSent = false
    private var metadataSent = false

    private var streamId = 1
    private var basePtsUs = Long.MIN_VALUE
    private var lastVideoTimestampMs = 0L
    private var lastAudioTimestampMs = 0L

    // ---- public API -----------------------------------------------------------------------

    /** Current publishing flag. */
    val isPublishing: Boolean
        get() = publishing && state == RtmpState.PUBLISHING

    /** Consistent snapshot of the publisher counters. */
    val stats: RtmpStats
        get() {
            val start = publishingStartNs
            val stop = stoppedAtNs
            val durationMs = if (start <= 0L) {
                0L
            } else {
                val end = if (stop > 0L) stop else System.nanoTime()
                max(0L, (end - start) / 1_000_000L)
            }
            return RtmpStats(
                state = state,
                publishing = isPublishing,
                durationMs = durationMs,
                videoFramesSent = videoFramesSent.get(),
                audioFramesSent = audioFramesSent.get(),
                droppedVideoFrames = droppedVideoFrames.get(),
                droppedAudioFrames = droppedAudioFrames.get(),
                bytesSent = bytesSent.get(),
                bitrateBps = currentBitrate(),
                lastError = lastError
            )
        }

    /** Optional, used for the `onMetaData` tag. Safe to call at any time. */
    fun setVideoInfo(width: Int, height: Int, fps: Int) {
        try {
            synchronized(videoInfoLock) {
                videoWidth = width.coerceAtLeast(0)
                videoHeight = height.coerceAtLeast(0)
                videoFps = fps.coerceAtLeast(0)
            }
        } catch (t: Throwable) {
            // never throw
        }
    }

    /** Audio sample rate / channel count used to derive the AAC sequence header. Default 44100/2. */
    fun setAudioInfo(sampleRate: Int, channels: Int) {
        try {
            if (sampleRate <= 0 || channels <= 0) return
            synchronized(audioLock) {
                this.sampleRate = sampleRate
                this.audioChannels = channels.coerceIn(1, 2)
                this.audioTagByte = AacConfig.audioTagByte(this.sampleRate, this.audioChannels)
            }
        } catch (t: Throwable) {
            // never throw
        }
    }

    /** Optional exact AudioSpecificConfig from the AAC encoder; takes precedence over [setAudioInfo]. */
    fun setAudioSpecificConfig(asc: ByteArray) {
        try {
            if (asc.isEmpty()) return
            explicitAsc = asc.copyOf()
        } catch (t: Throwable) {
            // never throw
        }
    }

    /**
     * Connects asynchronously: TCP connect, simple handshake, AMF0 `connect` / `createStream` /
     * `publish`. Failures are reported through [Listener.onError] with [RtmpState.ERROR]; a new
     * attempt may then be started by calling this method again.
     */
    fun connect(url: String) {
        try {
            synchronized(lifecycleLock) {
                when (state) {
                    RtmpState.CONNECTING, RtmpState.CONNECTED, RtmpState.PUBLISHING -> return
                    else -> Unit
                }
                cleanupSession()
                resetSessionState()
                val token = SessionToken()
                sessionToken = token
                state = RtmpState.CONNECTING
                val thread = Thread({ runConnect(url, token) }, "rtmp-connect")
                thread.isDaemon = true
                connectThread = thread
                thread.start()
            }
        } catch (t: Throwable) {
            // never throw from the public API
            val token = sessionToken
            if (token != null) {
                reportFailure(token, "failed to start RTMP connection: ${t.message}", t)
            }
        }
    }

    /** Stops publishing, closes the socket and joins the worker threads. Idempotent. */
    fun disconnect() {
        try {
            val changed = synchronized(lifecycleLock) {
                if (state == RtmpState.STOPPED && connectThread == null && socket == null) {
                    false
                } else {
                    cleanupSession()
                    stoppedAtNs = System.nanoTime()
                    state = RtmpState.STOPPED
                    true
                }
            }
            if (changed) {
                notifyState(RtmpState.STOPPED, null)
                notifyStats()
            }
        } catch (t: Throwable) {
            // never throw
        }
    }

    /**
     * Sends one Annex-B H.264 access unit. SPS/PPS NAL units are stripped and only used to build /
     * refresh the AVC sequence header; a codec-config-only buffer is consumed silently.
     *
     * The parameter sets are recorded even while the publisher is not publishing yet: the video
     * encoder starts before [connect] finishes and MediaCodec only emits its codec-config buffer
     * once, so discarding it would leave the stream without an AVC sequence header.
     */
    fun sendVideoFrame(annexB: ByteArray, ptsUs: Long) {
        try {
            if (annexB.isEmpty()) return
            val units = H264AnnexB.split(annexB)
            if (units.isEmpty()) {
                droppedVideoFrames.incrementAndGet()
                return
            }
            val hasPictureUnits = units.any {
                it.type != H264AnnexB.NAL_TYPE_SPS && it.type != H264AnnexB.NAL_TYPE_PPS
            }
            synchronized(videoConfigLock) {
                // Record parameter sets regardless of the publishing state.
                for (unit in units) {
                    when (unit.type) {
                        H264AnnexB.NAL_TYPE_SPS -> sps = unit.data
                        H264AnnexB.NAL_TYPE_PPS -> pps = unit.data
                    }
                }
                if (!isPublishing) {
                    // A codec-config-only buffer is not a picture frame: consume it, keep SPS/PPS.
                    // Picture frames produced before publish completes are dropped and counted.
                    if (hasPictureUnits) droppedVideoFrames.incrementAndGet()
                    return
                }
                val newRecord = buildVideoConfigIfChanged()
                if (newRecord != null) {
                    // The AVC sequence header must always precede the first NALU of the session,
                    // even when SPS/PPS were received before connect()/publish.
                    ensureMetadataEnqueued(ptsUs)
                    enqueue(
                        OutMessage(
                            RtmpChunkStreamId.VIDEO,
                            RtmpMessageType.VIDEO,
                            videoTimestamp(ptsUs),
                            buildAvcSequenceHeader(newRecord),
                            Track.VIDEO,
                            true
                        )
                    )
                    videoConfigSent = true
                }

                val pictureUnits = units.filter {
                    it.type != H264AnnexB.NAL_TYPE_SPS && it.type != H264AnnexB.NAL_TYPE_PPS
                }
                if (pictureUnits.isEmpty()) return // codec-config-only buffer
                if (!videoConfigSent) {
                    droppedVideoFrames.incrementAndGet()
                    return
                }
                ensureMetadataEnqueued(ptsUs)
                val keyFrame = pictureUnits.any { it.type == H264AnnexB.NAL_TYPE_IDR }
                enqueue(
                    OutMessage(
                        RtmpChunkStreamId.VIDEO,
                        RtmpMessageType.VIDEO,
                        videoTimestamp(ptsUs),
                        buildVideoFramePayload(keyFrame, pictureUnits),
                        Track.VIDEO,
                        false
                    )
                )
            }
        } catch (t: Throwable) {
            // never throw
        }
    }

    /** Sends one raw AAC frame (no ADTS). The AAC sequence header is sent before the first frame. */
    fun sendAudioFrame(rawAac: ByteArray, ptsUs: Long) {
        try {
            if (rawAac.isEmpty()) return
            if (!isPublishing) {
                droppedAudioFrames.incrementAndGet()
                return
            }
            synchronized(audioLock) {
                if (!audioConfigSent) {
                    val asc = explicitAsc ?: AacConfig.buildAudioSpecificConfig(sampleRate, audioChannels)
                    ensureMetadataEnqueued(ptsUs)
                    enqueue(
                        OutMessage(
                            RtmpChunkStreamId.AUDIO,
                            RtmpMessageType.AUDIO,
                            audioTimestamp(ptsUs),
                            buildAacSequenceHeader(asc),
                            Track.AUDIO,
                            true
                        )
                    )
                    audioConfigSent = true
                }
                val frame = AacConfig.stripAdtsHeader(rawAac)
                if (frame.isEmpty()) {
                    droppedAudioFrames.incrementAndGet()
                    return
                }
                val payload = ByteArray(2 + frame.size)
                payload[0] = audioTagByte
                payload[1] = FLV_AAC_RAW.toByte()
                frame.copyInto(payload, 2)
                ensureMetadataEnqueued(ptsUs)
                enqueue(
                    OutMessage(
                        RtmpChunkStreamId.AUDIO,
                        RtmpMessageType.AUDIO,
                        audioTimestamp(ptsUs),
                        payload,
                        Track.AUDIO,
                        false
                    )
                )
            }
        } catch (t: Throwable) {
            // never throw
        }
    }

    // ---- connection flow ------------------------------------------------------------------

    private fun runConnect(url: String, token: SessionToken) {
        try {
            val target = RtmpTargetParser.parse(url)
            if (target == null) {
                reportFailure(token, "invalid RTMP url: $url", null)
                return
            }
            if (target.scheme != "rtmp") {
                reportFailure(token, "unsupported RTMP scheme '${target.scheme}' (only rtmp:// is supported)", null)
                return
            }

            notifyState(RtmpState.CONNECTING, target.host + ":" + target.port)
            notifyStats()

            val newSocket = Socket()
            socket = newSocket
            newSocket.tcpNoDelay = true
            newSocket.soTimeout = IO_TIMEOUT_MS
            newSocket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            if (token.stopped.get()) {
                closeSocket()
                return
            }

            val socketInput = BufferedInputStream(newSocket.getInputStream())
            val socketOutput = BufferedOutputStream(newSocket.getOutputStream())
            input = socketInput
            output = socketOutput

            performHandshake(token, socketInput, socketOutput)
            synchronized(lifecycleLock) {
                if (token.stopped.get()) return
                readerThread = startThread("rtmp-reader") { runReader(socketInput, token) }
            }

            sendSetChunkSize(OUT_CHUNK_SIZE)
            sendConnectCommand(target)
            val connectResult = awaitCommand(token, RESPONSE_TIMEOUT_MS) { values ->
                val name = commandName(values)
                name == "_result" || name == "_error"
            }
            if (connectResult == null) {
                reportFailure(token, "RTMP connect timed out after ${RESPONSE_TIMEOUT_MS}ms", null)
                return
            }
            if (commandName(connectResult) == "_error") {
                reportFailure(token, "RTMP server rejected connect: ${describeCommand(connectResult)}", null)
                return
            }
            setState(RtmpState.CONNECTED, null)

            // Flow control as recommended by the RTMP spec once the connection is established.
            sendWindowAckSize(DEFAULT_WINDOW_ACK_SIZE)
            sendSetPeerBandwidth(DEFAULT_WINDOW_ACK_SIZE, 2)

            sendCreateStreamCommand()
            val createResult = awaitCommand(token, RESPONSE_TIMEOUT_MS) { values ->
                val name = commandName(values)
                name == "_result" || name == "_error"
            }
            if (createResult == null) {
                reportFailure(token, "RTMP createStream timed out after ${RESPONSE_TIMEOUT_MS}ms", null)
                return
            }
            if (commandName(createResult) == "_error") {
                reportFailure(token, "RTMP server rejected createStream: ${describeCommand(createResult)}", null)
                return
            }
            val newStreamId = extractStreamId(createResult)
            if (newStreamId <= 0) {
                reportFailure(token, "RTMP createStream returned invalid stream id $newStreamId", null)
                return
            }
            streamId = newStreamId

            sendPublishCommand(target.streamName)
            val publishResult = awaitCommand(token, RESPONSE_TIMEOUT_MS) { values ->
                val name = commandName(values)
                name == "onStatus" || name == "_error"
            }
            if (publishResult == null) {
                reportFailure(token, "RTMP publish timed out after ${RESPONSE_TIMEOUT_MS}ms", null)
                return
            }
            val (started, detail) = evaluatePublishStatus(publishResult)
            if (!started) {
                reportFailure(token, "RTMP publish rejected: $detail", null)
                return
            }

            synchronized(lifecycleLock) {
                if (token.stopped.get()) return
                publishingStartNs = System.nanoTime()
                stoppedAtNs = 0L
                publishing = true
                writerThread = startThread("rtmp-writer") { runWriter(token) }
                statsThread = startThread("rtmp-stats") { runStats(token) }
                state = RtmpState.PUBLISHING
            }
            notifyState(RtmpState.PUBLISHING, null)
            notifyStats()
        } catch (t: Throwable) {
            if (token.stopped.get()) {
                closeSocket()
            } else {
                reportFailure(token, "RTMP connect failed: ${t.message ?: t.javaClass.simpleName}", t)
            }
        }
    }

    private fun performHandshake(token: SessionToken, streamInput: InputStream, streamOutput: OutputStream) {
        val c0c1 = ByteArray(1 + HANDSHAKE_SIZE)
        c0c1[0] = 0x03
        val random = ByteArray(HANDSHAKE_SIZE - 8)
        SecureRandom().nextBytes(random)
        random.copyInto(c0c1, 9)
        synchronized(writeLock) {
            streamOutput.write(c0c1)
            streamOutput.flush()
        }

        val started = System.currentTimeMillis()
        val s0 = readHandshakeBytes(token, streamInput, 1, started)[0].toInt() and 0xFF
        if (s0 != 0x03) throw IOException("unsupported RTMP handshake version $s0")
        val s1 = readHandshakeBytes(token, streamInput, HANDSHAKE_SIZE, started)
        readHandshakeBytes(token, streamInput, HANDSHAKE_SIZE, started) // S2 (not verified)
        synchronized(writeLock) {
            streamOutput.write(s1) // C2 echoes S1
            streamOutput.flush()
        }
    }

    private fun readHandshakeBytes(token: SessionToken, streamInput: InputStream, count: Int, started: Long): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            if (token.stopped.get()) throw IOException("RTMP handshake aborted")
            if (System.currentTimeMillis() - started > HANDSHAKE_BUDGET_MS) {
                throw IOException("RTMP handshake timed out")
            }
            try {
                val n = streamInput.read(buffer, read, count - read)
                if (n < 0) throw EOFException("RTMP handshake connection closed")
                read += n
            } catch (timeout: SocketTimeoutException) {
                // Retry until the handshake budget expires.
            }
        }
        return buffer
    }

    private fun startThread(name: String, body: () -> Unit): Thread {
        val thread = Thread(body, name)
        thread.isDaemon = true
        thread.start()
        return thread
    }

    // ---- reader ---------------------------------------------------------------------------

    private fun runReader(streamInput: InputStream, token: SessionToken) {
        try {
            val reader = RtmpChunkReader(streamInput) { token.stopped.get() }
            while (!token.stopped.get()) {
                val message = reader.readMessage() ?: break
                bytesRead += message.payload.size
                handleIncoming(reader, message)
            }
        } catch (eof: EOFException) {
            if (!token.stopped.get()) reportFailure(token, "RTMP connection closed by server", eof)
        } catch (io: IOException) {
            if (!token.stopped.get()) reportFailure(token, "RTMP read failed: ${io.message}", io)
        } catch (t: Throwable) {
            if (!token.stopped.get()) reportFailure(token, "RTMP reader crashed: ${t.message}", t)
        }
    }

    private fun handleIncoming(reader: RtmpChunkReader, message: RtmpIncomingMessage) {
        when (message.type) {
            RtmpMessageType.SET_CHUNK_SIZE -> {
                if (message.payload.size >= 4) {
                    val value = readInt32(message.payload, 0) and 0x7FFFFFFF
                    if (value > 0) reader.chunkSize = value
                }
            }
            RtmpMessageType.ABORT -> Unit
            RtmpMessageType.ACKNOWLEDGEMENT -> Unit
            RtmpMessageType.USER_CONTROL -> handleUserControl(message.payload)
            RtmpMessageType.WINDOW_ACK_SIZE -> {
                if (message.payload.size >= 4) {
                    ackWindowSize = readInt32(message.payload, 0).toLong() and 0xFFFFFFFFL
                }
            }
            RtmpMessageType.SET_PEER_BANDWIDTH -> Unit
            RtmpMessageType.COMMAND_AMF0 -> dispatchCommandMessage(message.payload)
            RtmpMessageType.COMMAND_AMF3 -> {
                if (message.payload.size > 1) {
                    dispatchCommandMessage(message.payload.copyOfRange(1, message.payload.size))
                }
            }
            else -> Unit // audio/video/data/shared objects from the server are not needed
        }
        maybeSendAcknowledgement()
    }

    private fun handleUserControl(payload: ByteArray) {
        if (payload.size < 2) return
        val event = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (event == RtmpUserControlEvent.PING_REQUEST && payload.size >= 6) {
            val pong = payload.copyOfRange(0, 6)
            pong[0] = 0
            pong[1] = RtmpUserControlEvent.PING_RESPONSE.toByte()
            sendProtocolMessage(RtmpMessageType.USER_CONTROL, pong)
        }
    }

    private fun dispatchCommandMessage(payload: ByteArray) {
        val values = try {
            Amf0.decode(payload)
        } catch (t: Throwable) {
            return
        }
        if (commandName(values) == null) return
        while (!commandQueue.offer(values)) {
            if (commandQueue.poll() == null) break
        }
    }

    private fun maybeSendAcknowledgement() {
        val window = ackWindowSize
        if (window <= 0L) return
        val read = bytesRead
        if (read - lastAckAtBytes < window) return
        lastAckAtBytes = read
        try {
            sendProtocolMessage(
                RtmpMessageType.ACKNOWLEDGEMENT,
                uint32((read and 0xFFFFFFFFL).toInt())
            )
        } catch (t: Throwable) {
            // The read loop will surface a broken connection.
        }
    }

    // ---- writer ---------------------------------------------------------------------------

    private fun runWriter(token: SessionToken) {
        while (!token.stopped.get()) {
            val outMessage = synchronized(pendingLock) {
                while (!token.stopped.get() && pending.isEmpty()) {
                    try {
                        pendingLock.wait(200)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (token.stopped.get()) return
                pending.pollFirst()
            } ?: continue
            try {
                writeMessage(
                    outMessage.chunkStreamId,
                    outMessage.messageType,
                    streamId,
                    outMessage.timestampMs,
                    outMessage.payload
                )
                when (outMessage.track) {
                    Track.VIDEO -> videoFramesSent.incrementAndGet()
                    Track.AUDIO -> audioFramesSent.incrementAndGet()
                    null -> Unit
                }
            } catch (io: IOException) {
                if (!token.stopped.get()) reportFailure(token, "RTMP write failed: ${io.message}", io)
                return
            } catch (t: Throwable) {
                if (!token.stopped.get()) reportFailure(token, "RTMP writer crashed: ${t.message}", t)
                return
            }
        }
    }

    private fun runStats(token: SessionToken) {
        while (!token.stopped.get()) {
            try {
                Thread.sleep(STATS_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (token.stopped.get()) return
            notifyStats()
        }
    }

    // ---- message send helpers -------------------------------------------------------------

    private fun sendProtocolMessage(messageType: Int, payload: ByteArray) {
        writeMessage(RtmpChunkStreamId.PROTOCOL_CONTROL, messageType, 0, 0L, payload)
    }

    private fun sendSetChunkSize(chunkSize: Int) {
        sendProtocolMessage(RtmpMessageType.SET_CHUNK_SIZE, uint32(chunkSize))
    }

    private fun sendWindowAckSize(size: Int) {
        sendProtocolMessage(RtmpMessageType.WINDOW_ACK_SIZE, uint32(size))
    }

    private fun sendSetPeerBandwidth(size: Int, limitType: Int) {
        val payload = ByteArray(5)
        val encoded = uint32(size)
        encoded.copyInto(payload, 0)
        payload[4] = limitType.toByte()
        sendProtocolMessage(RtmpMessageType.SET_PEER_BANDWIDTH, payload)
    }

    private fun sendCommandMessage(values: List<AmfValue>, messageStreamId: Int) {
        val payload = Amf0.encodeAll(values)
        writeMessage(RtmpChunkStreamId.COMMAND, RtmpMessageType.COMMAND_AMF0, messageStreamId, 0L, payload)
    }

    private fun sendConnectCommand(target: RtmpTarget) {
        val properties = Amf0.obj(
            "app" to AmfString(target.app),
            "type" to AmfString("nonprivate"),
            "flashVer" to AmfString(FLASH_VERSION),
            "tcUrl" to AmfString(target.tcUrl),
            "fpad" to AmfBoolean(false),
            "audioCodecs" to AmfNumber(3575.0),
            "videoCodecs" to AmfNumber(252.0),
            "videoFunction" to AmfNumber(1.0),
            "objectEncoding" to AmfNumber(0.0)
        )
        sendCommandMessage(
            listOf(AmfString("connect"), AmfNumber(TRANSACTION_CONNECT), properties),
            0
        )
    }

    private fun sendCreateStreamCommand() {
        sendCommandMessage(
            listOf(AmfString("createStream"), AmfNumber(TRANSACTION_CREATE_STREAM), AmfNull),
            0
        )
    }

    private fun sendPublishCommand(streamName: String) {
        sendCommandMessage(
            listOf(
                AmfString("publish"),
                AmfNumber(TRANSACTION_PUBLISH),
                AmfNull,
                AmfString(streamName),
                AmfString("live")
            ),
            streamId
        )
    }

    /**
     * Writes one chunked message. Serialised on [writeLock]; counts network bytes and feeds the
     * rolling bitrate window.
     */
    private fun writeMessage(
        chunkStreamId: Int,
        messageType: Int,
        messageStreamId: Int,
        timestampMs: Long,
        payload: ByteArray
    ): Long {
        val streamOutput = output ?: throw IOException("RTMP socket is not connected")
        synchronized(writeLock) {
            RtmpChunks.writeMessage(
                streamOutput,
                OUT_CHUNK_SIZE,
                chunkStreamId,
                messageType,
                messageStreamId,
                timestampMs,
                payload
            )
            streamOutput.flush()
        }
        val size = chunkedMessageSize(payload.size, timestampMs)
        bytesSent.addAndGet(size)
        recordBitrateSample()
        return size
    }

    private fun chunkedMessageSize(payloadSize: Int, timestampMs: Long): Long {
        val extended = timestampMs >= 0xFFFFFFL
        var size = 1 + 3 + 3 + 1 + 4 + (if (extended) 4 else 0) + payloadSize
        if (payloadSize > OUT_CHUNK_SIZE) {
            val continuations = (payloadSize - 1) / OUT_CHUNK_SIZE
            size += continuations * (1 + if (extended) 4 else 0)
        }
        return size.toLong()
    }

    // ---- media queue ----------------------------------------------------------------------

    private fun enqueue(message: OutMessage) {
        if (!isPublishing) {
            countDrop(message.track)
            return
        }
        synchronized(pendingLock) {
            if (!isPublishing) {
                countDrop(message.track)
                return
            }
            if (pending.size >= MAX_PENDING_MESSAGES) {
                var victim: OutMessage? = null
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (!candidate.critical) {
                        iterator.remove()
                        victim = candidate
                        break
                    }
                }
                if (victim != null) {
                    countDrop(victim.track)
                } else if (!message.critical) {
                    countDrop(message.track)
                    return
                } else {
                    pending.pollFirst()?.let { countDrop(it.track) }
                }
            }
            pending.addLast(message)
            pendingLock.notifyAll()
        }
    }

    private fun countDrop(track: Track?) {
        when (track) {
            Track.VIDEO -> droppedVideoFrames.incrementAndGet()
            Track.AUDIO -> droppedAudioFrames.incrementAndGet()
            null -> Unit
        }
    }

    // ---- FLV payload builders -------------------------------------------------------------

    private fun buildVideoConfigIfChanged(): ByteArray? {
        val currentSps = sps ?: return null
        val currentPps = pps ?: return null
        val record = try {
            H264AnnexB.buildAvcConfigRecord(currentSps, currentPps)
        } catch (t: Throwable) {
            return null
        }
        val previous = avcConfigRecord
        if (previous != null && previous.contentEquals(record) && videoConfigSent) return null
        avcConfigRecord = record
        videoConfigSent = false
        return record
    }

    private fun buildAvcSequenceHeader(record: ByteArray): ByteArray {
        val payload = ByteArray(5 + record.size)
        payload[0] = FLV_VIDEO_KEYFRAME_AVC.toByte()
        payload[1] = FLV_AVC_SEQUENCE_HEADER.toByte()
        record.copyInto(payload, 5)
        return payload
    }

    private fun buildVideoFramePayload(
        keyFrame: Boolean,
        units: List<H264AnnexB.NalUnit>
    ): ByteArray {
        val lengthPrefixed = H264AnnexB.toLengthPrefixed(units)
        val payload = ByteArray(5 + lengthPrefixed.size)
        payload[0] = (if (keyFrame) FLV_VIDEO_KEYFRAME_AVC else FLV_VIDEO_INTER_FRAME_AVC).toByte()
        payload[1] = FLV_AVC_NALU.toByte()
        lengthPrefixed.copyInto(payload, 5)
        return payload
    }

    private fun buildAacSequenceHeader(asc: ByteArray): ByteArray {
        val payload = ByteArray(2 + asc.size)
        payload[0] = audioTagByte
        payload[1] = FLV_AAC_SEQUENCE_HEADER.toByte()
        asc.copyInto(payload, 2)
        return payload
    }

    private fun ensureMetadataEnqueued(ptsUs: Long) {
        synchronized(metadataLock) {
            if (metadataSent) return
            metadataSent = true
            enqueue(
                OutMessage(
                    RtmpChunkStreamId.DATA,
                    RtmpMessageType.DATA_AMF0,
                    mediaTimestamp(ptsUs, null),
                    buildMetadataPayload(),
                    null,
                    true
                )
            )
        }
    }

    private fun buildMetadataPayload(): ByteArray {
        val properties = LinkedHashMap<String, AmfValue>()
        properties["duration"] = AmfNumber(0.0)
        val width = videoWidth
        val height = videoHeight
        val fps = videoFps
        if (width > 0) properties["width"] = AmfNumber(width)
        if (height > 0) properties["height"] = AmfNumber(height)
        if (fps > 0) properties["framerate"] = AmfNumber(fps)
        properties["videocodecid"] = AmfNumber(7.0)
        properties["audiocodecid"] = AmfNumber(10.0)
        properties["audiosamplerate"] = AmfNumber(sampleRate.toDouble())
        properties["audiosamplesize"] = AmfNumber(16.0)
        properties["stereo"] = AmfBoolean(audioChannels > 1)
        properties["encoder"] = AmfString("BiliUVC")
        properties["filesize"] = AmfNumber(0.0)
        return Amf0.encodeAll(
            listOf(
                AmfString("@setDataFrame"),
                AmfString("onMetaData"),
                AmfEcmaArray(properties)
            )
        )
    }

    // ---- timestamps / bitrate -------------------------------------------------------------

    private fun videoTimestamp(ptsUs: Long): Long = mediaTimestamp(ptsUs, Track.VIDEO)

    private fun audioTimestamp(ptsUs: Long): Long = mediaTimestamp(ptsUs, Track.AUDIO)

    /**
     * Converts a microsecond PTS to a millisecond RTMP timestamp relative to the first media frame.
     * Per track the value is clamped to be monotonic, tolerating slightly out-of-order timestamps
     * coming from different encoder threads.
     */
    private fun mediaTimestamp(ptsUs: Long, track: Track?): Long {
        synchronized(timestampLock) {
            if (basePtsUs == Long.MIN_VALUE) basePtsUs = ptsUs
            var deltaMs = (ptsUs - basePtsUs) / 1_000L
            if (deltaMs < 0L) deltaMs = 0L
            when (track) {
                Track.VIDEO -> {
                    if (deltaMs < lastVideoTimestampMs) deltaMs = lastVideoTimestampMs
                    lastVideoTimestampMs = deltaMs
                }
                Track.AUDIO -> {
                    if (deltaMs < lastAudioTimestampMs) deltaMs = lastAudioTimestampMs
                    lastAudioTimestampMs = deltaMs
                }
                null -> Unit
            }
            return deltaMs and 0xFFFFFFFFL
        }
    }

    private fun recordBitrateSample() {
        val start = publishingStartNs
        if (start <= 0L) return
        synchronized(sampleLock) {
            val elapsed = (System.nanoTime() - start) / 1_000_000L
            bitrateSamples.addLast(longArrayOf(elapsed, bytesSent.get()))
            while (bitrateSamples.size > 2 && elapsed - bitrateSamples.first[0] > BITRATE_WINDOW_MS) {
                bitrateSamples.removeFirst()
            }
        }
    }

    private fun currentBitrate(): Long {
        synchronized(sampleLock) {
            if (bitrateSamples.isEmpty()) return 0L
            val first = bitrateSamples.first
            val last = bitrateSamples.last
            val elapsedMs = last[0] - first[0]
            val bytes = last[1] - first[1]
            if (elapsedMs >= 200L) return bytes * 8_000L / elapsedMs
            val total = publishingStartNs
            if (total <= 0L) return 0L
            val durationMs = (System.nanoTime() - total) / 1_000_000L
            if (durationMs <= 0L) return 0L
            return bytesSent.get() * 8_000L / durationMs
        }
    }

    // ---- state / failure handling ---------------------------------------------------------

    private fun setState(newState: RtmpState, detail: String?) {
        state = newState
        notifyState(newState, detail)
        notifyStats()
    }

    private fun reportFailure(token: SessionToken, message: String, cause: Throwable?) {
        synchronized(failureLock) {
            // Ignore failures reported by a stale session thread.
            if (token !== sessionToken || token.stopped.get()) return
            token.stopped.set(true)
            publishing = false
            lastError = message
            if (stoppedAtNs == 0L) stoppedAtNs = System.nanoTime()
            state = RtmpState.ERROR
        }
        closeSocket()
        synchronized(pendingLock) {
            pending.clear()
            pendingLock.notifyAll()
        }
        commandQueue.clear()
        notifyState(RtmpState.ERROR, message)
        notifyError(message, cause)
        notifyStats()
    }

    private fun notifyState(newState: RtmpState, detail: String?) {
        try {
            listener.onState(newState, detail)
        } catch (t: Throwable) {
            // A listener must not break the publisher.
        }
    }

    private fun notifyStats() {
        try {
            listener.onStats(stats)
        } catch (t: Throwable) {
            // A listener must not break the publisher.
        }
    }

    private fun notifyError(message: String, cause: Throwable?) {
        try {
            listener.onError(message, cause)
        } catch (t: Throwable) {
            // A listener must not break the publisher.
        }
    }

    // ---- teardown -------------------------------------------------------------------------

    private fun cleanupSession() {
        val token = sessionToken
        token?.stopped?.set(true)
        sessionToken = null
        publishing = false
        closeSocket()
        synchronized(pendingLock) {
            pending.clear()
            pendingLock.notifyAll()
        }
        commandQueue.clear()
        interruptQuietly(connectThread)
        interruptQuietly(readerThread)
        interruptQuietly(writerThread)
        interruptQuietly(statsThread)
        joinQuietly(connectThread)
        joinQuietly(readerThread)
        joinQuietly(writerThread)
        joinQuietly(statsThread)
        connectThread = null
        readerThread = null
        writerThread = null
        statsThread = null
        input = null
        output = null
    }

    private fun resetSessionState() {
        publishing = false
        lastError = null
        publishingStartNs = 0L
        stoppedAtNs = 0L
        socket = null
        input = null
        output = null
        connectThread = null
        readerThread = null
        writerThread = null
        statsThread = null
        synchronized(pendingLock) { pending.clear() }
        commandQueue.clear()
        videoFramesSent.set(0L)
        audioFramesSent.set(0L)
        droppedVideoFrames.set(0L)
        droppedAudioFrames.set(0L)
        bytesSent.set(0L)
        bytesRead = 0L
        lastAckAtBytes = 0L
        ackWindowSize = 0L
        synchronized(sampleLock) { bitrateSamples.clear() }
        synchronized(videoConfigLock) {
            // Cached SPS/PPS survive a reconnect: they belong to the encoder, not the RTMP session.
            avcConfigRecord = null
            videoConfigSent = false
            metadataSent = false
        }
        synchronized(audioLock) {
            audioConfigSent = false
        }
        synchronized(timestampLock) {
            basePtsUs = Long.MIN_VALUE
            lastVideoTimestampMs = 0L
            lastAudioTimestampMs = 0L
        }
        streamId = 1
    }

    private fun closeSocket() {
        val current = socket
        socket = null
        if (current != null) {
            try {
                current.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun interruptQuietly(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.interrupt()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun joinQuietly(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(1_500)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- command response helpers ---------------------------------------------------------

    private fun awaitCommand(
        token: SessionToken,
        timeoutMs: Long,
        predicate: (List<AmfValue>) -> Boolean
    ): List<AmfValue>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!token.stopped.get()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return null
            val values = try {
                commandQueue.poll(remaining, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } ?: return null
            if (predicate(values)) return values
        }
        return null
    }

    private fun commandName(values: List<AmfValue>?): String? {
        if (values == null || values.isEmpty()) return null
        return (values[0] as? AmfString)?.value
    }

    private fun describeCommand(values: List<AmfValue>?): String {
        if (values == null) return "no response"
        val name = commandName(values) ?: "?"
        for (value in values) {
            val properties = when (value) {
                is AmfObject -> value.properties
                is AmfEcmaArray -> value.properties
                else -> null
            } ?: continue
            val code = (properties["code"] as? AmfString)?.value
            if (code != null) return "$name ($code)"
        }
        return name
    }

    private fun extractStreamId(values: List<AmfValue>): Int {
        // Standard layout: ["_result", transactionId, null, streamId]
        if (values.size > 3) {
            val streamIdValue = values[3]
            if (streamIdValue is AmfNumber) {
                val id = streamIdValue.value.toInt()
                if (id > 0) return id
            }
        }
        for (index in values.indices.reversed()) {
            val value = values[index]
            if (value is AmfNumber && value.value >= 1.0) return value.value.toInt()
        }
        return 0
    }

    private fun evaluatePublishStatus(values: List<AmfValue>?): Pair<Boolean, String> {
        if (values == null) return false to "no response"
        if (commandName(values) == "_error") {
            return false to describeCommand(values)
        }
        var code: String? = null
        var level: String? = null
        for (value in values) {
            val properties = when (value) {
                is AmfObject -> value.properties
                is AmfEcmaArray -> value.properties
                else -> null
            } ?: continue
            code = (properties["code"] as? AmfString)?.value ?: code
            level = (properties["level"] as? AmfString)?.value ?: level
        }
        if (code == null) return false to "publish response without code"
        if (code == "NetStream.Publish.Start" || code.startsWith("NetStream.Publish.Start")) {
            return true to code
        }
        return false to "$code (level=${level ?: "unknown"})"
    }
}

// ---- little-endian / big-endian helpers used by the publisher's control messages -----------

private fun uint32(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte()
)

private fun readInt32(payload: ByteArray, offset: Int): Int =
    ((payload[offset].toInt() and 0xFF) shl 24) or
        ((payload[offset + 1].toInt() and 0xFF) shl 16) or
        ((payload[offset + 2].toInt() and 0xFF) shl 8) or
        (payload[offset + 3].toInt() and 0xFF)
