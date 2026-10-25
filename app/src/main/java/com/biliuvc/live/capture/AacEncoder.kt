package com.biliuvc.live.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AAC-LC 硬编码器：手机麦克风（AudioRecord）-> MediaCodec(audio/mp4a-latm) -> raw AAC。
 *
 * ## 线程模型
 * - [start] / [stop] 可在任意线程调用；`AudioRecord` 与 `MediaCodec` 的创建、读写、释放
 *   全部在独立工作线程 `AacEncoder` 中完成。
 * - [Listener.onConfig] / [Listener.onEncoded] / [Listener.onError] 回调在该工作线程。
 *
 * ## 数据约定
 * - [Listener.onConfig] 给出 MediaCodec codec-config（`csd-0`，即 AudioSpecificConfig）。
 * - [Listener.onEncoded] 给出**无 ADTS 头**的 raw AAC 帧 + 单调递增 PTS（µs）。
 *   PTS = 开始录音时的 `System.nanoTime()/1000` 基准 + 累计采样字节换算，与 UVC 视频帧
 *   （同样是 nanoTime 基准）一致，RtmpPublisher 用「首帧 PTS 作为公共 base」rebase 时音视频
 *   才能保持同一时间轴；若音频从 0 起算会与视频差出一个开机时长，导致音频时间戳被钳到 0。
 * - [stop] 幂等；stop 后可再次 start（会重新创建 AudioRecord/MediaCodec）。
 *
 * ## 无真机验证点 / 已知风险
 * 1. 手机麦克风不是 USB 摄像头音频（架构已知限制）。
 * 2. `RECORD_AUDIO` 权限：未授权时构造/启动 AudioRecord 会失败，这里转成 onError，不崩溃。
 * 3. 部分设备的 AAC 编码器要求固定 input buffer 大小 / 固定帧长（1024 samples）；
 *    这里按任意 PCM 块喂入，依赖编码器内部缓冲，需真机验证 PTS 与帧连续性。
 * 4. start 时有约一个 read buffer 的启动延迟；PTS 基准在开始录音时采样。
 */
