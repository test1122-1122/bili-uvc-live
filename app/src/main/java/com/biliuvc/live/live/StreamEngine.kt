package com.biliuvc.live.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.SurfaceTexture
import com.biliuvc.live.capture.AacEncoder
import com.biliuvc.live.capture.H264Encoder
import com.biliuvc.live.capture.UvcController
import com.biliuvc.live.capture.UvcDeviceInfo
import com.biliuvc.live.data.StreamConfig
import com.biliuvc.live.rtmp.RtmpPublisher
import com.biliuvc.live.rtmp.RtmpState
import com.biliuvc.live.rtmp.RtmpStats
import com.biliuvc.live.rtmp.RtmpTargetParser
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the capture -> encode -> RTMP pipeline.
 *
 * All callbacks are delivered on the main thread. Camera frames are received on the USB
 * thread and are forwarded straight into the H.264 encoder queue without touching the UI.
 */
class StreamEngine(private val context: Context) {

    interface Listener {
        fun onCameraDevices(devices: List<UvcDeviceInfo>)
        fun onCameraOpened(size: Pair<Int, Int>, supportedSizes: List<Pair<Int, Int>>)
        fun onCameraDetached()
        fun onCameraError(message: String)
        fun onStreamState(state: RtmpState, detail: String?)
        fun onStats(snapshot: StreamSnapshot)
        fun onError(message: String)
    }

