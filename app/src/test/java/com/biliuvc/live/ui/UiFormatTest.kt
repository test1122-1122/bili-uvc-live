package com.biliuvc.live.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormatTest {

    @Test
    fun formatsDuration() {
        assertEquals("00:00", UiFormat.duration(0))
        assertEquals("00:05", UiFormat.duration(5_000))
        assertEquals("01:01", UiFormat.duration(61_000))
        assertEquals("1:00:00", UiFormat.duration(3_600_000))
        assertEquals("00:00", UiFormat.duration(-5))
    }

    @Test
    fun formatsBitrate() {
        assertEquals("3.5 Mbps", UiFormat.bitrate(3_500_000))
        assertEquals("128 Kbps", UiFormat.bitrate(128_000))
    }

    @Test
    fun formatsByteSize() {
        assertEquals("512 KB", UiFormat.size(512 * 1024))
        assertEquals("1.0 MB", UiFormat.size(1024 * 1024))
        assertEquals("2.00 GB", UiFormat.size(2L * 1024 * 1024 * 1024))
    }
}
