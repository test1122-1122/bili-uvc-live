package com.biliuvc.live.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigTest {

    @Test
    fun defaultConfigIsLandscape720p30() {
        val config = StreamConfig()
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(30, config.fps)
        assertTrue(config.isLandscape)
        assertTrue(config.videoBitrateBps in 1_000_000..6_000_000)
    }

    @Test
    fun normalizedMakesDimensionsEven() {
        assertEquals(1280 to 720, StreamConfig(width = 1281, height = 721).normalized().let {
            it.width to it.height
        })
    }

    @Test
    fun bitrateSuggestionScalesWithResolutionAndFps() {
        val low = StreamConfig.suggestVideoBitrate(640, 480, 30)
        val mid = StreamConfig.suggestVideoBitrate(1280, 720, 30)
        val high = StreamConfig.suggestVideoBitrate(1920, 1080, 30)
        assertTrue(low < mid)
        assertTrue(mid < high)
        assertTrue(StreamConfig.suggestVideoBitrate(1280, 720, 15) <
            StreamConfig.suggestVideoBitrate(1280, 720, 30))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidFps() {
        StreamConfig(fps = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSize() {
        StreamConfig(width = 0)
    }
}
