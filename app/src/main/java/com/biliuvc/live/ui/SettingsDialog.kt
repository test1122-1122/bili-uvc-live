package com.biliuvc.live.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.widget.ArrayAdapter
import com.biliuvc.live.R
import com.biliuvc.live.data.StreamConfig
import com.biliuvc.live.databinding.DialogSettingsBinding

/** Stream settings dialog. Resolution choices come from the UVC camera when available. */
class SettingsDialog(
    private val activity: Activity,
    private val config: StreamConfig,
    availableSizes: List<Pair<Int, Int>>,
    private val isStreaming: Boolean,
    private val onSave: (StreamConfig) -> Unit
) {
    private val binding = DialogSettingsBinding.inflate(activity.layoutInflater)
    private val dialog = Dialog(activity).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(true)
    }

    private val sizes: List<Pair<Int, Int>> = buildList {
        val base = availableSizes.ifEmpty {
            listOf(
                1920 to 1080, 1280 to 720, 960 to 540, 640 to 480
            )
        }
        addAll(base.distinct().sortedWith(compareByDescending<Pair<Int, Int>> { it.first * it.second }))
        val current = config.width to config.height
        if (none { it == current }) add(0, current)
    }

    // 60fps is supported by most 720p UVC capture cards; the old 30fps limit was our choice.
    private val fpsOptions = listOf(60, 50, 30, 25, 20, 15)
    private val bitrateOptions = listOf(
        12_000_000, 10_000_000, 8_000_000, 6_000_000, 5_000_000, 3_500_000, 2_500_000, 1_500_000
    )

    init {
        binding.spinnerResolution.adapter = spinnerAdapter(sizes.map { "${it.first} × ${it.second}" })
        binding.spinnerFps.adapter = spinnerAdapter(fpsOptions.map { "$it fps" })
        binding.spinnerBitrate.adapter = spinnerAdapter(bitrateOptions.map { UiFormat.bitrate(it.toLong()) })

        val sizeIndex = sizes.indexOfFirst { it.first == config.width && it.second == config.height }
            .takeIf { it >= 0 }
            ?: sizes.indexOfFirst { it.first == 1280 && it.second == 720 }.takeIf { it >= 0 }
            ?: sizes.indexOfFirst { it.first * it.second <= 1920 * 1080 }.takeIf { it >= 0 }
            ?: 0
        binding.spinnerResolution.setSelection(sizeIndex)
        val fpsIndex = fpsOptions.indexOf(config.fps).takeIf { it >= 0 } ?: 0
        binding.spinnerFps.setSelection(fpsIndex)
        val brIndex = bitrateOptions.indexOfFirst { it == config.videoBitrateBps }
            .takeIf { it >= 0 } ?: bitrateOptions.indexOfFirst { it <= config.videoBitrateBps }.takeIf { it >= 0 } ?: 3
        binding.spinnerBitrate.setSelection(brIndex)
        binding.switchAudio.isChecked = config.audioEnabled

        if (isStreaming) {
            binding.spinnerResolution.isEnabled = false
            binding.spinnerFps.isEnabled = false
            binding.spinnerBitrate.isEnabled = false
            binding.tvLiveWarning.visibility = android.view.View.VISIBLE
        }

        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnSave.setOnClickListener {
            val size = sizes[binding.spinnerResolution.selectedItemPosition.coerceIn(0, sizes.lastIndex)]
            val fps = fpsOptions[binding.spinnerFps.selectedItemPosition.coerceIn(0, fpsOptions.lastIndex)]
            val bitrate = bitrateOptions[binding.spinnerBitrate.selectedItemPosition.coerceIn(0, bitrateOptions.lastIndex)]
            val updated = config.copy(
                width = size.first,
                height = size.second,
                fps = fps,
                videoBitrateBps = bitrate,
                audioEnabled = binding.switchAudio.isChecked
            )
            dialog.dismiss()
            onSave(updated)
        }
    }

    fun show() {
        if (activity.isFinishing || dialog.isShowing) return
        dialog.show()
        // Dialog windows wrap content by default; in portrait the fixed dp root width was ignored
        // and the settings content collapsed into a narrow column. Let the window control width.
        val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun spinnerAdapter(labels: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(activity, R.layout.item_spinner, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }
}
