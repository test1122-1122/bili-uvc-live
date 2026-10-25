package com.biliuvc.live.ui

import java.util.Locale

object UiFormat {

    fun duration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    fun bitrate(bps: Long): String {
        return if (bps >= 1_000_000) {
            String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        } else {
            String.format(Locale.US, "%d Kbps", bps / 1000)
        }
    }

    fun size(bytes: Long): String {
        return when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            else -> String.format(Locale.US, "%d KB", bytes / 1024)
        }
    }
}
