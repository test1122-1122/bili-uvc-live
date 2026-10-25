package com.biliuvc.live.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 硬编码器（MediaCodec video/avc）。
 *
 * 输入：NV21（YVU420SemiPlanar）帧；输出：Annex-B H.264 帧 + 单调递增 PTS（µs）。
 *
 * ## 线程模型
 * - [encodeNv21Frame] / [requestKeyFrame] / [stop] 可在任意线程调用；[encodeNv21Frame] **不阻塞**，
 *   入队满（队列长度 3）时丢弃最旧帧并计入 [droppedFrames]。
 * - 编码在独立工作线程 `H264Encoder` 中完成；[Listener.onEncoded] / [Listener.onError]
 *   回调在该工作线程（不是主线程）。
 *
 * ## 数据约定
 * - 入参 `nv21.size` 必须 >= `width * height * 3 / 2`，否则该帧被丢弃。
 * - 每帧独立数组由调用方保证（UvcController.onFrame 已满足）；本类持有引用直到编码完成，
 *   调用方不得复用同一个数组。
 * - 输出为 Annex-B（每个 NAL 前 4 字节 `00 00 00 01`）。**关键帧**会在最前面补上
 *   SPS/PPS（来自 codec-config），这样 RtmpPublisher 可以直接从关键帧里提取 SPS/PPS 组装
 *   AVCDecoderConfigurationRecord。
 *
 * ## 无真机验证点 / 已知风险
 * 1. 部分设备编码器不声明 `COLOR_FormatYUV420SemiPlanar`：会尝试
 *    `COLOR_FormatYUV420Flexible`（用 `getInputImage()` 按 plane 填充）。若该编码器实际仍按
 *    NV12 解释 buffer，颜色可能异常 —— 需要在目标机型上验证。
 * 2. 宽高对齐：这里只保证偶数；某些编码器要求 16 对齐（如 1080 对某些硬件需要传 1088），
 *    失败时 configure 抛异常并走 onError。
 * 3. `KEY_I_FRAME_INTERVAL=2` 秒，`requestKeyFrame()` 通过 `PARAMETER_KEY_REQUEST_SYNC_FRAME`
 *    在下一帧生效（个别编码器忽略 request-sync，需要真机确认）。
 * 4. 输出 PTS 直接透传 `encodeNv21Frame(ptsUs)`；若 <= 0 用 `System.nanoTime()/1000` 兜底。
 */
