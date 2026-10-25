package com.biliuvc.live.rtmp

/**
 * AAC helpers (ISO/IEC 14496-3 / ADTS).
 *
 * The AAC encoder hands over raw AAC frames (no ADTS header). The FLV muxer needs an
 * AudioSpecificConfig as the sequence header; when the encoder does not provide one it can be
 * derived from the sample rate and channel count (AAC LC, as produced by MediaCodec).
 */
object AacConfig {

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350
    )

    private const val AUDIO_OBJECT_TYPE_AAC_LC = 2

    /** FLV audio tag first byte (AAC 44.1 kHz stereo -> 0xAF). */
    fun audioTagByte(sampleRate: Int, channels: Int): Byte {
        val soundRate = when (sampleRate) {
            44100, 48000, 32000 -> 3 // 44.1 kHz (48/32 kHz share the marker in FLV)
            22050, 24000 -> 2
            11025, 12000 -> 1
            8000, 5512, 5500 -> 0
            else -> 3
        }
        val soundType = if (channels > 1) 1 else 0
        val value = (0x0A shl 4) or (soundRate shl 2) or (1 shl 1) or soundType
        return value.toByte()
    }

    /**
     * Builds a 2-byte AudioSpecificConfig for AAC LC at [sampleRate] with [channels] channels.
     * 44100 Hz stereo -> `12 10`.
     */
    fun buildAudioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
        val frequencyIndex = SAMPLE_RATES.indexOf(sampleRate).let { if (it < 0) 4 else it }
        val channelConfig = channels.coerceIn(1, 7)
        val bits = (AUDIO_OBJECT_TYPE_AAC_LC shl 11) or (frequencyIndex shl 7) or (channelConfig shl 3)
        return byteArrayOf(((bits ushr 8) and 0xFF).toByte(), (bits and 0xFF).toByte())
    }

    /** True when [data] begins with an ADTS sync word (`0xFF 0xF?`). */
    fun hasAdtsHeader(data: ByteArray): Boolean {
        if (data.size < 7) return false
        val first = data[0].toInt() and 0xFF
        val second = data[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xF0) == 0xF0
    }

    /**
     * Removes an ADTS header when present (7 bytes, or 9 bytes when CRC protection is enabled).
     * Raw AAC frames are returned unchanged. Never throws.
     */
    fun stripAdtsHeader(data: ByteArray): ByteArray {
        if (!hasAdtsHeader(data)) return data
        val protectionAbsent = (data[1].toInt() ushr 0) and 0x01
        val headerSize = if (protectionAbsent == 1) 7 else 9
        if (data.size <= headerSize) return ByteArray(0)
        return data.copyOfRange(headerSize, data.size)
    }
}