    data class StreamSnapshot(
        val connected: Boolean,
        val publishing: Boolean,
        val durationMs: Long,
        val videoFramesSent: Long,
        val audioFramesSent: Long,
        val droppedVideoFrames: Long,
        val droppedAudioFrames: Long,
        val bytesSent: Long,
        val bitrateBps: Long,
        val previewFps: Int,
        val lastError: String?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uvc = UvcController(context)

    /** Serialised, off-main-thread shutdown of encoders/camera/RTMP (their stop() joins threads). */
    private val cleanupExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "stream-cleanup").apply { isDaemon = true }
    }

    @Volatile
    private var listener: Listener? = null

    private var publisher: RtmpPublisher? = null
    private var videoEncoder: H264Encoder? = null
    private var audioEncoder: AacEncoder? = null

    @Volatile
    private var previewSurface: SurfaceTexture? = null

    @Volatile
    private var cameraOpened: Boolean = false

    @Volatile
    private var streaming: Boolean = false

    @Volatile
    private var started: Boolean = false

    @Volatile
    private var currentSize: Pair<Int, Int>? = null

    @Volatile
    private var supportedSizes: List<Pair<Int, Int>> = emptyList()

    private val frameCounter = java.util.concurrent.atomic.AtomicLong()
    private var lastFrameCount = 0L
    private var previewFps = 0
    private var statsTicker: Runnable? = null

    private val uvcListener = object : UvcController.Listener {
        override fun onDeviceAttached(devices: List<UvcDeviceInfo>) {
            mainHandler.post { listener?.onCameraDevices(devices) }
        }

        override fun onDeviceDetached(device: UvcDeviceInfo) {
            cameraOpened = false
            if (streaming) {
                mainHandler.post {
                    listener?.onCameraDetached()
                    listener?.onError("UVC 摄像头已断开，直播已停止")
                }
                stopStreaming()
            } else {
                mainHandler.post { listener?.onCameraDetached() }
            }
        }

        override fun onCameraOpened(sizes: List<Pair<Int, Int>>) {
            cameraOpened = true
            supportedSizes = sizes
            currentSize = uvc.currentPreviewSize ?: currentSize ?: sizes.firstOrNull()
            previewSurface?.let { uvc.attachPreview(it) }
            mainHandler.post {
                currentSize?.let { listener?.onCameraOpened(it, sizes) }
            }
        }

        override fun onCameraError(message: String) {
            cameraOpened = false
            if (streaming) {
                // Never keep an RTMP connection alive without a video source: viewers would see
                // a frozen/black stream and the Bilibili room would stay "live" with no content.
                stopStreaming()
                mainHandler.post { listener?.onError("摄像头错误，直播已停止: $message") }
            }
            mainHandler.post { listener?.onCameraError(message) }
        }

        override fun onFrame(frame: com.biliuvc.live.capture.UvcFrame) {
            frameCounter.incrementAndGet()
            val encoder = videoEncoder
            if (streaming && encoder != null) {
                encoder.encodeNv21Frame(frame.nv21, frame.timestampUs)
            }
        }
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    /** Registers the USB monitor and starts watching for UVC cameras. Idempotent. */
    fun start() {
        if (started) return
        started = true
        uvc.setListener(uvcListener)
        uvc.start()
    }

    fun isStreaming(): Boolean = streaming

    fun isCameraOpened(): Boolean = cameraOpened

    fun currentPreviewSize(): Pair<Int, Int>? = currentSize

    fun supportedSizes(): List<Pair<Int, Int>> = supportedSizes

    fun devices(): List<UvcDeviceInfo> = uvc.devices()

    /** Re-triggers the Android USB host permission dialog (after the user denied it once). */
    fun retryUsbPermission() = uvc.retryUsbPermission()

    /** Snapshot of the USB devices enumerated by Android (for the in-app diagnostics banner). */
    fun usbDeviceReport(): String = uvc.usbDeviceReport()

    /**
     * Recovers from the USB attach / app-chooser flow: Android can grant access when the user
     * picks this app for the USB device, so re-enumerate and open the camera (or ask again).
     */
    fun refreshUsbState(requestPermissionIfNeeded: Boolean) =
        uvc.refreshDevicesAndOpen(requestPermissionIfNeeded)

    fun setPreviewSurface(surface: SurfaceTexture?) {
        previewSurface = surface
        if (surface != null) {
            uvc.attachPreview(surface)
        } else {
            uvc.detachPreview()
        }
    }

    /**
     * Applies size + frame rate from the settings. Re-opens the camera when not streaming so the
     * new values take effect immediately.
     */
    fun applyStreamConfig(config: StreamConfig) {
        if (streaming) return
        uvc.setFrameRate(config.fps)
        uvc.setPreviewSize(config.width, config.height)
        supportedSizeOrNull(config.width, config.height)?.let {
            uvc.reconfigure()
        }
    }

    /** Changes the camera resolution. Only allowed while not streaming. */
    fun setPreviewSize(width: Int, height: Int): Boolean {
        if (streaming) return false
        if (currentSize == previewSizeOf(width, height) && cameraOpened) return true
        supportedSizeOrNull(width, height) ?: return false
        uvc.setPreviewSize(width, height)
        if (cameraOpened) {
            // Re-open the camera so the new frame size takes effect. USB permission is kept by Android.
            uvc.stop()
            cameraOpened = false
            mainHandler.postDelayed({
                if (started) {
                    uvc.start()
                    // UvcController caches the pending SurfaceTexture, so the preview restarts by itself.
                }
            }, 250)
        }
        return true
    }

    /**
     * Starts publishing. [rtmpAddr] and [rtmpCode] are the values returned by Bilibili startLive.
     *
     * @return false when the request is rejected synchronously (already streaming, no camera...).
     */
    fun startStreaming(rtmpAddr: String, rtmpCode: String, config: StreamConfig): Boolean {
        if (streaming) return false
        if (!cameraOpened) {
            listener?.onError("摄像头未就绪，无法开始推流")
            return false
        }
        val size = currentSize ?: run {
            listener?.onError("无法获取摄像头分辨率")
            return false
        }
        val target = RtmpTargetParser.fromParts(rtmpAddr, rtmpCode) ?: run {
            listener?.onError("推流地址无效: $rtmpAddr$rtmpCode")
            return false
        }
        val width = size.first - size.first % 2
        val height = size.second - size.second % 2

        val enc = H264Encoder(
            width = width,
            height = height,
            fps = config.fps,
            bitrateBps = config.videoBitrateBps,
            listener = object : H264Encoder.Listener {
                override fun onEncoded(annexB: ByteArray, ptsUs: Long) {
                    publisher?.sendVideoFrame(annexB, ptsUs)
                }

                override fun onError(message: String) {
                    mainHandler.post { listener?.onError("视频编码失败: $message") }
                }
            }
        )
        val pub = RtmpPublisher(object : RtmpPublisher.Listener {
            override fun onState(state: RtmpState, detail: String?) {
                // Force an IDR as soon as the server accepts the publish command so that
                // viewers see the first frame immediately instead of waiting for the 2s GOP.
                if (state == RtmpState.PUBLISHING) {
                    videoEncoder?.requestKeyFrame()
                }
                mainHandler.post { listener?.onStreamState(state, detail) }
            }

            override fun onStats(stats: RtmpStats) {
                // UI polls via the 1s ticker; nothing to do here.
            }

            override fun onError(message: String, cause: Throwable?) {
                mainHandler.post { listener?.onStreamState(RtmpState.ERROR, message) }
            }
        })
        pub.setVideoInfo(width, height, config.fps)
        pub.setAudioInfo(config.audioSampleRate, config.audioChannels)

        val aac = if (config.audioEnabled) {
            AacEncoder(
                sampleRate = config.audioSampleRate,
                channels = config.audioChannels,
                bitrateBps = config.audioBitrateBps,
                listener = object : AacEncoder.Listener {
                    override fun onConfig(asc: ByteArray) {
                        pub.setAudioSpecificConfig(asc)
                    }

                    override fun onEncoded(rawAac: ByteArray, ptsUs: Long) {
                        pub.sendAudioFrame(rawAac, ptsUs)
                    }

                    override fun onError(message: String) {
                        mainHandler.post { listener?.onError("音频编码失败: $message") }
                    }
                }
            )
        } else {
            null
        }

        publisher = pub
        videoEncoder = enc
        audioEncoder = aac
        streaming = true
        enc.start()
        aac?.start()
        pub.connect(target.originalUrl)
        startStatsTicker()
        return true
    }

    /** Disconnects RTMP, stops encoders. Idempotent. */
    fun stopStreaming() {
        if (!streaming && publisher == null) return
        streaming = false
        stopStatsTicker()
        val oldPublisher = publisher
        val oldVideo = videoEncoder
        val oldAudio = audioEncoder
        publisher = null
        videoEncoder = null
        audioEncoder = null
        mainHandler.post { listener?.onStreamState(RtmpState.STOPPED, null) }
        // Encoder/publisher stop() joins internal threads; run it off the main thread to avoid ANRs.
        cleanupExecutor.execute {
            runCatching { oldAudio?.stop() }
            runCatching { oldVideo?.stop() }
            runCatching { oldPublisher?.disconnect() }
        }
    }

    /** Stops everything (camera + stream). */
    fun release() {
        stopStreaming()
        started = false
        previewSurface = null
        cameraOpened = false
        cleanupExecutor.execute { runCatching { uvc.stop() } }
    }

    private fun startStatsTicker() {
        stopStatsTicker()
        lastFrameCount = frameCounter.get()
        val ticker = object : Runnable {
            override fun run() {
                val pub = publisher ?: return
                val stats = pub.stats
                val count = frameCounter.get()
                val delta = (count - lastFrameCount).coerceAtLeast(0)
                previewFps = delta.toInt()
                lastFrameCount = count
                val snapshot = StreamSnapshot(
                    connected = stats.state == RtmpState.CONNECTED || stats.publishing,
                    publishing = stats.publishing,
                    durationMs = stats.durationMs,
                    videoFramesSent = stats.videoFramesSent,
                    audioFramesSent = stats.audioFramesSent,
                    droppedVideoFrames = stats.droppedVideoFrames + (videoEncoder?.droppedFrames ?: 0L),
                    droppedAudioFrames = stats.droppedAudioFrames,
                    bytesSent = stats.bytesSent,
                    bitrateBps = stats.bitrateBps,
                    previewFps = previewFps,
                    lastError = stats.lastError
                )
                listener?.onStats(snapshot)
                if (streaming) mainHandler.postDelayed(this, 1000L)
            }
        }
        statsTicker = ticker
        mainHandler.postDelayed(ticker, 1000L)
    }

    private fun stopStatsTicker() {
        statsTicker?.let { mainHandler.removeCallbacks(it) }
        statsTicker = null
    }

    private fun previewSizeOf(width: Int, height: Int): Pair<Int, Int> = width to height

    private fun supportedSizeOrNull(width: Int, height: Int): Pair<Int, Int>? {
        val sizes = supportedSizes
        if (sizes.isEmpty()) return previewSizeOf(width, height)
        return sizes.firstOrNull { it.first == width && it.second == height }
    }
}