// COLOR_FormatYUV420SemiPlanar / INFO_OUTPUT_BUFFERS_CHANGED 在 Android SDK 中被标记 deprecated
// （官方推荐 Flexible），但 ARCHITECTURE.md 4.3 明确要求使用 SemiPlanar，故这里刻意保留。
@Suppress("DEPRECATION")
class H264Encoder(
    width: Int,
    height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val listener: Listener
) {

    interface Listener {
        /** Annex-B 编码帧（关键帧已含 SPS/PPS）；回调在编码线程。 */
        fun onEncoded(annexB: ByteArray, ptsUs: Long)

        /** 编码器错误（已捕获异常，不崩溃）；回调在编码线程。 */
        fun onError(message: String)
    }

    private val mWidth: Int = normalizeDimension(width)
    private val mHeight: Int = normalizeDimension(height)
    private val expectedNv21Size: Int = mWidth * mHeight * 3 / 2

    private val queue = LinkedBlockingDeque<Frame>(QUEUE_CAPACITY)
    private val queueLock = Any()
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val keyFrameRequested = AtomicBoolean(false)
    private val dropped = AtomicLong(0)

    @Volatile
    private var worker: Thread? = null

    /** 实际配置成功的 input color format（用于 input 填充分支）。 */
    @Volatile
    private var colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    /** SPS/PPS 的 Annex-B 形式；用于给关键帧补头。 */
    @Volatile
    private var csdAnnexB: ByteArray? = null

    /** 编码线程是否已经跑起来（用于延迟报错/日志）。 */
    @Volatile
    private var codecStarted = false

    /** 已丢弃的帧数（队列满丢最旧 + input buffer 不可用）。 */
    val droppedFrames: Long
        get() = dropped.get()

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get()) {
                Log.d(TAG, "start(): already started")
                return
            }
            if (mWidth <= 0 || mHeight <= 0) {
                reportError("H264 编码器宽高非法: ${mWidth}x$mHeight")
                return
            }
            if (expectedNv21Size <= 0) {
                reportError("H264 编码器尺寸非法: ${mWidth}x$mHeight")
                return
            }
            keyFrameRequested.set(false)
            codecStarted = false
            csdAnnexB = null
            running.set(true)
            val thread = Thread({ runEncoderLoop() }, "H264Encoder")
            worker = thread
            thread.start()
        }
    }

    /** 非阻塞入队；队列满（3）时丢最旧帧。 */
    fun encodeNv21Frame(nv21: ByteArray, ptsUs: Long) {
        if (!running.get()) return
        if (nv21.size < expectedNv21Size) {
            dropped.incrementAndGet()
            Log.w(
                TAG,
                "drop frame: size=${nv21.size} < expected=$expectedNv21Size " +
                    "(${mWidth}x$mHeight)"
            )
            return
        }
        val frame = Frame(nv21, ptsUs)
        synchronized(queueLock) {
            if (queue.size >= QUEUE_CAPACITY) {
                val removed = queue.pollFirst()
                if (removed != null) {
                    dropped.incrementAndGet()
                    Log.w(TAG, "queue full, dropped oldest frame")
                }
            }
            if (!queue.offerLast(frame)) {
                dropped.incrementAndGet()
            }
        }
    }

    /** 请求下一帧为 IDR。 */
    fun requestKeyFrame() {
        if (running.get()) {
            keyFrameRequested.set(true)
        }
    }

    fun stop() {
        val thread: Thread?
        synchronized(lifecycleLock) {
            if (!running.getAndSet(false) && worker == null) {
                Log.d(TAG, "stop(): already stopped")
                return
            }
            thread = worker
            worker = null
        }
        queue.clear()
        keyFrameRequested.set(false)
        if (thread != null) {
            thread.interrupt()
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(STOP_JOIN_TIMEOUT_MS)
                    if (thread.isAlive) Log.w(TAG, "encoder thread did not exit in time")
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // 编码线程
    // ------------------------------------------------------------------------------------

    private fun runEncoderLoop() {
        var codec: MediaCodec? = null
        try {
            val created = createAndConfigureCodec()
            codec = created.first
            colorFormat = created.second
            codec.start()
            codecStarted = true
            Log.i(
                TAG,
                "encoder started ${mWidth}x$mHeight fps=$fps bitrate=$bitrateBps " +
                    "colorFormat=$colorFormat"
            )
            val bufferInfo = MediaCodec.BufferInfo()
            while (running.get()) {
                val frame = pollFrame()
                if (frame == null) continue
                if (!running.get()) break

                val ptsUs = if (frame.ptsUs > 0) frame.ptsUs else System.nanoTime() / 1000L
                if (keyFrameRequested.compareAndSet(true, false)) {
                    requestSyncFrame(codec)
                }

                val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inIndex < 0) {
                    dropped.incrementAndGet()
                    Log.w(TAG, "no input buffer available, drop frame")
                } else {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    var queuedSize = 0
                    if (inputBuffer != null) {
                        val filled = try {
                            fillInputBuffer(codec, inIndex, inputBuffer, frame.nv21)
                        } catch (t: Throwable) {
                            Log.e(TAG, "fillInputBuffer failed", t)
                            false
                        }
                        if (filled) queuedSize = expectedNv21Size else dropped.incrementAndGet()
                    } else {
                        dropped.incrementAndGet()
                    }
                    codec.queueInputBuffer(inIndex, 0, queuedSize, ptsUs, 0)
                }

                drainOutput(codec, bufferInfo)
            }
            // 尽量把已编码帧吐完，避免 stop 时丢最后一帧。
            if (codecStarted) {
                drainOutput(codec, bufferInfo)
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.e(TAG, "encoder loop failed", t)
                reportError("H264 编码器异常: ${t.message ?: t.javaClass.simpleName}")
            } else {
                Log.i(TAG, "encoder loop exit: ${t.message}")
            }
        } finally {
            running.set(false)
            codecStarted = false
            try {
                codec?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "codec.stop failed", t)
            }
            try {
                codec?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "codec.release failed", t)
            }
            queue.clear()
        }
    }

    private fun pollFrame(): Frame? {
        return try {
            queue.pollFirst(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            null
        }
    }

    // ------------------------------------------------------------------------------------
    // MediaCodec 配置
    // ------------------------------------------------------------------------------------

    private fun createAndConfigureCodec(): Pair<MediaCodec, Int> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val advertised: Set<Int> = try {
            val probe = MediaCodec.createEncoderByType(mime)
            try {
                probe.codecInfo.getCapabilitiesForType(mime).colorFormats.toSet()
            } finally {
                try {
                    probe.release()
                } catch (t: Throwable) {
                    // ignore
                }
            }
        } catch (t: Throwable) {
            throw IllegalStateException(
                "设备没有可用的 H.264 编码器: ${t.message ?: t.javaClass.simpleName}", t
            )
        }
        Log.i(TAG, "encoder color formats: ${advertised.joinToString()}")

        val candidates = ArrayList<Int>(4)
        if (advertised.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
            candidates.add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        }
        if (advertised.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
            candidates.add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        // 兜底：即使编码器没有声明 SemiPlanar，也尝试配置一次（部分厂商编码器可用）。
        if (!candidates.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
            candidates.add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        }
        // 兜底：Flexible 作为最后的尝试（见类注释风险 1）。
        if (!candidates.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
            candidates.add(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }

        var lastError: Throwable? = null
        for (format in candidates) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createEncoderByType(mime)
                val mediaFormat = MediaFormat.createVideoFormat(mime, mWidth, mHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, format)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (bitrateBps > 0) bitrateBps else DEFAULT_BITRATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, if (fps > 0) fps else DEFAULT_FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                }
                codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "configured colorFormat=$format (advertised=${advertised.contains(format)})")
                return codec to format
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "configure colorFormat=$format failed: ${t.message}")
                try {
                    codec?.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "release failed codec", e)
                }
            }
        }
        throw IllegalStateException(
            "无法配置 H.264 编码器（已尝试 ${candidates.joinToString()}）: " +
                "${lastError?.message ?: lastError?.javaClass?.simpleName}",
            lastError
        )
    }

    private fun requestSyncFrame(codec: MediaCodec) {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            Log.d(TAG, "requested sync frame")
        } catch (t: Throwable) {
            Log.w(TAG, "request sync frame failed", t)
        }
    }

    // ------------------------------------------------------------------------------------
    // 输入：NV21 -> 编码器 input buffer
    // ------------------------------------------------------------------------------------

    private fun fillInputBuffer(
        codec: MediaCodec,
        index: Int,
        inputBuffer: ByteBuffer,
        nv21: ByteArray
    ): Boolean {
        if (nv21.size < expectedNv21Size) return false
        return if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            val image = try {
                codec.getInputImage(index)
            } catch (t: Throwable) {
                Log.w(TAG, "getInputImage failed: ${t.message}")
                null
            }
            if (image != null && fillImage(image, nv21)) {
                true
            } else {
                // 风险：回退为 NV12 内存布局（见类注释）。
                Log.w(TAG, "flexible input fallback to semi-planar buffer layout")
                fillSemiPlanar(inputBuffer, nv21)
            }
        } else {
            fillSemiPlanar(inputBuffer, nv21)
        }
    }

    /** NV21 -> NV12（Y 拷贝 + 色度 V/U 交换），写入 SemiPlanar input buffer。 */
    private fun fillSemiPlanar(inputBuffer: ByteBuffer, nv21: ByteArray): Boolean {
        val ySize = mWidth * mHeight
        val expected = ySize * 3 / 2
        if (nv21.size < expected) return false
        try {
            inputBuffer.clear()
            inputBuffer.put(nv21, 0, ySize)
            var i = ySize
            val end = ySize + ySize / 2
            while (i < end) {
                val v = nv21[i]
                val u = nv21[i + 1]
                inputBuffer.put(u) // NV12: U 在前
                inputBuffer.put(v) // V 在后
                i += 2
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "fillSemiPlanar failed", t)
            return false
        }
    }

    /** COLOR_FormatYUV420Flexible：按 plane 填充（兼容 I420 与 semi-planar 两种常见布局）。 */
    private fun fillImage(image: android.media.Image, nv21: ByteArray): Boolean {
        val planes = image.planes
        if (planes.isEmpty()) return false
        val ySize = mWidth * mHeight
        if (nv21.size < ySize + ySize / 2) return false
        try {
            // ---- Y plane ----
            val yPlane = planes[0]
            val yBuf = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            var srcPos = 0
            for (row in 0 until mHeight) {
                val rowBase = row * yRowStride
                if (yPixelStride == 1) {
                    for (col in 0 until mWidth) {
                        yBuf.put(rowBase + col, nv21[srcPos + col])
                    }
                } else {
                    for (col in 0 until mWidth) {
                        yBuf.put(rowBase + col * yPixelStride, nv21[srcPos + col])
                    }
                }
                srcPos += mWidth
            }

            // ---- Chroma ----
            val chromaWidth = mWidth / 2
            val chromaHeight = mHeight / 2
            if (planes.size >= 3 && planes[1].pixelStride == 1 && planes[2].pixelStride == 1) {
                // I420: plane[1]=U, plane[2]=V 各自独立、像素连续
                val uPlane = planes[1]
                val vPlane = planes[2]
                val uBuf = uPlane.buffer
                val vBuf = vPlane.buffer
                for (row in 0 until chromaHeight) {
                    val srcRow = ySize + row * mWidth
                    val uRow = row * uPlane.rowStride
                    val vRow = row * vPlane.rowStride
                    for (col in 0 until chromaWidth) {
                        val v = nv21[srcRow + col * 2]
                        val u = nv21[srcRow + col * 2 + 1]
                        uBuf.put(uRow + col * uPlane.pixelStride, u)
                        vBuf.put(vRow + col * vPlane.pixelStride, v)
                    }
                }
                return true
            }
            if (planes.size >= 2) {
                val uvPlane = planes[1]
                if (uvPlane.pixelStride == 2) {
                    // Semi-planar：plane[1] 是交错的 UV
                    val uvBuf = uvPlane.buffer
                    for (row in 0 until chromaHeight) {
                        val srcRow = ySize + row * mWidth
                        val dstRow = row * uvPlane.rowStride
                        for (col in 0 until chromaWidth) {
                            val v = nv21[srcRow + col * 2]
                            val u = nv21[srcRow + col * 2 + 1]
                            val dst = dstRow + col * 2
                            uvBuf.put(dst, u)
                            uvBuf.put(dst + 1, v)
                        }
                    }
                    return true
                }
                if (uvPlane.pixelStride == 1 && uvPlane.rowStride >= mWidth) {
                    // 半平面但 plane[1] 覆盖整行 interleaved UV 的另一种表示
                    val uvBuf = uvPlane.buffer
                    for (row in 0 until chromaHeight) {
                        val srcRow = ySize + row * mWidth
                        val dstRow = row * uvPlane.rowStride
                        for (col in 0 until chromaWidth) {
                            val v = nv21[srcRow + col * 2]
                            val u = nv21[srcRow + col * 2 + 1]
                            uvBuf.put(dstRow + col * 2, u)
                            uvBuf.put(dstRow + col * 2 + 1, v)
                        }
                    }
                    return true
                }
            }
            Log.w(TAG, "unsupported Image plane layout: ${planes.size} planes")
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "fillImage failed: ${t.message}")
            return false
        }
    }

    // ------------------------------------------------------------------------------------
    // 输出：Annex-B
    // ------------------------------------------------------------------------------------

    private fun drainOutput(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                handleOutputFormat(codec.outputFormat)
                continue
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (outIndex < 0) continue
            try {
                val outBuffer = codec.getOutputBuffer(outIndex)
                val size = bufferInfo.size
                val flags = bufferInfo.flags
                if (outBuffer != null && size > 0) {
                    outBuffer.position(bufferInfo.offset)
                    outBuffer.limit(bufferInfo.offset + size)
                    val raw = ByteArray(size)
                    outBuffer.get(raw)
                    if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // 少数编码器把 SPS/PPS 作为 codec-config output buffer 给出。
                        captureCsd(raw)
                    } else {
                        emitFrame(raw, bufferInfo.presentationTimeUs, flags)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "drain output failed", t)
            } finally {
                try {
                    codec.releaseOutputBuffer(outIndex, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "releaseOutputBuffer failed", t)
                }
            }
        }
    }

    private fun handleOutputFormat(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0")?.let { copyBuffer(it) }
        val csd1 = format.getByteBuffer("csd-1")?.let { copyBuffer(it) }
        if (csd0 == null && csd1 == null) {
            Log.w(TAG, "output format changed but no csd-0/csd-1 (SPS/PPS missing?)")
            return
        }
        val baos = ByteArrayOutputStream()
        if (csd0 != null) baos.write(AnnexBUtil.normalize(csd0))
        if (csd1 != null) baos.write(AnnexBUtil.normalize(csd1))
        val csd = baos.toByteArray()
        if (csd.isNotEmpty()) {
            csdAnnexB = csd
            Log.i(TAG, "SPS/PPS captured: ${csd.size} bytes")
        }
    }

    private fun captureCsd(raw: ByteArray) {
        val normalized = AnnexBUtil.normalize(raw)
        if (normalized.isEmpty()) return
        val old = csdAnnexB
        csdAnnexB = if (old == null) normalized else old + normalized
        Log.i(TAG, "SPS/PPS captured from codec-config buffer: ${normalized.size} bytes")
    }

    private fun emitFrame(raw: ByteArray, ptsUs: Long, flags: Int) {
        var data = AnnexBUtil.normalize(raw)
        if (data.isEmpty()) return
        val isKeyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (isKeyFrame) {
            val csd = csdAnnexB
            // 关键帧补 SPS/PPS（若编码器自己没带）；RtmpPublisher 靠它发 sequence header。
            if (csd != null && AnnexBUtil.firstNalType(data) != AnnexBUtil.NAL_SPS) {
                data = csd + data
            }
        }
        try {
            listener.onEncoded(data, ptsUs)
        } catch (t: Throwable) {
            Log.w(TAG, "listener.onEncoded threw", t)
        }
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray {
        val dup = buffer.duplicate()
        dup.rewind()
        val size = dup.remaining()
        val out = ByteArray(size)
        dup.get(out)
        return out
    }

    private fun reportError(message: String) {
        Log.e(TAG, message)
        try {
            listener.onError(message)
        } catch (t: Throwable) {
            Log.w(TAG, "listener.onError threw", t)
        }
    }

    private data class Frame(val nv21: ByteArray, val ptsUs: Long)

    private companion object {
        const val TAG = "H264Encoder"
        const val QUEUE_CAPACITY = 3
        const val POLL_TIMEOUT_MS = 50L
        const val INPUT_TIMEOUT_US = 10_000L
        const val STOP_JOIN_TIMEOUT_MS = 1_000L
        const val I_FRAME_INTERVAL_SECONDS = 2
        const val DEFAULT_FPS = 30
        const val DEFAULT_BITRATE = 3_000_000

        fun normalizeDimension(value: Int): Int {
            var v = value
            if (v <= 0) v = 2
            if ((v and 1) != 0) v -= 1
            return v.coerceAtLeast(2)
        }
    }
}

