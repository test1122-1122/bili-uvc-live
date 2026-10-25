package com.biliuvc.live.data

/**
 * Shared streaming configuration used by the capture, UI and orchestration layers.
 *
 * Keep this class dependency-free (pure Kotlin) so it can be unit-tested on the JVM.
 */
data class StreamConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val videoBitrateBps: Int = 3_500_000,
    val audioEnabled: Boolean = true,
    val audioSampleRate: Int = 44_100,
    val audioChannels: Int = 2,
    val audioBitrateBps: Int = 128_000
) {
    init {
        require(width > 0 && height > 0) { "invalid video size ${width}x$height" }
        require(fps in 1..60) { "invalid fps $fps" }
        require(videoBitrateBps in 200_000..20_000_000) { "invalid video bitrate $videoBitrateBps" }
    }

    val isLandscape: Boolean get() = width >= height

    fun normalized(): StreamConfig {
        val w = width - width % 2
        val h = height - height % 2
        return copy(width = w, height = h)
    }

    companion object {
        val DEFAULT = StreamConfig()

        /** Rough H.264 bitrate guidance for a live stream, in bits/s. */
        fun suggestVideoBitrate(width: Int, height: Int, fps: Int): Int {
            val pixels = width.toLong() * height
            val base = when {
                pixels >= 1920L * 1080 -> 6_000_000
                pixels >= 1280L * 720 -> 3_500_000
                pixels >= 960L * 540 -> 2_200_000
                pixels >= 640L * 480 -> 1_200_000
                else -> 800_000
            }
            val fpsScale = (fps.coerceIn(15, 60) / 30.0)
            return (base * fpsScale).toInt().coerceIn(400_000, 12_000_000)
        }
    }
}
