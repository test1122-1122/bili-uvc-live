package com.biliuvc.live.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.text.method.ScrollingMovementMethod
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.biliuvc.live.R
import com.biliuvc.live.bili.BiliApi
import com.biliuvc.live.bili.BiliSession
import com.biliuvc.live.bili.RoomInfo
import com.biliuvc.live.bili.SessionStore
import com.biliuvc.live.bili.StartLiveResult
import com.biliuvc.live.capture.UvcDeviceInfo
import com.biliuvc.live.data.AppPrefs
import com.biliuvc.live.databinding.ActivityMainBinding
import com.biliuvc.live.databinding.DialogFaceAuthBinding
import com.biliuvc.live.live.StreamEngine
import com.biliuvc.live.rtmp.RtmpState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single screen: UVC preview + Bilibili QR login + start/stop live + live stats.
 *
 * The whole session is intentionally kept in the foreground: Android may destroy the
 * TextureView when the activity is stopped, so streaming is stopped when leaving the app
 * to avoid leaving an orphaned Bilibili live room behind.
 */
class MainActivity : AppCompatActivity(), StreamEngine.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var engine: StreamEngine
    private lateinit var api: BiliApi
    private lateinit var sessionStore: SessionStore

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var session: BiliSession? = null
    private var roomInfo: RoomInfo? = null
    private var currentPreviewSize: Pair<Int, Int>? = null
    private var liveConnected = false
    private var startInProgress = false
    private var stopping = false
    private var cameraGranted = false
    private var micGranted = false
    private var liveAttemptId = 0

    private var loginDialog: LoginDialog? = null
    private var faceAuthDialog: Dialog? = null
    private var faceAuthPolling = false

    private var bannerHideRunnable: Runnable? = null
    private var lastBackPressMs = 0L

    private val connectWatchdog = Runnable {
        if (startInProgress && !liveConnected) {
            startInProgress = false
            toast(getString(R.string.live_connect_failed, "连接超时"))
            stopLiveInternal(stopBili = true)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Android 12+ requires CAMERA permission for USB video class devices, otherwise
        // UsbManager.requestPermission() is denied without even showing the system dialog.
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        micGranted = result[Manifest.permission.RECORD_AUDIO] ?: micGranted
        if (cameraGranted) {
            if (!engine.isCameraOpened()) {
                startUsbEngine()
                main.postDelayed({ engine.refreshUsbState(requestPermissionIfNeeded = true) }, 300L)
            }
        } else {
            showBanner(getString(R.string.camera_permission_required), autoHideMs = 0L)
            binding.centerHint.visibility = View.VISIBLE
            binding.btnUsbRetry.visibility = View.VISIBLE
        }
        if (!micGranted) toast(getString(R.string.toast_permission_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = AppPrefs(this)
        engine = StreamEngine(this).also { it.setListener(this) }
        api = BiliApi()
        sessionStore = SessionStore(this)

        setupTextureView()
        setupButtons()
        loadSession()

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        engine.applyStreamConfig(prefs.streamConfig)
        if (cameraGranted) {
            // CAMERA is required for USB video class devices on Android 12+; the microphone only
            // affects audio and can be denied (video-only stream).
            startUsbEngine()
            if (!micGranted) permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            binding.centerHint.visibility = View.VISIBLE
            binding.btnUsbRetry.visibility = View.VISIBLE
            showBanner(getString(R.string.camera_permission_required), autoHideMs = 0L)
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // App was launched because the camera was just plugged in; the system chooser may have
            // granted access already, so re-check once the engine is initialized.
            main.postDelayed({ engine.refreshUsbState(requestPermissionIfNeeded = true) }, 800L)
        }
    }

    private fun startUsbEngine() {
        binding.progressHint.visibility = View.VISIBLE
        binding.centerHint.visibility = View.VISIBLE
        binding.btnUsbRetry.visibility = View.VISIBLE
        binding.tvHint.setText(R.string.hint_connect_camera)
        engine.start()
    }

    private fun showUsbDiagnostics() {
        val report = runCatching { engine.usbDeviceReport() }
            .getOrElse { "读取 USB 信息失败: ${it.message}" }
        showBanner(getString(R.string.usb_diag_message, report), autoHideMs = 0L)
    }

    /**
     * Non-modal, in-layout message bar. Never uses a Dialog/Toast so it can never cover the
     * system USB permission dialog (a frequent source of "cannot tap Allow" reports).
     */
    private fun showBanner(message: String, autoHideMs: Long = 5_000L) {
        if (isFinishing || isDestroyed) return
        bannerHideRunnable?.let { main.removeCallbacks(it) }
        binding.tvBanner.text = if (autoHideMs > 0L) message else "$message\n\n（点按消息条关闭）"
        binding.tvBanner.movementMethod = ScrollingMovementMethod()
        binding.tvBanner.visibility = View.VISIBLE
        binding.tvBanner.setOnClickListener { hideBanner() }
        if (autoHideMs > 0L) {
            val runnable = Runnable { binding.tvBanner.visibility = View.GONE }
            bannerHideRunnable = runnable
            main.postDelayed(runnable, autoHideMs)
        }
    }

    private fun hideBanner() {
        bannerHideRunnable?.let { main.removeCallbacks(it) }
        bannerHideRunnable = null
        binding.tvBanner.visibility = View.GONE
    }

    private fun setupTextureView() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                engine.setPreviewSurface(st)
                binding.textureView.post { applyPreviewTransform() }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                engine.setPreviewSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    private fun applyPreviewTransform() {
        val size = currentPreviewSize ?: prefs.streamConfig.let { it.width to it.height }
        val vw = binding.textureView.width
        val vh = binding.textureView.height
        if (vw <= 0 || vh <= 0 || size.first <= 0 || size.second <= 0) return
        val viewAspect = vw.toFloat() / vh
        val videoAspect = size.first.toFloat() / size.second
        val matrix = Matrix()
        if (videoAspect > viewAspect) {
            matrix.setScale(1f, viewAspect / videoAspect, vw / 2f, vh / 2f)
        } else {
            matrix.setScale(videoAspect / viewAspect, 1f, vw / 2f, vh / 2f)
        }
        binding.textureView.setTransform(matrix)
    }

    private fun setupButtons() {
        binding.btnUsbRetry.setOnClickListener {
            if (!cameraGranted) {
                showBanner(getString(R.string.camera_permission_required), autoHideMs = 0L)
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                )
            } else {
                engine.retryUsbPermission()
                toast(getString(R.string.usb_retry_requested))
            }
        }
        binding.tvCamera.setOnLongClickListener {
            showUsbDiagnostics()
            true
        }
        binding.tvBanner.setOnClickListener { hideBanner() }
        binding.btnLogin.setOnClickListener { onLoginButton() }
        binding.btnLogin.setOnLongClickListener {
            if (session != null) {
                logout()
                true
            } else {
                false
            }
        }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnStream.setOnClickListener {
            if (liveConnected || startInProgress) stopLiveInternal(stopBili = true) else startLiveFlow()
        }
    }

    // ------------------------------------------------------------------ session / login

    private fun loadSession() {
        session = sessionStore.load()
        updateSessionUi()
        session?.let { refreshRoomInfo(it) }
    }

    private fun onLoginButton() {
        if (liveConnected || startInProgress) {
            toast("直播中无法切换账号")
            return
        }
        // No popup menu: tap = login/re-login, long-press = logout. This keeps the screen free
        // of app dialogs that could cover the system USB permission dialog.
        startLogin()
    }

    private fun startLogin() {
        loginDialog?.dismiss()
        loginDialog = LoginDialog(
            this,
            api,
            onSuccess = { cookies, uid -> onLoginSuccess(cookies, uid) },
            onFailure = { message -> toast(message) }
        ).also { it.show() }
    }

    private fun onLoginSuccess(cookies: String, uid: Long) {
        io.execute {
            val nav = api.nav(cookies)
            if (nav == null) {
                main.post { toast(getString(R.string.toast_session_expired)) }
                return@execute
            }
            val roomId = api.roomIdByUid(cookies, uid) ?: 0L
            val info = if (roomId > 0) api.roomInfo(cookies, roomId) else null
            main.post {
                session = BiliSession(cookies, uid, nav.uname, roomId)
                roomInfo = info
                session?.let { sessionStore.save(it) }
                updateSessionUi()
                toast(getString(R.string.login_success))
                if (roomId <= 0L) {
                    toast("该账号尚未开通直播间，请先到 B 站开通")
                }
            }
        }
    }

    private fun refreshRoomInfo(s: BiliSession) {
        io.execute {
            // A single network hiccup must not log the user out; only an explicit -101 on a
            // later startLive attempt clears the stored session.
            val nav = runCatching { api.nav(s.cookies) }.getOrNull()
            val info = if (nav == null) null else runCatching { api.roomInfo(s.cookies, s.roomId) }.getOrNull()
            main.post {
                if (nav == null) {
                    toast(getString(R.string.refresh_room_failed))
                } else {
                    if (info != null) roomInfo = info
                    updateSessionUi()
                }
            }
        }
    }

    private fun logout() {
        session = null
        roomInfo = null
        sessionStore.clear()
        updateSessionUi()
        toast(getString(R.string.toast_logout_done))
    }

    private fun updateSessionUi() {
        val s = session
        binding.btnLogin.text =
            if (s != null) getString(R.string.btn_logged_in, s.uname) else getString(R.string.btn_login)
        binding.tvSession.text = when {
            s == null -> ""
            roomInfo != null -> getString(
                R.string.room_info,
                s.roomId.toString(),
                "${roomInfo!!.title} · ${roomInfo!!.areaName}"
            )
            s.roomId > 0 -> "直播间 ${s.roomId}"
            else -> ""
        }
    }

    // ------------------------------------------------------------------ settings

    private fun showSettings() {
        SettingsDialog(
            this,
            prefs.streamConfig,
            engine.supportedSizes(),
            engine.isStreaming()
        ) { updated ->
            prefs.streamConfig = updated
            engine.applyStreamConfig(updated)
        }.show()
    }

    // ------------------------------------------------------------------ live

    private fun startLiveFlow() {
        if (startInProgress) return
        val s = session
        if (s == null) {
            toast(getString(R.string.live_need_login))
            startLogin()
            return
        }
        if (!engine.isCameraOpened()) {
            toast(getString(R.string.live_need_camera))
            return
        }
        if (s.roomId <= 0) {
            toast("该账号尚未开通直播间")
            return
        }
        // Start immediately: room info is already visible in the top bar, and avoiding a
        // confirmation dialog keeps the app from stacking windows over system USB dialogs.
        startLiveAsync(s)
    }

    private fun startLiveAsync(s: BiliSession) {
        if (startInProgress) return
        startInProgress = true
        val attempt = ++liveAttemptId
        binding.btnStream.isEnabled = false
        binding.tvLiveState.visibility = View.VISIBLE
        binding.tvLiveState.setText(R.string.status_connecting)
        toast(getString(R.string.live_starting))
        io.execute {
            // Always refresh room info before startLive: areas can change, and area_v2 must be
            // a valid sub-area id (the reference implementation does the same).
            val freshInfo = runCatching { api.roomInfo(s.cookies, s.roomId) }.getOrNull() ?: roomInfo
            if (freshInfo == null || freshInfo.areaId <= 0) {
                main.post {
                    if (attempt != liveAttemptId) return@post
                    startInProgress = false
                    binding.btnStream.isEnabled = true
                    resetIdleUi()
                    toast(getString(R.string.room_area_missing))
                }
                return@execute
            }
            roomInfo = freshInfo
            val result = runCatching {
                api.startLive(s.cookies, s.csrf, s.roomId, freshInfo.areaId, s.uid)
            }.getOrElse { StartLiveResult.Failure(it.message ?: "网络错误") }
            main.post {
                if (attempt != liveAttemptId) return@post
                handleStartLiveResult(s, result)
            }
        }
    }

    private fun handleStartLiveResult(s: BiliSession, result: StartLiveResult) {
        if (stopping || isFinishing || isDestroyed) {
            startInProgress = false
            resetIdleUi()
            if (result is StartLiveResult.Success) stopBiliLiveAsync(s)
            return
        }
        when (result) {
            is StartLiveResult.Success -> {
                val cfg = effectiveConfig()
                val started = engine.startStreaming(result.addr, result.code, cfg)
                if (started) {
                    main.removeCallbacks(connectWatchdog)
                    main.postDelayed(connectWatchdog, CONNECT_TIMEOUT_MS)
                }
                if (!started) {
                    startInProgress = false
                    binding.btnStream.isEnabled = true
                    resetIdleUi()
                    toast(getString(R.string.live_connect_failed, "推流初始化失败"))
                    stopBiliLiveAsync(s)
                }
            }
            is StartLiveResult.FaceAuthRequired -> {
                // Bilibili created a pending live request that needs face verification.
                // Reset the in-flight flag, otherwise the automatic/manual retry would be ignored.
                startInProgress = false
                binding.btnStream.isEnabled = false
                showFaceAuthDialog(s, result)
            }
            is StartLiveResult.Failure -> {
                startInProgress = false
                binding.btnStream.isEnabled = true
                resetIdleUi()
                showBanner(getString(R.string.live_start_failed, result.message), autoHideMs = 0L)
            }
        }
    }

    private fun showFaceAuthDialog(s: BiliSession, result: StartLiveResult.FaceAuthRequired) {
        dismissFaceAuth()
        val b = DialogFaceAuthBinding.inflate(layoutInflater)
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(b.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(false)
        }
        b.imgQr.setImageBitmap(QrCodeUtil.generate(result.qrContent, 640))
        b.tvStatus.setText(R.string.face_waiting)
        b.btnCancel.setOnClickListener {
            faceAuthPolling = false
            dialog.dismiss()
            startInProgress = false
            binding.btnStream.isEnabled = true
            resetIdleUi()
        }
        b.btnRetry.setOnClickListener {
            faceAuthPolling = false
            dialog.dismiss()
            startLiveAsync(s)
        }
        dialog.setOnDismissListener {
            faceAuthPolling = false
            if (faceAuthDialog === dialog) faceAuthDialog = null
        }
        faceAuthDialog = dialog
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        startFaceAuthPolling(s, result.faceAuthCode, b)
    }

    private fun startFaceAuthPolling(s: BiliSession, code: Int, b: DialogFaceAuthBinding) {
        faceAuthPolling = true
        val poll = object : Runnable {
            override fun run() {
                if (!faceAuthPolling || isFinishing || isDestroyed) return
                io.execute {
                    val identified = runCatching {
                        api.isFaceAuthDone(s.cookies, s.csrf, s.roomId, code)
                    }.getOrNull()
                    main.post {
                        if (!faceAuthPolling) return@post
                        if (identified == true) {
                            faceAuthPolling = false
                            b.tvStatus.setText(R.string.face_identified)
                            dismissFaceAuth()
                            startLiveAsync(s)
                        } else {
                            main.postDelayed(this, FACE_POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }
        main.postDelayed(poll, FACE_POLL_INTERVAL_MS)
    }

    private fun dismissFaceAuth() {
        faceAuthPolling = false
        faceAuthDialog?.let { if (it.isShowing) it.dismiss() }
        faceAuthDialog = null
    }

    private fun stopLiveInternal(stopBili: Boolean) {
        if (stopping) return
        stopping = true
        startInProgress = false
        liveAttemptId++
        main.removeCallbacks(connectWatchdog)
        dismissFaceAuth()
        val s = session
        engine.stopStreaming()
        if (stopBili && s != null) stopBiliLiveAsync(s)
        main.postDelayed({
            stopping = false
            resetIdleUi()
        }, 400L)
    }

    private fun stopBiliLiveAsync(s: BiliSession) {
        io.execute { runCatching { api.stopLive(s.cookies, s.csrf, s.roomId) } }
    }

    private fun resetIdleUi() {
        liveConnected = false
        startInProgress = false
        binding.btnLogin.isEnabled = true
        binding.btnSettings.isEnabled = true
        binding.btnStream.isEnabled = true
        binding.btnStream.setText(R.string.btn_start)
        binding.tvLiveState.visibility = View.GONE
        binding.tvStats.visibility = View.GONE
    }

    private fun setLiveUi() {
        binding.btnStream.isEnabled = true
        binding.btnStream.setText(R.string.btn_stop)
        binding.tvLiveState.visibility = View.VISIBLE
        binding.tvLiveState.setText(R.string.status_live)
        binding.tvStats.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        binding.btnSettings.isEnabled = false
    }

    // ------------------------------------------------------------------ StreamEngine.Listener

    override fun onCameraDevices(devices: List<UvcDeviceInfo>) {
        if (devices.isEmpty()) {
            binding.centerHint.visibility = View.VISIBLE
            binding.btnUsbRetry.visibility = View.VISIBLE
            binding.tvHint.setText(R.string.hint_connect_camera)
            binding.progressHint.visibility = View.GONE
        } else {
            binding.tvHint.text = "检测到 ${devices.size} 个 UVC 摄像头，正在初始化…"
        }
    }

    override fun onCameraOpened(size: Pair<Int, Int>, supportedSizes: List<Pair<Int, Int>>) {
        currentPreviewSize = size
        binding.centerHint.visibility = View.GONE
        binding.btnUsbRetry.visibility = View.GONE
        binding.progressHint.visibility = View.GONE
        binding.tvCamera.text = getString(
            R.string.camera_info,
            engine.devices().firstOrNull()?.name ?: "UVC Camera",
            size.first,
            size.second
        )
        binding.textureView.post { applyPreviewTransform() }
    }

    override fun onCameraDetached() {
        currentPreviewSize = null
        binding.centerHint.visibility = View.VISIBLE
        binding.tvHint.setText(R.string.hint_connect_camera)
        binding.tvCamera.text = getString(R.string.hint_connect_camera)
        if (liveConnected || startInProgress) {
            stopLiveInternal(stopBili = true)
            toast("摄像头断开，直播已停止")
        }
    }

    override fun onCameraError(message: String) {
        binding.centerHint.visibility = View.VISIBLE
        binding.btnUsbRetry.visibility = View.VISIBLE
        binding.progressHint.visibility = View.GONE
        binding.tvHint.text = getString(R.string.hint_camera_error, message)
        // Also toast it: the hint may be hidden behind the preview on some devices and a silent
        // failure is the worst possible UX for USB debugging.
        if (message.contains("权限被拒绝")) {
            showBanner(getString(R.string.usb_permission_message), autoHideMs = 0L)
        } else {
            showBanner(getString(R.string.hint_camera_error, message), autoHideMs = 8_000L)
        }
    }

    override fun onStreamState(state: RtmpState, detail: String?) {
        when (state) {
            RtmpState.PUBLISHING -> {
                main.removeCallbacks(connectWatchdog)
                startInProgress = false
                liveConnected = true
                setLiveUi()
                toast(getString(R.string.live_started))
            }
            RtmpState.CONNECTING, RtmpState.CONNECTED -> {
                binding.tvLiveState.visibility = View.VISIBLE
                binding.tvLiveState.setText(R.string.status_connecting)
            }
            RtmpState.ERROR -> {
                main.removeCallbacks(connectWatchdog)
                if (!stopping) {
                    toast(getString(R.string.live_connect_failed, detail ?: "未知错误"))
                    stopLiveInternal(stopBili = true)
                }
            }
            RtmpState.STOPPED, RtmpState.IDLE -> {
                main.removeCallbacks(connectWatchdog)
                if (!stopping) {
                    resetIdleUi()
                }
            }
        }
    }

    override fun onStats(snapshot: StreamEngine.StreamSnapshot) {
        binding.tvStats.visibility = View.VISIBLE
        binding.tvStats.text = getString(
            R.string.stats_format,
            UiFormat.duration(snapshot.durationMs),
            UiFormat.bitrate(snapshot.bitrateBps),
            snapshot.previewFps,
            snapshot.droppedVideoFrames
        )
    }

    override fun onError(message: String) {
        toast(message)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Launched via the USB_DEVICE_ATTACHED intent filter (camera just plugged in).
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            binding.btnUsbRetry.visibility = View.VISIBLE
            engine.start()
            // NOT just start(): start() is a no-op once running. Android's "choose an app for the
            // USB device" chooser may have granted permission right now, so re-enumerate and open.
            engine.refreshUsbState(requestPermissionIfNeeded = true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::engine.isInitialized) return
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (cameraGranted && !engine.isCameraOpened()) {
            // Recover when permission was granted while we were behind a system dialog/chooser.
            engine.refreshUsbState(requestPermissionIfNeeded = false)
        }
    }

    override fun onStop() {
        super.onStop()
        if (liveConnected || startInProgress) {
            stopLiveInternal(stopBili = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loginDialog?.dismiss()
        loginDialog = null
        dismissFaceAuth()
        engine.release()
        // shutdown() lets an already queued stopLive request run; shutdownNow() would cancel it
        // and could leave a dangling Bilibili live session.
        io.shutdown()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (liveConnected || startInProgress) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressMs <= 2_000L) {
                stopLiveInternal(stopBili = true)
            } else {
                lastBackPressMs = now
                showBanner(getString(R.string.live_stop_hint), autoHideMs = 2_000L)
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Applies the runtime microphone permission to the persisted configuration. */
    private fun effectiveConfig() = prefs.streamConfig.copy(
        audioEnabled = prefs.streamConfig.audioEnabled && micGranted
    )

    private fun toast(message: String) {
        showBanner(message, autoHideMs = 4_000L)
    }

    companion object {
        private const val FACE_POLL_INTERVAL_MS = 3000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
