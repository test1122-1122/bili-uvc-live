package com.biliuvc.live.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent user settings (stream configuration only).
 *
 * Bilibili credentials are stored separately by [com.biliuvc.live.bili.SessionStore].
 */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var streamConfig: StreamConfig
        get() = StreamConfig(
            width = prefs.getInt(KEY_WIDTH, StreamConfig.DEFAULT.width),
            height = prefs.getInt(KEY_HEIGHT, StreamConfig.DEFAULT.height),
            fps = prefs.getInt(KEY_FPS, StreamConfig.DEFAULT.fps),
            videoBitrateBps = prefs.getInt(KEY_VIDEO_BITRATE, StreamConfig.DEFAULT.videoBitrateBps),
            audioEnabled = prefs.getBoolean(KEY_AUDIO_ENABLED, true),
            audioSampleRate = StreamConfig.DEFAULT.audioSampleRate,
            audioChannels = StreamConfig.DEFAULT.audioChannels,
            audioBitrateBps = prefs.getInt(KEY_AUDIO_BITRATE, StreamConfig.DEFAULT.audioBitrateBps)
        )
        set(value) {
            prefs.edit()
                .putInt(KEY_WIDTH, value.width)
                .putInt(KEY_HEIGHT, value.height)
                .putInt(KEY_FPS, value.fps)
                .putInt(KEY_VIDEO_BITRATE, value.videoBitrateBps)
                .putBoolean(KEY_AUDIO_ENABLED, value.audioEnabled)
                .putInt(KEY_AUDIO_BITRATE, value.audioBitrateBps)
                .apply()
        }

    companion object {
        private const val PREFS_NAME = "biliuvc_settings"
        private const val KEY_WIDTH = "video_width"
        private const val KEY_HEIGHT = "video_height"
        private const val KEY_FPS = "video_fps"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
    }
}
