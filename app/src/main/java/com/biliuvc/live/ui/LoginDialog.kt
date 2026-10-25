package com.biliuvc.live.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Window
import com.biliuvc.live.R
import com.biliuvc.live.bili.BiliApi
import com.biliuvc.live.bili.QrPollResult
import com.biliuvc.live.databinding.DialogQrBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Login dialog: fetches a Bilibili login QR code, renders it and polls the login state.
 * All network calls run on a private executor; UI updates happen on the main thread.
 */
class LoginDialog(
    private val activity: Activity,
    private val api: BiliApi,
    private val onSuccess: (cookies: String, uid: Long) -> Unit,
    private val onFailure: (message: String) -> Unit
) {
    private val binding = DialogQrBinding.inflate(activity.layoutInflater)
    private val dialog = Dialog(activity).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(true)
    }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cancelled = false
    private var pollScheduled = false

    init {
        binding.imgQr.setImageResource(android.R.color.transparent)
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnRefresh.setOnClickListener { loadQrCode() }
        dialog.setOnDismissListener {
            if (!cancelled) {
                cancelled = true
                executor.shutdownNow()
            }
        }
    }

    fun show() {
        if (activity.isFinishing || dialog.isShowing) return
        dialog.show()
        val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        loadQrCode()
    }

    fun dismiss() {
        cancelled = true
        executor.shutdownNow()
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun loadQrCode() {
        if (cancelled) return
        pollScheduled = false
        binding.tvStatus.setText(R.string.login_waiting)
        runCatching { executor.execute {
            val ticket = runCatching { api.generateQr() }.getOrNull()
            main.post {
                if (cancelled) return@post
                if (ticket == null || ticket.isEmpty) {
                    binding.tvStatus.text = activity.getString(R.string.login_failed, "获取二维码失败")
                    return@post
                }
                binding.imgQr.setImageBitmap(QrCodeUtil.generate(ticket.url, 640))
                binding.tvStatus.setText(R.string.login_opening_app)
                openInBilibiliApp(ticket.url)
                startPolling(ticket.key)
            }
        } }
    }

    /**
     * Opens the Bilibili login/confirm URL in the Bilibili app when it is the resolved handler
     * (verified on a real device: tv.danmaku.bili MWebActivity shows the "确认登录" page for the
     * same account). This lets the user confirm login on the same phone instead of needing a
     * second device to scan the QR code. Falls back to the in-app QR code otherwise.
     */
    private fun openInBilibiliApp(url: String) {
        if (url.isBlank()) return
        try {
            // Do NOT use resolveActivity() to check the handler: on Android 11+ package visibility
            // hides other apps unless <queries> is declared, so it returns null even though the
            // Bilibili app handles the URL. Just start it and fall back on ActivityNotFound.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            Log.i(TAG, "login URL handed to the resolved app (expected tv.danmaku.bili)")
        } catch (t: Throwable) {
            Log.w(TAG, "open login URL in Bilibili app failed, QR fallback", t)
            binding.tvStatus.setText(R.string.login_hint)
        }
    }

    private fun startPolling(key: String) {
        runCatching { executor.execute {
            val result = runCatching { api.pollQr(key) }.getOrElse { QrPollResult.Failure(it.message ?: "网络错误") }
            main.post {
                if (cancelled) return@post
                when (result) {
                    is QrPollResult.Success -> {
                        Log.i(TAG, "Bilibili QR login success uid=${result.uid}")
                        cancelled = true
                        executor.shutdownNow()
                        if (dialog.isShowing) dialog.dismiss()
                        onSuccess(result.cookies, result.uid)
                    }
                    is QrPollResult.Scanned -> {
                        binding.tvStatus.setText(R.string.login_scanned)
                        scheduleNextPoll(key)
                    }
                    is QrPollResult.Pending -> {
                        binding.tvStatus.setText(R.string.login_waiting)
                        scheduleNextPoll(key)
                    }
                    is QrPollResult.Expired -> {
                        binding.tvStatus.setText(R.string.login_expired)
                        loadQrCode()
                    }
                    is QrPollResult.Failure -> {
                        binding.tvStatus.text = activity.getString(R.string.login_failed, result.message)
                        onFailure(result.message)
                    }
                }
            }
        } }
    }

    private fun scheduleNextPoll(key: String) {
        if (pollScheduled || cancelled) return
        pollScheduled = true
        main.postDelayed({
            pollScheduled = false
            if (!cancelled) startPolling(key)
        }, POLL_INTERVAL_MS)
    }

    companion object {
        private const val TAG = "LoginDialog"
        private const val POLL_INTERVAL_MS = 2000L
    }
}
