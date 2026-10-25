package com.biliuvc.live.capture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer

/**
 * UVC 摄像头控制器：设备插拔、USB 权限、打开/关闭、TextureView 预览、NV21 帧回调。
 *
 * 实现基于 vendored 的 `app/libs/libuvc.aar`（saki4510t UVCCamera + USBMonitor）。
 *
 * ## 线程模型
 * - 公开方法都可以在任意线程调用，**不会阻塞调用线程**（内部把所有耗时操作 post 到专用
 *   `UvcController` HandlerThread 串行执行）。
 * - [Listener.onDeviceAttached] / [Listener.onDeviceDetached] / [Listener.onCameraOpened] /
 *   [Listener.onCameraError] 一律回调在**主线程**。
 * - [Listener.onFrame] 回调在 UVC native 采集线程（USB 线程），**只做拷贝/转发**，
 *   绝不能在里面编码或做 IO；[UvcFrame.nv21] 已经是独立数组，可直接入队。
 *
 * ## 与 libuvc.aar 的兼容性说明（重要，需要真机验证）
 * AAR 里的 `USBMonitor.register()` 内部使用 `Context.registerReceiver(receiver, filter)`（无 flag）。
 * 在 Android 13+ 设备、且 app `targetSdk >= 34` 时，为该 filter（含自定义 action）注册 receiver
 * 必须显式指定 `RECEIVER_EXPORTED/NOT_EXPORTED`，否则抛 `SecurityException`。
 * 因此这里**不调用 `USBMonitor.register()`**，改为：
 *  - 用 `USBManager` + 自己注册的 BroadcastReceiver 处理 USB 插拔与权限结果（带正确 flag）；
 *  - 仍然用 `USBMonitor` 做设备枚举（`getDeviceList()`）和 `UsbControlBlock` 创建
 *    （`openDevice()` 不需要 monitor 处于 registered 状态）。
 *
 * 另外 USB 权限的 PendingIntent 在 API 31+ 必须用 `FLAG_MUTABLE`：系统通过 fill-in intent
 * 回填 `EXTRA_DEVICE` / `EXTRA_PERMISSION_GRANTED`，而 `FLAG_IMMUTABLE` 会丢弃 fill-in
 * （AOSP PendingIntent fill-in 语义），否则 Android 12+ 首次授权后拿不到 UsbDevice、
 * 摄像头永远打不开。
 *
 * camera 工作线程是懒创建、空闲 1s 自退出的（见 [idleQuitRunnable]）：stop() 之后线程会
 * 安全退出（不随 Activity 重建泄漏），start() 会自动重建，短时间内的 stop→start 复用同一线程。
 *
 * ## 真机验证点（本环境无真机/模拟器/adb，未实机验证）
 * 1. 首次插入摄像头时系统 USB 权限弹窗（本实现在 start()/插入时自动请求一次权限）。
 * 2. 不同摄像头支持的 MJPEG/YUYV 分辨率集合；`setPreviewSize` 的 fps 区间兼容性。
 * 3. `setFrameCallback(..., PIXEL_FORMAT_NV21)` 的 NV21 输出是否与实际 buffer 大小一致。
 * 4. 拔插/重复 start-stop 后 native 资源是否完全释放（`UVCCamera.open()` 会 clone 一个
 *    UsbControlBlock，见下方 closeCameraInternal 的注释）。
 * 5. TextureView 销毁与 `detachPreview()` 的时序（必须在 SurfaceTexture 被 release 前停止预览）。
 */
class UvcController(context: Context) {

    interface Listener {
        /** 当前已接入的 UVC 设备列表（插入、拔出、start 后都会回调；主线程）。 */
        fun onDeviceAttached(devices: List<UvcDeviceInfo>)

        /** 某个 UVC 设备被拔出（主线程）。 */
        fun onDeviceDetached(device: UvcDeviceInfo)

        /** 摄像头打开成功，返回该摄像头支持的分辨率（主线程）。 */
        fun onCameraOpened(supportedSizes: List<Pair<Int, Int>>)

        /** 摄像头/USB 出错（主线程），实现内部已捕获异常，不会崩溃。 */
        fun onCameraError(message: String)

        /** 摄像头 NV21 帧（USB 线程，勿做耗时操作）。 */
        fun onFrame(frame: UvcFrame)
    }

    private val appContext: Context = context.applicationContext ?: context
    private val usbManager: UsbManager? =
        appContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * camera 工作线程：懒创建、空闲自退出（见 [idleQuitRunnable]）。
     * stop() 后若 1s 内没有再次 start()/操作，线程会 quitSafely，避免 Activity 重建/反复
     * start-stop 泄漏 HandlerThread；再次 start() 会自动重建。
     */
    @Volatile
    private var cameraThread: HandlerThread? = null

    @Volatile
    private var cameraHandler: Handler? = null

    private val handlerLock = Any()

    /** 线程长时间空闲（未 start 且无摄像头/monitor）时退出。 */
    private val idleQuitRunnable = object : Runnable {
        override fun run() {
            if (startedFlag || uvcCamera != null || usbMonitor != null) {
                return
            }
            val handler = cameraHandler ?: return
            val thread: HandlerThread?
            synchronized(handlerLock) {
                if (cameraHandler !== handler) return
                thread = cameraThread
                cameraHandler = null
                cameraThread = null
            }
            Log.i(TAG, "camera thread idle, quitting")
            try {
                handler.removeCallbacks(this)
                thread?.quitSafely()
            } catch (t: Throwable) {
                Log.w(TAG, "quit camera thread failed", t)
            }
        }
    }

    /** 保护 UvcController 的共享状态；也会包住「设置预览 surface + startPreview」保证原子性。 */
    private val stateLock = Any()

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var startedFlag = false

    @Volatile
    private var streamingFlag = false

    @Volatile
    private var currentSize: Pair<Int, Int>? = null

    @Volatile
    private var supportedSizeList: List<Pair<Int, Int>> = emptyList()

    /** 主线程调用 attachPreview 时缓存；如果摄像头尚未打开，打开后自动应用。 */
    @Volatile
    private var pendingSurface: SurfaceTexture? = null

    /** 已经真正 setPreviewTexture 的 surface。 */
    private var appliedSurface: SurfaceTexture? = null

    /** 每次 attach/detach 递增，用于丢弃过期的 attachPreview 任务（避免对已销毁的 ST 开始预览）。 */
    private var previewGeneration = 0L

    @Volatile
    private var uvcCamera: UVCCamera? = null

    // 以下字段只在 cameraThread 上访问（desired* 从任意线程写，标 @Volatile）。
    private var activeDevice: UsbDevice? = null
    private var activeControlBlock: USBMonitor.UsbControlBlock? = null
    private val knownDevices = LinkedHashMap<Int, UsbDevice>()
    private val requestedPermissions = HashSet<Int>()
    private var usbMonitor: USBMonitor? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var permissionIntent: PendingIntent? = null
    private var currentFrameFormat = UVCCamera.FRAME_FORMAT_MJPEG