/**
 * Annex-B 工具：把 MediaCodec 输出（通常是 Annex-B，个别机型是 AVCC）统一成
 * 4 字节起始码的 Annex-B；用于给关键帧补 SPS/PPS。
 */
internal object AnnexBUtil {

    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** 已存在 3/4 字节起始码则规范化为 4 字节；否则按 AVCC（4 字节长度前缀）转换。 */
    fun normalize(data: ByteArray): ByteArray {
        try {
            val nals = findNalRanges(data)
            if (nals.isNotEmpty()) {
                val out = ByteArrayOutputStream(data.size + 16)
                for (range in nals) {
                    val end = trimTrailingZeros(data, range[0], range[1])
                    if (end <= range[0]) continue
                    writeStartCode(out)
                    out.write(data, range[0], end - range[0])
                }
                return if (out.size() > 0) out.toByteArray() else data
            }
            // 尝试 AVCC：4 字节大端长度 + NAL 数据
            val out = ByteArrayOutputStream(data.size + 16)
            var i = 0
            var count = 0
            while (i + 4 <= data.size) {
                val len = ((data[i].toInt() and 0xFF) shl 24) or
                    ((data[i + 1].toInt() and 0xFF) shl 16) or
                    ((data[i + 2].toInt() and 0xFF) shl 8) or
                    (data[i + 3].toInt() and 0xFF)
                if (len <= 0 || i + 4 + len > data.size) break
                writeStartCode(out)
                out.write(data, i + 4, len)
                i += 4 + len
                count++
            }
            return if (count > 0) out.toByteArray() else data
        } catch (t: Throwable) {
            Log.w("AnnexBUtil", "normalize failed", t)
            return data
        }
    }