// INFO_OUTPUT_BUFFERS_CHANGED 在 SDK 中已 deprecated，但为了兼容旧编码器仍需处理该返回码。
@Suppress("DEPRECATION")
class AacEncoder(
    private val sampleRate: Int = 44_100,
    private val channels: Int = 2,
    private val bitrateBps: Int = 128_000,
    private val listener: Listener
) {

    interface Listener {
        /** MediaCodec codec-config (AudioSpecificConfig)。 */
        fun onConfig(asc: ByteArray)

        /** raw AAC（无 ADTS 头）；回调在音频线程。 */
        fun onEncoded(rawAac: ByteArray, ptsUs: Long)

        /** 出错（已捕获异常，不崩溃）；回调在音频线程。 */
        fun onError(message: String)
    }

    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    /** 供 stop() 从其他线程打断阻塞中的 read。 */
    @Volatile
    private var audioRecord: AudioRecord? = null

    private val channelCount: Int = if (channels <= 1) 1 else 2
    private val inputChannelMask: Int =
        if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val bytesPerFrame: Int = channelCount * 2
    private val bytesPerSecond: Int = sampleRate * bytesPerFrame

    fun start() {
        synchronized(lifecycleLock) {
            if (running.get()) {
                Log.d(TAG, "start(): already started")
                return
            }
            if (sampleRate <= 0) {
                reportError("AAC 采样率非法: $sampleRate")
                return
            }
            running.set(true)
            val thread = Thread({ runAudioLoop() }, "AacEncoder")
            worker = thread
            thread.start()
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
        // 打断阻塞的 AudioRecord.read()
        try {
            audioRecord?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "audioRecord.stop failed", t)
        }
        if (thread != null) {
            thread.interrupt()
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(STOP_JOIN_TIMEOUT_MS)
                    if (thread.isAlive) Log.w(TAG, "audio thread did not exit in time")
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // 音频线程
    // ------------------------------------------------------------------------------------

    private fun runAudioLoop() {
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        var totalBytes = 0L
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate, inputChannelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                throw IllegalStateException("AudioRecord 不支持当前参数 (minBuffer=$minBufferSize)")
            }
            val recordBufferSize = minBufferSize * 2 // 契约：minBuffer * 2
            val pcm = ByteArray(minBufferSize.coerceIn(1024, 8192))

            record = createAudioRecord(recordBufferSize)
            audioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord 初始化失败（检查 RECORD_AUDIO 权限/麦克风占用）")
            }

            codec = createAudioCodec()
            codec.start()
            if (!running.get()) return
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord 无法开始录音（RECORD_AUDIO 权限？麦克风被占用？）")
            }
            Log.i(
                TAG,
                "AAC encoder started: ${sampleRate}Hz ${channelCount}ch ${bitrateBps}bps " +
                    "recordBuffer=$recordBufferSize"
            )
            // 与 UVC 视频帧使用同一个单调时钟基准（nanoTime），便于 RtmpPublisher 统一 rebase。
            val basePtsUs = System.nanoTime() / 1000L

            val bufferInfo = MediaCodec.BufferInfo()
            while (running.get()) {
                val read = try {
                    record.read(pcm, 0, pcm.size)
                } catch (t: Throwable) {
                    Log.e(TAG, "AudioRecord.read failed", t)
                    if (running.get()) {
                        reportError("麦克风读取失败: ${t.message ?: t.javaClass.simpleName}")
                    }
                    break
                }
                if (read < 0) {
                    // ERROR_INVALID_OPERATION 常见于 stop() 打断；运行中出错则退出。
                    Log.e(TAG, "AudioRecord.read error=$read")
                    if (running.get()) {
                        reportError("麦克风读取失败 (AudioRecord error=$read)")
                    }
                    break
                }
                if (read == 0) continue

                val chunkStartPtsUs = basePtsUs + totalBytes * 1_000_000L / bytesPerSecond
                totalBytes += read

                // 把这块 PCM 喂给编码器；input buffer 不足时阻塞等待（音频不能随便丢，会破坏 PTS）。
                var offset = 0
                while (offset < read && running.get()) {
                    val inIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (inIndex < 0) {
                        Log.w(TAG, "no AAC input buffer available, drop ${read - offset} bytes")
                        break
                    }
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer == null) {
                        codec.queueInputBuffer(inIndex, 0, 0, chunkStartPtsUs, 0)
                        break
                    }
                    inputBuffer.clear()
                    val chunk = minOf(inputBuffer.remaining(), read - offset)
                    inputBuffer.put(pcm, offset, chunk)
                    val ptsUs = chunkStartPtsUs + offset * 1_000_000L / bytesPerSecond
                    codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                    offset += chunk
                }

                drainOutput(codec, bufferInfo)
            }
            // 尽量把剩余编码帧吐完
            drainOutput(codec, bufferInfo)
        } catch (t: Throwable) {
            if (running.get()) {
                Log.e(TAG, "AAC encoder failed", t)
                reportError("AAC 编码器异常: ${t.message ?: t.javaClass.simpleName}")
            } else {
                Log.i(TAG, "AAC encoder exit: ${t.message}")
            }
        } finally {
            running.set(false)
            audioRecord = null
            try {
                if (record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "record.stop failed", t)
            }
            try {
                record?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "record.release failed", t)
            }
            try {
                codec?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "aac codec.stop failed", t)
            }
            try {
                codec?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "aac codec.release failed", t)
            }
        }
    }

    // The caller (MainActivity) requests RECORD_AUDIO before starting streaming and runAudioLoop()
    // wraps this in try/catch, reporting a SecurityException through onError. The annotation keeps
    // Android Lint from treating the builder as an unchecked permission call.
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSize: Int): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(inputChannelMask)
            .build()
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun createAudioCodec(): MediaCodec {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val codec = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, if (bitrateBps > 0) bitrateBps else DEFAULT_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

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
                        // 少数编码器把 ASC 作为 codec-config output buffer 给出。
                        emitConfig(raw)
                    } else {
                        emitEncoded(raw, bufferInfo.presentationTimeUs)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "aac drain output failed", t)
            } finally {
                try {
                    codec.releaseOutputBuffer(outIndex, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "aac releaseOutputBuffer failed", t)
                }
            }
        }
    }

    private fun handleOutputFormat(format: MediaFormat) {
        val csd = try {
            format.getByteBuffer("csd-0")?.let { copyBuffer(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "read csd-0 failed", t)
            null
        }
        if (csd != null) {
            emitConfig(csd)
        } else {
            Log.w(TAG, "output format changed but csd-0 missing")
        }
    }

    private fun emitConfig(asc: ByteArray) {
        if (asc.isEmpty()) return
        Log.i(TAG, "AudioSpecificConfig captured: ${asc.size} bytes")
        try {
            listener.onConfig(asc)
        } catch (t: Throwable) {
            Log.w(TAG, "listener.onConfig threw", t)
        }
    }

    private fun emitEncoded(rawAac: ByteArray, ptsUs: Long) {
        if (rawAac.isEmpty()) return
        try {
            listener.onEncoded(rawAac, ptsUs)
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

    private companion object {
        const val TAG = "AacEncoder"
        const val INPUT_TIMEOUT_US = 10_000L
        const val STOP_JOIN_TIMEOUT_MS = 1_000L
        const val DEFAULT_BITRATE = 128_000
    }
}