    @Volatile
    private var desiredWidth = 0

    @Volatile
    private var desiredHeight = 0

    /** Requested capture frame rate (1..60); applied on the next camera open/reconfigure. */
    @Volatile
    private var desiredFps = 30

    /** selectDevice / requestUsbPermission 指定的目标设备。 */
    private var desiredDeviceId: Int? = null

    /** 当前已接入 UVC 设备的快照，任意线程可读。 */
    @Volatile
    private var cachedDeviceInfos: List<UvcDeviceInfo> = emptyList()

    /** 每个 controller 独立的自定义权限 action，避免多个实例互相干扰。 */
    private val permissionAction = "com.biliuvc.live.capture.USB_PERMISSION.${System.identityHashCode(this)}"

    // ------------------------------------------------------------------------------------
    // 公开 API（ARCHITECTURE.md 4.3，签名保持不变）
    // ------------------------------------------------------------------------------------

    fun setListener(l: Listener) {
        listener = l
        // 便于 UI 在任意时刻 setListener 后立即拿到当前状态。
        val devices = cachedDeviceInfos
        mainHandler.post { safeListener { l.onDeviceAttached(devices) } }
        val size = currentSize
        val sizes = supportedSizeList
        if (uvcCamera != null && size != null) {
            mainHandler.post { safeListener { l.onCameraOpened(sizes) } }
        }
    }