    fun firstNalType(data: ByteArray): Int {
        return try {
            val nals = findNalRanges(data)
            if (nals.isEmpty()) -1 else data[nals[0][0]].toInt() and 0x1F
        } catch (t: Throwable) {
            -1
        }
    }

    /** 返回每个 NAL 的 [start, endExclusive) 区间（不含起始码）。 */
    private fun findNalRanges(data: ByteArray): List<IntArray> {
        val result = ArrayList<IntArray>()
        var start = -1
        var i = 0
        val n = data.size
        while (i + 2 < n) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    if (start >= 0) result.add(intArrayOf(start, i))
                    i += 3
                    start = i
                    continue
                }
                if (i + 3 < n && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    if (start >= 0) result.add(intArrayOf(start, i))
                    i += 4
                    start = i
                    continue
                }
            }
            i++
        }
        if (start >= 0 && start < n) result.add(intArrayOf(start, n))
        return result
    }

    /** Annex-B 允许 NAL 之间有 trailing_zero_8bits；去掉尾部 0，避免把填充当 NAL 数据。 */
    private fun trimTrailingZeros(data: ByteArray, start: Int, end: Int): Int {
        var e = end
        while (e > start && data[e - 1].toInt() == 0) e--
        return if (e == start) end else e
    }

    private fun writeStartCode(out: ByteArrayOutputStream) {
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(1)
    }
}