    /** 注册 USB 监听；已授权的已接入设备会自动打开；无设备则等待插拔。可重复调用。 */
    fun start() {
        postToCamera {
            // 极端竞态：本任务可能是在 idle-quit 已经把线程置空、quitSafely 之前被处理的。
            // 此时线程即将退出，直接换一个新线程重试 start()。
            if (cameraHandler == null) {
                startedFlag = false
                mainHandler.post { start() }
                return@postToCamera
            }
            // 取消空闲退出：本次 start 之后线程要一直活着。
            cameraHandler?.removeCallbacks(idleQuitRunnable)
            if (startedFlag) {
                Log.d(TAG, "start(): already started")
                return@postToCamera
            }
            if (usbManager == null) {
                postError("USB 服务不可用（UsbManager 为 null）")
                return@postToCamera
            }
            startedFlag = true
            try {
                // USBMonitor 只作为「枚举设备 + 创建 UsbControlBlock」的桥；不调用 register()。
                usbMonitor = USBMonitor(appContext, monitorListener)
                registerUsbReceiver()
                refreshDeviceCache(notify = false)
                Log.i(TAG, "start(): uvc devices=${knownDevices.size}")
                if (cachedDeviceInfos.isNotEmpty()) {
                    postOnDeviceAttached(cachedDeviceInfos)
                }
                autoOpenFirstAvailable()
            } catch (t: Throwable) {
                Log.e(TAG, "start() failed", t)
                startedFlag = false
                cleanupMonitor()
                postError("初始化 USB 摄像头失败: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** 停止预览、关闭摄像头、注销 monitor；可重复调用，之后可再次 [start]。 */
    fun stop() {
        val handler = cameraHandler
        if (handler == null) {
            // camera 线程已经空闲退出（之前 stop 过）：只需清理 surface 缓存。
            startedFlag = false
            synchronized(stateLock) {
                previewGeneration++
                pendingSurface = null
                streamingFlag = false
                appliedSurface = null
            }
            Log.d(TAG, "stop(): camera thread not running")
            return
        }
        handler.post { runCameraTask { stopInternal() } }
    }

    fun devices(): List<UvcDeviceInfo> = cachedDeviceInfos

    /** 选择要使用的摄像头（需要权限时自动请求）；下次打开时生效。 */
    fun selectDevice(deviceId: Int) {
        postToCamera {
            val device = knownDevices[deviceId]
            if (device == null) {
                postError("未找到 USB 设备 id=$deviceId（可能已拔出）")
                return@postToCamera
            }
            desiredDeviceId = deviceId
            if (uvcCamera != null && activeDevice?.deviceId == deviceId) {
                Log.d(TAG, "selectDevice: already opened id=$deviceId")
                return@postToCamera
            }
            if (hasPermission(device)) {
                openAuthorizedDevice(device)
            } else {
                requestPermissionInternal(device)
            }
        }
    }

    /** 设置预览/编码分辨率；摄像头下次打开时生效（未开始预览时会立即尝试应用）。 */
    fun setPreviewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "setPreviewSize: invalid ${width}x$height")
            return
        }
        desiredWidth = width
        desiredHeight = height
        // 线程已退出（未 start）时只记住目标尺寸，open 时由 choosePreviewSize 使用。
        postToCameraIfActive { applyDesiredSizeIfPossible() }
    }

    /**
     * Sets the requested capture frame rate (clamped to 1..60). Applies on the next open; call
     * [reconfigure] to apply immediately to the current camera.
     */
    fun setFrameRate(fps: Int) {
        val clamped = fps.coerceIn(1, 60)
        if (desiredFps == clamped) return
        desiredFps = clamped
        Log.i(TAG, "setFrameRate: desired=$desiredFps")
    }

    /**
     * Re-opens the current camera with the current desired size/fps. Used when the user changes
     * stream settings while the preview is already running (UVC setPreviewSize cannot be applied
     * reliably while streaming).
     */
    fun reconfigure() {
        postToCamera {
            if (!startedFlag) return@postToCamera
            val device = activeDevice ?: knownDevices.values.firstOrNull() ?: return@postToCamera
            if (!hasPermission(device)) {
                requestPermissionInternal(device)
                return@postToCamera
            }
            if (uvcCamera != null) {
                Log.i(TAG, "reconfigure: close/reopen for ${desiredWidth}x${desiredHeight}@${desiredFps}fps")
                closeCameraInternal("reconfigure")
            }
            openAuthorizedDevice(device)
        }
    }

    /**
     * TextureView 就绪后调用，开始 UVC 预览渲染 + NV21 帧回调。
     *
     * 允许在摄像头打开前调用：会缓存 SurfaceTexture，等 [Listener.onCameraOpened] 之后自动应用。
     */
    fun attachPreview(surfaceTexture: SurfaceTexture) {
        val generation: Long
        synchronized(stateLock) {
            previewGeneration++
            generation = previewGeneration
            pendingSurface = surfaceTexture
        }
        // camera 未启动时 surface 已缓存，open 完成后会自动应用（不为此创建线程）。
        postToCameraIfActive { attachPreviewInternal(surfaceTexture, generation) }
    }

    /** 停止预览并释放 native 对 SurfaceTexture 的引用；摄像头保持打开。 */
    fun detachPreview() {
        synchronized(stateLock) {
            previewGeneration++
            pendingSurface = null
            streamingFlag = false
            appliedSurface = null
            // 同步 stopPreview：必须在调用方 release SurfaceTexture 之前完成，
            // 否则 native 仍可能往已释放的 ST 上渲染导致崩溃（真机重点验证）。
            val cam = uvcCamera
            if (cam != null) {
                try {
                    cam.stopPreview()
                } catch (t: Throwable) {
                    Log.w(TAG, "detachPreview: stopPreview failed", t)
                }
            }
        }
    }

    /** 预览是否正在出帧。 */
    fun isStreaming(): Boolean = streamingFlag

    /** 最近一次打开成功（或收到帧）的宽高，供 StreamEngine 对齐编码尺寸。 */
    val currentPreviewSize: Pair<Int, Int>?
        get() = currentSize

    fun supportedSizes(): List<Pair<Int, Int>> = supportedSizeList

    /** 请求某个设备的 USB 权限（会切换目标设备）；已授权则直接打开。 */
    fun requestUsbPermission(device: UvcDeviceInfo) {
        postToCamera {
            val real = knownDevices[device.deviceId]
                ?: usbManager?.deviceList?.values?.firstOrNull { it.deviceId == device.deviceId }
            if (real == null) {
                postError("设备已移除: ${device.name} (id=${device.deviceId})")
                return@postToCamera
            }
            knownDevices[real.deviceId] = real
            updateDeviceInfos()
            desiredDeviceId = real.deviceId
            if (hasPermission(real)) {
                openAuthorizedDevice(real)
            } else {
                requestPermissionInternal(real)
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // start / stop 内部实现
    // ------------------------------------------------------------------------------------

    private fun stopInternal() {
        val alreadyStopped = !startedFlag && usbMonitor == null && uvcCamera == null
        // 即使已经处于停止状态，也清理 surface 缓存：stop() 前可能刚 attachPreview 过。
        startedFlag = false
        synchronized(stateLock) {
            previewGeneration++
            pendingSurface = null
            streamingFlag = false
            appliedSurface = null
        }
        if (alreadyStopped) {
            Log.d(TAG, "stop(): already stopped")
        } else {
            Log.i(TAG, "stop()")
            closeCameraInternal("stop")
            cleanupMonitor()
            desiredDeviceId = null
            requestedPermissions.clear()
            knownDevices.clear()
            cachedDeviceInfos = emptyList()
            supportedSizeList = emptyList()
            currentSize = null
            // desiredWidth/desiredHeight 保留：restart 时继续使用调用方上一次设置的分辨率。
        }
        // 不立刻 quit HandlerThread：给短时间内的 restart（如 StreamEngine 改分辨率 stop→start(250ms)）
        // 复用线程的机会；1s 内没有 start()/操作则线程安全退出，避免 Activity 重建/反复 start-stop 泄漏。
        val handler = cameraHandler
        if (handler != null) {
            handler.removeCallbacks(idleQuitRunnable)
            handler.postDelayed(idleQuitRunnable, IDLE_QUIT_DELAY_MS)
        }
    }

    private fun cleanupMonitor() {
        unregisterUsbReceiver()
        val monitor = usbMonitor
        usbMonitor = null
        if (monitor != null) {
            try {
                monitor.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "monitor.destroy failed", t)
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // USB 广播 / 权限
    // ------------------------------------------------------------------------------------

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    permissionAction -> onPermissionResult(intent)
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = usbDeviceExtra(intent) ?: return
                        postToCamera { handleDeviceAttached(device) }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = usbDeviceExtra(intent) ?: return
                        postToCamera { handleDeviceDetached(device) }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：必须显式 NOT_EXPORTED（见类注释）。
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        usbReceiver = receiver

        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        permissionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 必须用 FLAG_MUTABLE：UsbManager.requestPermission() 是通过 fill-in intent 把
            // EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED 回填进这个 PendingIntent 再发给我们的。
            // FLAG_IMMUTABLE 会忽略 fill-in（AOSP PendingIntent fill-in 语义），导致 Android 12+
            // 首次授权后广播里没有 UsbDevice，摄像头永远打不开（只有拔插/重启才恢复）。
            PendingIntent.getBroadcast(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            // API 26~30：默认就是 mutable，显式加 UPDATE_CURRENT 保证 base intent 是最新的。
            @Suppress("UnspecifiedImmutableFlag")
            PendingIntent.getBroadcast(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    private fun unregisterUsbReceiver() {
        val receiver = usbReceiver ?: return
        usbReceiver = null
        permissionIntent = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterReceiver failed", t)
        }
    }

    private fun onPermissionResult(intent: Intent) {
        val device = usbDeviceExtra(intent) ?: run {
            // fill-in 丢失的兜底：正常情况（FLAG_MUTABLE）不会走到这里。
            Log.e(TAG, "permission result without EXTRA_DEVICE (fill-in lost?)")
            postError("USB 权限回调缺少设备信息，请重新插拔摄像头")
            return
        }
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        postToCamera {
            if (!startedFlag) return@postToCamera
            if (!isUvcDevice(device)) return@postToCamera
            if (granted) {
                Log.i(TAG, "USB permission granted for ${deviceLabel(device)}")
                requestedPermissions.remove(device.deviceId)
                knownDevices[device.deviceId] = device
                updateDeviceInfos()
                // 刚授权：不要再用 hasPermission() 复查（系统侧状态可能尚未刷新），直接打开；
                // openDevice 若因权限未生效抛 SecurityException，会延迟重试一次。
                openAuthorizedDevice(device, permissionJustGranted = true)
            } else {
                Log.w(TAG, "USB permission denied for ${deviceLabel(device)}")
                // 移除记录，允许用户之后在 UI 里显式重试（不会自动重弹，只有 attach 广播/显式请求才会再次弹）。
                requestedPermissions.remove(device.deviceId)
                // 拒绝只回调一次错误。
                postError("USB 摄像头权限被拒绝: ${deviceLabel(device)}")
            }
        }
    }

    /**
     * Human-readable snapshot of every USB device currently enumerated by Android. Used by the
     * in-app "USB diagnostics" dialog so users can report VID/PID/class without adb.
     */
    fun usbDeviceReport(): String {
        val mgr = usbManager ?: return "USB 服务不可用（UsbManager = null）"
        val devices = try {
            mgr.deviceList.values.toList()
        } catch (t: Throwable) {
            return "读取 USB 设备列表失败: ${t.message ?: t.javaClass.simpleName}"
        }
        if (devices.isEmpty()) {
            return "系统未枚举到任何 USB 设备。\n\n请确认：\n" +
                "1. OTG 线/转接头支持数据传输（不是仅充电线）；\n" +
                "2. 摄像头在手机上电（必要时用带供电的 USB Hub）；\n" +
                "3. 手机系统设置里没有关闭 OTG/USB Host；\n" +
                "4. 换一台手机或换个摄像头交叉测试。"
        }
        val sb = StringBuilder()
        sb.append("系统枚举到 ${devices.size} 个 USB 设备：\n")
        for (d in devices) {
            val ifaces = try {
                (0 until d.interfaceCount).joinToString(",") { i ->
                    val itf = d.getInterface(i)
                    "${itf.interfaceClass}/${itf.interfaceSubclass}"
                }
            } catch (t: Throwable) {
                "?"
            }
            sb.append("\n• ${try { d.productName ?: d.deviceName } catch (t: Throwable) { "usb" }}\n")
            sb.append("  vid=0x${d.vendorId.toString(16)} pid=0x${d.productId.toString(16)}\n")
            sb.append("  class=${d.deviceClass}/${d.deviceSubclass} iface=[$ifaces]\n")
            sb.append("  uvc=${isUvcDevice(d)} 已授权=${hasPermission(d)}\n")
        }
        return sb.toString()
    }

    /**
     * Re-enumerates USB devices and opens the first camera if permission is already granted.
     *
     * This must be called when Android hands us a USB_DEVICE_ATTACHED intent (the system chooser
     * "select an app for the USB device" may grant access at that moment) and on activity resume.
     * Unlike [start] it is NOT a no-op when already started.
     *
     * @param requestPermissionIfNeeded when true, calls UsbManager.requestPermission() for a
     *        detected-but-unauthorized camera (re-shows the system USB dialog).
     */
    fun refreshDevicesAndOpen(requestPermissionIfNeeded: Boolean) {
        postToCamera {
            if (!startedFlag) {
                Log.i(TAG, "refreshDevicesAndOpen: controller not started")
                return@postToCamera
            }
            refreshDeviceCache(notify = true)
            val device = knownDevices.values.firstOrNull() ?: run {
                Log.i(TAG, "refreshDevicesAndOpen: no UVC device enumerated")
                return@postToCamera
            }
            if (hasPermission(device)) {
                Log.i(TAG, "refreshDevicesAndOpen: permission present, opening ${deviceLabel(device)}")
                openAuthorizedDevice(device)
            } else if (requestPermissionIfNeeded) {
                Log.i(TAG, "refreshDevicesAndOpen: requesting permission for ${deviceLabel(device)}")
                requestedPermissions.remove(device.deviceId)
                requestPermissionInternal(device)
            } else {
                Log.i(TAG, "refreshDevicesAndOpen: permission still missing for ${deviceLabel(device)}")
            }
        }
    }

    /**
     * Re-requests USB host permission for the first attached UVC device. Useful when the user
     * denied the system USB dialog: Android allows asking again, and the system dialog will pop up.
     */
    fun retryUsbPermission() {
        postToCamera {
            if (!startedFlag) {
                postError("USB 摄像头尚未启动，请重新插拔摄像头或重开 App")
                return@postToCamera
            }
            refreshDeviceCache(notify = false)
            var target = knownDevices.values.firstOrNull { !hasPermission(it) }
                ?: knownDevices.values.firstOrNull()
            if (target == null) {
                // Some cameras expose a vendor-specific device/interface class instead of UVC 14.
                // Offer the permission dialog for any non-hub device so the user can at least try.
                target = try {
                    usbManager?.deviceList?.values?.firstOrNull {
                        it.deviceClass != UsbConstants.USB_CLASS_HUB && it.interfaceCount > 0
                    }
                } catch (t: Throwable) {
                    null
                }
            }
            if (target == null) {
                postError("未检测到 USB 摄像头，请重新插拔摄像头")
                return@postToCamera
            }
            if (hasPermission(target)) {
                openAuthorizedDevice(target)
                return@postToCamera
            }
            requestedPermissions.remove(target.deviceId)
            Log.i(TAG, "retryUsbPermission for ${deviceLabel(target)}")
            requestPermissionInternal(target)
        }
    }

    private fun requestPermissionInternal(device: UsbDevice) {
        val mgr = usbManager
        if (mgr == null) {
            postError("USB 服务不可用，无法请求权限")
            return
        }
        if (mgr.hasPermission(device)) {
            openAuthorizedDevice(device)
            return
        }
        if (!requestedPermissions.add(device.deviceId)) {
            Log.d(TAG, "requestPermission: already requested id=${device.deviceId}")
            return
        }
        val pi = permissionIntent
        if (pi == null) {
            postError("USB 权限请求初始化失败")
            return
        }
        try {
            Log.i(TAG, "requesting USB permission for ${deviceLabel(device)}")
            mgr.requestPermission(device, pi)
        } catch (t: Throwable) {
            Log.e(TAG, "requestPermission failed", t)
            postError("请求 USB 权限失败: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------------------------
    // 插拔 / 打开 / 关闭
    // ------------------------------------------------------------------------------------

    private fun handleDeviceAttached(device: UsbDevice) {
        if (!startedFlag) return
        if (!isUvcDevice(device)) return
        refreshDeviceCache(notify = false)
        knownDevices[device.deviceId] = device
        updateDeviceInfos()
        postOnDeviceAttached(cachedDeviceInfos)
        Log.i(TAG, "UVC attached: ${deviceLabel(device)} id=${device.deviceId}")

        if (uvcCamera != null) {
            Log.i(TAG, "camera already open, keep it; new device only added to list")
            return
        }
        val wanted = desiredDeviceId
        if (wanted != null && wanted != device.deviceId) {
            // 用户明确指定了另一台设备，不抢占。
            return
        }
        if (hasPermission(device)) {
            openAuthorizedDevice(device)
        } else {
            requestPermissionInternal(device)
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        if (!startedFlag) return
        requestedPermissions.remove(device.deviceId)
        val known = knownDevices.remove(device.deviceId)
        if (known == null && !isUvcDevice(device)) return

        val info = toDeviceInfo(known ?: device)
        Log.i(TAG, "UVC detached: ${info.name} id=${device.deviceId}")
        if (activeDevice?.deviceId == device.deviceId) {
            closeCameraInternal("usb-detached")
            postError("USB 摄像头已断开: ${info.name}")
        }
        if (desiredDeviceId == device.deviceId) desiredDeviceId = null
        refreshDeviceCache(notify = false)
        // 极少数机型在 DETACHED 广播时 UsbManager 列表里还残留该设备，这里再确保移除一次。
        knownDevices.remove(device.deviceId)
        updateDeviceInfos()
        postOnDeviceDetached(info)
        postOnDeviceAttached(cachedDeviceInfos)
    }

    private fun autoOpenFirstAvailable() {
        if (knownDevices.isEmpty()) {
            Log.i(TAG, "no UVC device attached, waiting for hot-plug")
            return
        }
        val wanted = desiredDeviceId
        val target = if (wanted != null) knownDevices[wanted] else knownDevices.values.first()
        if (target == null) {
            Log.w(TAG, "autoOpen: desired device not present")
            return
        }
        if (hasPermission(target)) {
            openAuthorizedDevice(target)
        } else {
            requestPermissionInternal(target)
        }
    }

    private fun openAuthorizedDevice(
        device: UsbDevice,
        permissionJustGranted: Boolean = false,
        retryCount: Int = 0
    ) {
        if (!startedFlag) return
        if (!permissionJustGranted && !hasPermission(device)) {
            requestPermissionInternal(device)
            return
        }
        if (uvcCamera != null) {
            if (activeDevice?.deviceId == device.deviceId) {
                Log.d(TAG, "openAuthorizedDevice: already open id=${device.deviceId}")
                return
            }
            if (desiredDeviceId != device.deviceId) {
                Log.d(TAG, "openAuthorizedDevice: another camera is open, ignore id=${device.deviceId}")
                return
            }
            closeCameraInternal("switch-device")
        }
        val monitor = usbMonitor
        if (monitor == null) {
            postError("USB monitor 未初始化")
            return
        }
        val ctrlBlock = try {
            monitor.openDevice(device)
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "openDevice failed (justGranted=$permissionJustGranted, retry=$retryCount)",
                t
            )
            if (permissionJustGranted && retryCount < GRANT_RETRY_MAX) {
                // 刚授权的瞬间系统权限状态可能还没刷新，400ms 后重试一次；仍失败才上报。
                val handler = cameraHandler
                if (handler == null) {
                    postError("打开 USB 设备失败: ${t.message ?: t.javaClass.simpleName}")
                } else {
                    handler.postDelayed({
                        try {
                            if (!startedFlag) return@postDelayed
                            if (uvcCamera == null && hasPermission(device)) {
                                openAuthorizedDevice(
                                    device,
                                    permissionJustGranted = true,
                                    retryCount = retryCount + 1
                                )
                            } else {
                                postError("打开 USB 设备失败: ${t.message ?: t.javaClass.simpleName}")
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "retry open failed", e)
                            postError("打开 USB 设备失败: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }, GRANT_RETRY_DELAY_MS)
                }
            } else {
                postError("打开 USB 设备失败: ${t.message ?: t.javaClass.simpleName}")
            }
            return
        }
        openCameraInternal(device, ctrlBlock)
    }

    private fun openCameraInternal(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        var cam: UVCCamera? = null
        try {
            cam = UVCCamera()
            // 注意：UVCCamera.open() 内部会 clone 一个 UsbControlBlock（第二条 UsbDeviceConnection），
            // 关闭时 destroy() 关掉 clone；monitor 的那个 block 由我们在 closeCameraInternal 里 close()。
            cam.open(ctrlBlock)

            val mjpegSizes = querySizes(cam, UVCCamera.FRAME_FORMAT_MJPEG)
            val yuyvSizes = querySizes(cam, UVCCamera.FRAME_FORMAT_YUYV)
            // MJPEG 优先（码率低、USB 带宽友好），没有 MJPEG 才用 YUYV。
            val preferredFormat = if (mjpegSizes.isEmpty()) {
                UVCCamera.FRAME_FORMAT_YUYV
            } else {
                UVCCamera.FRAME_FORMAT_MJPEG
            }
            val preferredSizes =
                if (preferredFormat == UVCCamera.FRAME_FORMAT_YUYV) yuyvSizes else mjpegSizes
            val chosen = choosePreviewSize(preferredSizes)
            val applied = applyPreviewSize(cam, chosen, preferredFormat)
            // P2 修复：applyPreviewSize 内部可能因 MJPEG 失败而降级到 YUYV，这里必须记录
            // 「实际生效」的 format 与 size；全部失败时也不能拿请求值猜，要用真实默认值兜底。
            val effectiveFormat = applied?.format ?: preferredFormat
            val effectiveSize = applied?.size
                ?: resolveActualPreviewSize(cam, preferredSizes, chosen)
            val effectiveSizes = if (applied != null && applied.format != preferredFormat) {
                // 降级换了 format：按实际 format 重新枚举，保证 supportedSizes 与 currentPreviewSize 一致。
                val actualFormatSizes = querySizes(cam, applied.format)
                if (actualFormatSizes.isEmpty()) preferredSizes else actualFormatSizes
            } else {
                preferredSizes
            }
            currentFrameFormat = effectiveFormat

            synchronized(stateLock) {
                uvcCamera = cam
                activeDevice = device
                activeControlBlock = ctrlBlock
                currentSize = effectiveSize
                supportedSizeList =
                    if (effectiveSizes.isEmpty()) listOf(effectiveSize) else effectiveSizes
                streamingFlag = false
            }
            Log.i(
                TAG,
                "camera opened: actual=${effectiveSize.first}x${effectiveSize.second} " +
                    "format=${formatName(effectiveFormat)} requested=$chosen " +
                    "applied=${applied != null} supported=${supportedSizeList.size}"
            )
            postOnCameraOpened(supportedSizeList)

            // attachPreview 可能早于 open：这里应用缓存的 SurfaceTexture 并开始预览。
            synchronized(stateLock) {
                val surface = pendingSurface ?: appliedSurface
                if (surface != null) {
                    pendingSurface = null
                    startPreviewLocked(cam, surface)
                } else {
                    Log.i(TAG, "camera opened, waiting for TextureView surface (attachPreview)")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "open camera failed", t)
            synchronized(stateLock) {
                uvcCamera = null
                activeDevice = null
                activeControlBlock = null
                streamingFlag = false
                appliedSurface = null
                currentSize = null
            }
            if (cam != null) {
                try {
                    cam.destroy()
                } catch (e: Throwable) {
                    Log.w(TAG, "destroy failed camera", e)
                }
            }
            try {
                // 清掉 monitor 中这个设备的连接，避免 fd 泄漏；下次打开会重新创建。
                ctrlBlock.close()
            } catch (e: Throwable) {
                Log.w(TAG, "close ctrlBlock failed", e)
            }
            postError("打开 UVC 摄像头失败: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun closeCameraInternal(reason: String) {
        val cam: UVCCamera?
        val block: USBMonitor.UsbControlBlock?
        synchronized(stateLock) {
            previewGeneration++
            streamingFlag = false
            appliedSurface = null
            cam = uvcCamera
            block = activeControlBlock
            uvcCamera = null
            activeDevice = null
            activeControlBlock = null
            currentSize = null
        }
        if (cam == null && block == null) return
        Log.i(TAG, "closing camera ($reason)")
        if (cam != null) {
            try {
                cam.stopPreview()
            } catch (t: Throwable) {
                Log.w(TAG, "stopPreview failed", t)
            }
            try {
                // destroy() -> close()：释放 native camera 并关闭 open() 时 clone 的 ctrlBlock。
                cam.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "camera destroy failed", t)
            }
        }
        if (block != null) {
            try {
                // clone 的 close() 会顺带把 monitor 内部的 map 条目移除；这里再关掉 monitor 原本的
                // block，保证 UsbDeviceConnection 不泄漏（UsbControlBlock.close 是幂等的）。
                block.close()
            } catch (t: Throwable) {
                Log.w(TAG, "ctrlBlock close failed", t)
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // 预览 surface / 帧回调
    // ------------------------------------------------------------------------------------

    private fun attachPreviewInternal(surfaceTexture: SurfaceTexture, generation: Long) {
        synchronized(stateLock) {
            if (generation != previewGeneration) {
                Log.d(TAG, "attachPreview: stale request dropped")
                return
            }
            val cam = uvcCamera
            if (cam == null) {
                Log.i(TAG, "attachPreview: camera not open yet, surface cached")
                pendingSurface = surfaceTexture
                return
            }
            pendingSurface = null
            if (appliedSurface === surfaceTexture && streamingFlag) {
                Log.d(TAG, "attachPreview: already previewing this surface")
                return
            }
            if (streamingFlag) {
                try {
                    cam.stopPreview()
                } catch (t: Throwable) {
                    Log.w(TAG, "attachPreview: stopPreview failed", t)
                }
            }
            streamingFlag = false
            appliedSurface = null
            startPreviewLocked(cam, surfaceTexture)
        }
    }

    /** 必须在持有 [stateLock] 时调用。 */
    private fun armFirstFrameWatchdog() {
        previewWatchdog?.let { cameraHandler?.removeCallbacks(it) }
        val base = frameCounter.get()
        val watchdog = object : Runnable {
            override fun run() {
                if (!streamingFlag) return
                val frames = frameCounter.get() - base
                if (frames > 0) {
                    Log.i(TAG, "first-frame watchdog: got $frames frames")
                    return
                }
                // No automatic fps fallback: a capture card with no HDMI signal legitimately
                // produces no frames, and silently changing the user's fps would be wrong.
                Log.e(TAG, "camera opened but no frame received (check HDMI input / cable)")
                postError("摄像头已打开但收不到画面：请检查 HDMI 输入源/采集卡供电，或拔插后重试（Hagibis 无 HDMI 信号时不会输出画面）")
            }
        }
        previewWatchdog = watchdog
        cameraHandler?.postDelayed(watchdog, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun cancelFirstFrameWatchdog() {
        previewWatchdog?.let { cameraHandler?.removeCallbacks(it) }
        previewWatchdog = null
    }

    private fun startPreviewLocked(cam: UVCCamera, surface: SurfaceTexture) {
        try {
            cam.setPreviewTexture(surface)
            cam.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
            streamingFlag = true
            appliedSurface = surface
            cam.startPreview()
            armFirstFrameWatchdog()
            Log.i(
                TAG,
                "preview started: ${currentSize?.first}x${currentSize?.second} (NV21 frame callback)"
            )
        } catch (t: Throwable) {
            streamingFlag = false
            appliedSurface = null
            Log.e(TAG, "startPreview failed", t)
            try {
                cam.stopPreview()
            } catch (e: Throwable) {
                Log.w(TAG, "stopPreview after failure failed", e)
            }
            postError("启动 UVC 预览失败: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private val frameCallback = IFrameCallback { frame ->
        handleFrame(frame)
    }

    private val frameCounter = java.util.concurrent.atomic.AtomicLong()
    private var previewWatchdog: Runnable? = null

    /**
     * native 采集线程回调：只做「拷贝成独立数组 + 转发」，绝不做编码/IO。
     * 拷贝是必须的：callback 收到的 ByteBuffer 由 native 复用，且 StreamEngine 的编码队列
     * 会异步持有该帧数据。
     */
    private fun handleFrame(frame: ByteBuffer?) {
        if (frame == null || !streamingFlag) return
        val l = listener ?: return
        val size = currentSize ?: return
        val width = size.first
        val height = size.second
        if (width <= 0 || height <= 0 || (width and 1) != 0 || (height and 1) != 0) return
        try {
            val src = frame.duplicate()
            src.rewind()
            val expected = width * height * 3 / 2
            if (src.remaining() < expected) {
                Log.w(
                    TAG,
                    "frame too small: remaining=${src.remaining()} expected=$expected " +
                        "(${width}x$height)"
                )
                return
            }
            val data = ByteArray(expected) // 每帧独立新分配（StreamEngine 异步持有）
            src.get(data, 0, expected)
            val count = frameCounter.incrementAndGet()
            if (count % 60L == 0L) {
                // Helpful for diagnosing a capture card with no HDMI input (frames arrive but are black).
                var sum = 0L
                var n = 0
                var i = 0
                while (i < width * height) {
                    sum += (data[i].toInt() and 0xFF)
                    n++
                    i += 97
                }
                Log.i(TAG, "frames=$count luma=${if (n > 0) sum / n else 0} size=${width}x$height")
            }
            l.onFrame(UvcFrame(data, width, height, System.nanoTime() / 1000L))
        } catch (t: Throwable) {
            // native 线程绝不能抛异常。
            Log.w(TAG, "frame callback failed", t)
        }
    }

    // ------------------------------------------------------------------------------------
    // 分辨率
    // ------------------------------------------------------------------------------------

    private fun querySizes(cam: UVCCamera, frameFormat: Int): List<Pair<Int, Int>> {
        val list = try {
            cam.getSupportedSizeList(frameFormat)
        } catch (t: Throwable) {
            Log.w(TAG, "getSupportedSizeList(format=$frameFormat) failed", t)
            null
        } ?: return emptyList()
        val result = LinkedHashSet<Pair<Int, Int>>()
        for (size in list) {
            try {
                val w = size.width
                val h = size.height
                // NV21 / MediaCodec 都要求偶数宽高；奇数尺寸直接忽略。
                if (w > 0 && h > 0 && (w and 1) == 0 && (h and 1) == 0) {
                    result.add(w to h)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "read Size failed", t)
            }
        }
        return result.toList()
    }

    private fun choosePreviewSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int> {
        val w = desiredWidth
        val h = desiredHeight
        if (w > 0 && h > 0) {
            val want = w to h
            if (sizes.isEmpty() || sizes.contains(want)) return want
            Log.w(TAG, "requested preview size ${w}x$h unsupported; fallback to auto choice")
        }
        if (sizes.isEmpty()) return DEFAULT_SIZE
        for (preferred in PREFERRED_SIZES) {
            if (sizes.contains(preferred)) return preferred
        }
        return sizes
            .filter { it.first <= 1920 && it.second <= 1080 }
            .maxByOrNull { it.first.toLong() * it.second.toLong() }
            ?: sizes.maxByOrNull { it.first.toLong() * it.second.toLong() }
            ?: DEFAULT_SIZE
    }

    /** `setPreviewSize` 真正成功后的尺寸 + frame format。 */
    private data class AppliedPreview(val size: Pair<Int, Int>, val format: Int)

    /**
     * 尝试设置预览尺寸。部分摄像头/固件对 frame format 或 fps 区间挑剔，按组合依次降级。
     * @return 实际生效的尺寸与 format；全部失败返回 null（此时相机仍停留在 open() 的默认尺寸，
     *         调用方需用 [resolveActualPreviewSize] 取真实值，不能拿请求值当实际值）。
     */
    private fun applyPreviewSize(
        cam: UVCCamera,
        size: Pair<Int, Int>,
        preferredFormat: Int
    ): AppliedPreview? {
        val formats = if (preferredFormat == UVCCamera.FRAME_FORMAT_YUYV) {
            intArrayOf(UVCCamera.FRAME_FORMAT_YUYV, UVCCamera.FRAME_FORMAT_MJPEG)
        } else {
            intArrayOf(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV)
        }
        val fpsPairs = arrayOf(
            desiredFps to desiredFps,
            desiredFps.coerceAtLeast(1) to 60,
            1 to 31
        ).distinct()
        for (format in formats) {
            for ((minFps, maxFps) in fpsPairs) {
                try {
                    cam.setPreviewSize(
                        size.first, size.second, minFps, maxFps, format,
                        UVCCamera.DEFAULT_BANDWIDTH
                    )
                    Log.i(
                        TAG,
                        "setPreviewSize ${size.first}x${size.second} " +
                            "format=${formatName(format)} fps=$minFps-$maxFps ok"
                    )
                    return AppliedPreview(size, format)
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "setPreviewSize ${size.first}x${size.second} " +
                            "format=${formatName(format)} fps=$minFps-$maxFps failed: ${t.message}"
                    )
                }
            }
        }
        // 真机验证点：某些摄像头只接受 open() 时自动选择的默认尺寸。
        Log.w(
            TAG,
            "all setPreviewSize attempts failed for ${size.first}x${size.second} " +
                "(preferredFormat=${formatName(preferredFormat)})"
        )
        return null
    }

    /**
     * `setPreviewSize` 全部失败时推算相机**真实**尺寸。
     *
     * 注意：AAR 的 `UVCCamera.getPreviewSize()` 用 `||` 匹配（size.width == curW || size.height == curH），
     * 返回的尺寸可能是错的；所以先用候选列表校验，再退回「open() 默认 = 当前 format 支持列表第一个尺寸」。
     */
    private fun resolveActualPreviewSize(
        cam: UVCCamera,
        candidates: List<Pair<Int, Int>>,
        requested: Pair<Int, Int>
    ): Pair<Int, Int> {
        val reported = try {
            cam.getPreviewSize()?.let { it.width to it.height }
        } catch (t: Throwable) {
            Log.w(TAG, "getPreviewSize failed", t)
            null
        }
        if (reported != null && isEvenSize(reported) &&
            (candidates.isEmpty() || candidates.contains(reported))
        ) {
            Log.i(TAG, "actual preview size from camera: ${reported.first}x${reported.second}")
            return reported
        }
        candidates.firstOrNull()?.let {
            Log.i(TAG, "actual preview size fallback = first supported: ${it.first}x${it.second}")
            return it
        }
        Log.w(
            TAG,
            "actual preview size unknown, use requested ${requested.first}x${requested.second}"
        )
        return requested
    }

    private fun isEvenSize(size: Pair<Int, Int>): Boolean =
        size.first > 0 && size.second > 0 &&
            (size.first and 1) == 0 && (size.second and 1) == 0

    private fun formatName(format: Int): String = when (format) {
        UVCCamera.FRAME_FORMAT_MJPEG -> "MJPEG"
        UVCCamera.FRAME_FORMAT_YUYV -> "YUYV"
        else -> "format=$format"
    }

    private fun applyDesiredSizeIfPossible() {
        val cam = uvcCamera ?: return
        if (streamingFlag) {
            // 契约：setPreviewSize 下次打开生效；预览中不打断。
            Log.i(TAG, "setPreviewSize: previewing, new size applies on next open")
            return
        }
        val w = desiredWidth
        val h = desiredHeight
        if (w <= 0 || h <= 0) return
        val want = w to h
        val sizes = supportedSizeList
        if (sizes.isNotEmpty() && !sizes.contains(want)) {
            Log.w(TAG, "setPreviewSize: $want not in supported sizes, ignored")
            return
        }
        val oldFormat = currentFrameFormat
        val applied = applyPreviewSize(cam, want, oldFormat)
        if (applied == null) {
            Log.w(
                TAG,
                "setPreviewSize: apply failed, keep current " +
                    "${currentSize?.first}x${currentSize?.second}"
            )
            return
        }
        currentFrameFormat = applied.format
        currentSize = applied.size
        if (applied.format != oldFormat) {
            val newSizes = querySizes(cam, applied.format)
            if (newSizes.isNotEmpty()) supportedSizeList = newSizes
        }
        Log.i(
            TAG,
            "setPreviewSize applied before preview: " +
                "${applied.size.first}x${applied.size.second} format=${formatName(applied.format)}"
        )
        postOnCameraOpened(supportedSizeList)
    }

    // ------------------------------------------------------------------------------------
    // 设备枚举 / 工具
    // ------------------------------------------------------------------------------------

    private fun refreshDeviceCache(notify: Boolean) {
        val devices: List<UsbDevice> = try {
            usbMonitor?.getDeviceList()
                ?: usbManager?.deviceList?.values?.toList()
                ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "getDeviceList failed, fallback to UsbManager", t)
            try {
                usbManager?.deviceList?.values?.toList() ?: emptyList()
            } catch (e: Throwable) {
                emptyList()
            }
        }
        knownDevices.clear()
        for (device in devices) {
            val uvc = isUvcDevice(device)
            // Log every USB device (VID/PID/class/interface classes) even if it is not a UVC
            // camera: this is the first thing to check when the app reports "no camera".
            val ifaceClasses = try {
                (0 until device.interfaceCount).joinToString(",") { i ->
                    device.getInterface(i).interfaceClass.toString()
                }
            } catch (t: Throwable) {
                "?"
            }
            Log.i(
                TAG,
                "USB device: ${device.deviceName} vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)} class=${device.deviceClass} " +
                    "ifaceClasses=[$ifaceClasses] uvc=$uvc"
            )
            if (uvc) {
                knownDevices[device.deviceId] = device
            }
        }
        updateDeviceInfos()
        if (notify) postOnDeviceAttached(cachedDeviceInfos)
    }

    private fun updateDeviceInfos() {
        cachedDeviceInfos = knownDevices.values.map { toDeviceInfo(it) }
    }

    private fun hasPermission(device: UsbDevice): Boolean {
        val mgr = usbManager ?: return false
        return try {
            mgr.hasPermission(device)
        } catch (t: Throwable) {
            Log.w(TAG, "hasPermission failed", t)
            false
        }
    }

    /**
     * UVC 设备判定：
     * 1. deviceClass == VIDEO(14)；
     * 2. USB IAD 复合设备（class 239 / subclass 2 / protocol 1）——nExt Camera 的
     *    device_filter 就是用这组属性匹配 UVC 摄像头（见其 APK 的 res/xml/device_filter.xml）；
     * 3. 任一 interface class == VIDEO(14)。
     */
    private fun isUvcDevice(device: UsbDevice?): Boolean {
        device ?: return false
        try {
            if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
            if (device.deviceClass == USB_CLASS_MISC &&
                device.deviceSubclass == 2 &&
                device.deviceProtocol == 1
            ) return true
            val count = device.interfaceCount
            for (i in 0 until count) {
                try {
                    if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                        return true
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "getInterface($i) failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "isUvcDevice failed", t)
        }
        return false
    }

    private fun toDeviceInfo(device: UsbDevice): UvcDeviceInfo {
        val name = try {
            val product = device.productName
            if (!product.isNullOrBlank()) product else device.deviceName
        } catch (t: Throwable) {
            null
        } ?: "UVC ${device.vendorId.toString(16)}:${device.productId.toString(16)}"
        return UvcDeviceInfo(device.deviceId, device.vendorId, device.productId, name)
    }

    private fun deviceLabel(device: UsbDevice): String =
        try {
            device.productName ?: device.deviceName
        } catch (t: Throwable) {
            "usb#${device.deviceId}"
        }

    @Suppress("DEPRECATION")
    private fun usbDeviceExtra(intent: Intent): UsbDevice? =
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    // ------------------------------------------------------------------------------------
    // 回调分发 / 异常保护
    // ------------------------------------------------------------------------------------

    /**
     * 获取（必要时创建）camera 线程。新线程会挂一个 1s 的 idle-quit：
     * 如果期间没有 start()，线程自动退出，避免「只调了 setPreviewSize/构造了 controller」就泄漏线程。
     */
    private fun ensureCameraHandler(): Handler {
        synchronized(handlerLock) {
            cameraHandler?.let { return it }
            val thread = HandlerThread("UvcController").apply { start() }
            val handler = Handler(thread.looper)
            cameraThread = thread
            cameraHandler = handler
            handler.postDelayed(idleQuitRunnable, IDLE_QUIT_DELAY_MS)
            Log.d(TAG, "camera thread started")
            return handler
        }
    }

    /** 提交到 camera 线程（不存在则创建）；用于 start/stop 和显式用户操作。 */
    private fun postToCamera(block: () -> Unit) {
        ensureCameraHandler().post { runCameraTask(block) }
    }

    /** 已启动（线程存在）时才提交；用于 attachPreview/setPreviewSize 这类可提前缓存的调用。 */
    private fun postToCameraIfActive(block: () -> Unit) {
        val handler = cameraHandler ?: return
        handler.post { runCameraTask(block) }
    }

    /** 所有 cameraThread 任务都包一层，避免未捕获异常杀死 HandlerThread。 */
    private fun runCameraTask(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "camera task failed", t)
            postError("UVC 内部错误: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private inline fun safeListener(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "listener callback threw", t)
        }
    }

    private fun postOnDeviceAttached(devices: List<UvcDeviceInfo>) {
        val copy = devices.toList()
        mainHandler.post {
            val l = listener ?: return@post
            safeListener { l.onDeviceAttached(copy) }
        }
    }

    private fun postOnDeviceDetached(device: UvcDeviceInfo) {
        mainHandler.post {
            val l = listener ?: return@post
            safeListener { l.onDeviceDetached(device) }
        }
    }

    private fun postOnCameraOpened(sizes: List<Pair<Int, Int>>) {
        val copy = sizes.toList()
        mainHandler.post {
            val l = listener ?: return@post
            safeListener { l.onCameraOpened(copy) }
        }
    }

    private fun postError(message: String) {
        Log.e(TAG, message)
        mainHandler.post {
            val l = listener ?: return@post
            safeListener { l.onCameraError(message) }
        }
    }

    /**
     * USBMonitor 的 listener：本实现不使用 USBMonitor.register()，因此这里几乎不会被调用；
     * 只有 `UsbControlBlock.close()`（native camera 关闭 clone / 我们主动 close）时会同步触发
     * `onDisconnect`。这里只记日志，绝不做重入的相机操作。
     */
    private val monitorListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            Log.d(TAG, "USBMonitor.onAttach: ${device?.deviceId}")
        }

        override fun onDetach(device: UsbDevice?) {
            Log.d(TAG, "USBMonitor.onDetach: ${device?.deviceId}")
        }

        override fun onConnect(
            device: UsbDevice?,
            ctrlBlock: USBMonitor.UsbControlBlock?,
            createNew: Boolean
        ) {
            Log.d(TAG, "USBMonitor.onConnect: ${device?.deviceId} createNew=$createNew")
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            Log.d(TAG, "USBMonitor.onDisconnect: ${device?.deviceId}")
        }

        override fun onCancel(device: UsbDevice?) {
            Log.d(TAG, "USBMonitor.onCancel: ${device?.deviceId}")
        }
    }

    private companion object {
        const val TAG = "UvcController"

        /** UsbConstants.USB_CLASS_MISC (0xEF); the public constant is hidden in the SDK. */
        const val USB_CLASS_MISC = 0xEF
        const val MIN_FPS = 30
        const val MAX_FPS = 30

        /** 授权成功后若 openDevice 因权限未刷新失败，延迟重试一次。 */
        const val GRANT_RETRY_DELAY_MS = 400L

        /** If no frame arrives in this time, report a "no signal" hint to the UI. */
        const val FIRST_FRAME_TIMEOUT_MS = 4_000L
        const val GRANT_RETRY_MAX = 1

        /** camera 线程空闲多久后退出（避免 Activity 重建/反复 start-stop 泄漏线程）。 */
        const val IDLE_QUIT_DELAY_MS = 1_000L

        val DEFAULT_SIZE = 1280 to 720
        val PREFERRED_SIZES = listOf(
            1280 to 720,
            1920 to 1080,
            960 to 540,
            640 to 480,
            1024 to 768,
            800 to 600
        )
    }
}
