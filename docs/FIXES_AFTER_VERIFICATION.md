# 独立验收发现的问题与修复记录

独立验收报告见 [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md)（**修复前快照**，保留原始结论用于追溯）。
本文件记录针对该报告的修复与当前状态（2026-10-25）。

## 真机反馈 #1（2026-10-25）：插入 USB 摄像头后完全探测不到

**根因**：构建时只 vendor 了 `libuvc.aar`，漏掉其 POM 声明的运行时依赖 `libuvccommon.aar`（提供 `com.serenegiant.utils.HandlerThreadHandler`、`BuildCheck`、`PermissionCheck` 等）与 `com.elvishew:xlog`。`USBMonitor` 构造函数第一行就调用 `HandlerThreadHandler.createHandler()`，运行时抛 `NoClassDefFoundError`，被 `UvcController.start()` 捕获后只上报「初始化 USB 摄像头失败」，因此设备列表始终为空。

**修复（v1.0.1）**：
- 新增 `app/libs/libuvccommon.aar`（与 libuvc 同版本 3.2.7，无重复类）；
- 增加 `com.elvishew:xlog:1.11.0` 依赖；
- `UvcController.refreshDeviceCache()` 增加逐设备日志：`USB device: ... vid=... pid=... class=... ifaceClasses=[...] uvc=true/false`（`adb logcat -s UvcController` 可直接判断系统是否枚举到设备、class 是否为 14）；
- 用 `apkanalyzer dex packages` 校验 APK 中已包含 `com.serenegiant.utils.HandlerThreadHandler`、`BuildCheck`、`PermissionCheck` 与 `com.elvishew.xlog` 类。

**验证**：重新构建 debug/release 通过，**130 单测全绿**，Lint 0 error（另补 `POST_NOTIFICATIONS` 声明以消掉 vendored `NotificationBuilder` 的 lint error，App 自身不弹通知），APK dex 校验确认包含 `HandlerThreadHandler`/`BuildCheck`/`PermissionCheck`/`XLog`。真机行为（插入后枚举/授权/打开）仍需用户实测。

### 真机验证（2026-10-25，红魔 NX789J / Android 15 / Hagibis USB 采集卡）

通过无线调试（Windows adb）在真实设备上验证并修复：

1. **v1.0.6 → v1.0.7 根因确认**：系统日志出现
   `UsbUserPermissionManager: Camera permission required for USB video class devices`。
   Android 12+ 要求 App 持有 CAMERA 才能给 UVC 设备授予 USB 权限；此前 v1.0.1 删掉了 CAMERA，导致 `requestPermission()` 立即返回 `granted=false` 且不弹系统框。v1.0.7 加回 CAMERA 并在启动时申请，真机上 USB 授权弹窗正常出现，点允许后相机成功打开：
   `setPreviewSize 1280x720 format=MJPEG fps=... ok` / `preview started` / `libUVCCamera PIXEL_FORMAT_NV21`。
2. **USB 插入/选择应用恢复**：v1.0.6 修复（onNewIntent/onResume 强制重新枚举并打开）。
3. **B 站 App 登录**：v1.0.11 修复包可见性误判后，真机验证：生成二维码登录 URL → 自动打开 B 站 App 的 `MWebActivity`「哔哩哔哩扫码登录」确认页 → 确认后 App 收到登录成功（UID 与直播间号（已脱敏），SessionStore 已保存）。无需第二台设备扫码。
4. **帧率**：v1.0.10 起支持 15/20/25/30/50/60fps（此前 30fps 是代码限制）；设置界面可选，保存后自动 `reconfigure` 重开相机（真机日志 `setPreviewSize ... fps=60-60 ok`）。
5. **竖屏/dialog 布局**：v1.0.5 改竖屏；v1.0.8 修复设置/登录/人脸验证对话框宽度塌缩（window.setLayout 92% 屏宽）。
6. **无 HDMI 信号**：采集卡无 HDMI 输入时打开成功但无帧，v1.0.12 增加提示“摄像头已打开但收不到画面，请检查 HDMI 输入源/采集卡供电”（4 秒无帧触发），不再误判为带宽问题（移除 60→30 自动降级）。

**仍未验证**：HDMI 源接入后的编码、真实 B 站开播/推流（登录已通，但未在用户账号上开播）；需要用户接上 HDMI 源后再测。

### v1.0.6：修复录屏暴露的 USB 授权状态恢复问题

用户录屏（Hagibis USB 采集卡）显示：App 已识别到 1 个 UVC 设备，插入时系统弹出「为 USB 设备选择一个应用」（nExt Camera 与本 App 都声明了 `USB_DEVICE_ATTACHED`），选择后 App 仍显示「USB 摄像头权限被拒绝」。根因：`MainActivity.onNewIntent()` 只调用了幂等的 `UvcController.start()`（已启动时立即 return），没有重新枚举/请求权限/打开设备。

修复：
- `UvcController.refreshDevicesAndOpen(requestPermissionIfNeeded)`：强制重新枚举；已授权则直接 open；未授权且允许请求时重新 `requestPermission()`；
- `onNewIntent`（USB attach）调用 refresh(true)，`onResume` 调用 refresh(false)（用于系统选择应用/授权框关闭后恢复）；
- 若 App 由 `USB_DEVICE_ATTACHED` 启动，`onCreate` 延迟 800ms 再 refresh(true)；
- 提示条增加引导：「如果出现为 USB 设备选择一个应用，请选择 BiliUVC Live 并点始终」。

### v1.0.5：去掉信息弹窗、改为竖屏

用户反馈「App 的弹窗覆盖了系统 USB 授权弹窗」。v1.0.5：
- 所有**信息类**提示/错误/诊断从 `AlertDialog`/`Toast` 改为页面内**非模态消息条** `tvBanner`（可点按关闭，自动消失；长文本可滚动）；
- 去掉开播确认、停播确认、开播失败、USB 授权失败、登录账号菜单等弹窗；开播/停播直接执行；返回键在直播中改为「再按一次返回停止」提示条；
- 保留登录扫码、设置、人脸验证这三个**用户主动触发的功能对话框**（非信息展示）；
- 屏幕方向从 `sensorLandscape` 改为 `portrait`（manifest `screenOrientation="1"`），底部按钮改为等宽自适应，设置对话框宽度改为 340dp 适配竖屏。

### v1.0.4：对照 nExt Camera 修正 USB 识别方式

用户要求先参考 nExt Camera 的实现（分析见 [NEXT_CAMERA_NOTES.md](NEXT_CAMERA_NOTES.md)）。关键发现：nExt 的 `device_filter.xml` 用 **USB IAD class 239/subclass 2/protocol 1** 匹配 UVC 摄像头，并有 `USB_DEVICE_ATTACHED` intent-filter + 跳板 Activity；底层通过 `UsbManager.openDevice().getFileDescriptor()` 交给 libusb。v1.0.4 改动：
- `isUvcDevice()` 增加 IAD（239/2/1）判定，不再只认 class 14/interface 14；
- MainActivity 增加 `USB_DEVICE_ATTACHED` intent-filter 和 `device_filter.xml`（IAD + class 14），插入摄像头自动拉起 App；
- 新增「USB 诊断 / 请求授权」按钮与长按诊断弹窗（列出所有枚举到的 USB 设备的 VID/PID/class/interface/授权状态）；
- 启动 USB 引擎不再依赖麦克风权限（麦克风只影响音频）。

### v1.0.2：USB 授权被拒绝后的恢复路径

用户反馈拒绝授权后无法继续。v1.0.2 增加：
- `UvcController.retryUsbPermission()` / `StreamEngine.retryUsbPermission()`：重新枚举并调用 `UsbManager.requestPermission()`，系统会再次弹出 USB 授权框；
- `MainActivity.onCameraError` 识别「权限被拒绝」时弹出可操作对话框（「重新授权」/「取消」），并说明这是 USB 设备授权、不是相机权限；
- 版本 `1.0.2`（versionCode 3）。产物：`dist/BiliUVC-Live-1.0.2.apk`（5.5 MB，sha256 `96eb1cab…3d6f59`）、debug（6.5 MB，`de41ede1…e8b5`）。

### 当前产物（v1.0.5）

`dist/BiliUVC-Live-1.0.5.apk`（5.5 MB，sha256 `8fc6a495…cdab`）、debug（6.5 MB，`f98188fa…5948`）；130 单测通过，Lint 0 error。

## P0（阻断真机核心链路）

| # | 问题 | 状态 | 修复说明 |
|---|---|---|---|
| P0-1 | `UvcController` USB 授权 PendingIntent 使用 `FLAG_IMMUTABLE`，Android 12+ 系统 fill-in `EXTRA_DEVICE` 被丢弃 → 首次授权后摄像头不打开 | ✅ 已修复 | API 31+ 改用 `FLAG_MUTABLE or FLAG_UPDATE_CURRENT`；grant 后跳过 `hasPermission()` 复查并直接 open，SecurityException 400ms 重试一次；deny/填充丢失有明确错误上报 |
| P0-2 | 人脸验证分支不复位 `startInProgress`，自动/手动重试被 `return` 拦截 | ✅ 已修复 | `handleStartLiveResult(FaceAuthRequired)` 置 `startInProgress=false` 并保持按钮禁用；轮询命中/「重试」按钮均可再次调用 `startLiveAsync`（`MainActivity.kt`） |

## P1（真机稳定性/状态机）

| # | 问题 | 状态 | 修复说明 |
|---|---|---|---|
| P1-1 | 停止直播后登录/设置按钮永久禁用 | ✅ 已修复 | `resetIdleUi()` 恢复 `btnLogin`/`btnSettings` 可用 |
| P1-2 | 停止链路在主线程 `join`（最坏 ~8s）有 ANR 风险 | ✅ 已修复 | `StreamEngine.stopStreaming()` 立即切换状态，编码器/推流器/相机停止移到后台 `cleanupExecutor` 串行执行；主线程只更新 UI |
| P1-3 | `onDestroy` 的 `shutdownNow()` 可能丢弃已排队的 B 站停播请求 | ✅ 已修复 | `onDestroy` 改为 `io.shutdown()`（允许已排队任务执行），并先释放引擎；`onStop` 已排队 stopLive |
| P1-4 | Home 打断开播的竞态：迟到的 `startLive` 成功回调仍会开始推流 | ✅ 已修复 | 增加 `liveAttemptId` 会话代次：`stopLiveInternal()` 自增，回调按代次丢弃；`handleStartLiveResult` 入口再校验 `stopping/isFinishing/isDestroyed`，迟到成功会补发 `stopLive` |
| P1-5 | 网络抖动导致 `nav()` 返回 null 时清空登录态 | ✅ 已修复 | `refreshRoomInfo` 不再因一次失败清除会话；仅真正的开播失败会提示，会话保留 |
| P1-6 | 摄像头错误时 RTMP 连接继续（有推流无画面） | ✅ 已修复 | `StreamEngine.onCameraError` 若正在直播则 `stopStreaming()` 并上报错误，Activity 走停播流程 |

## P2（建议项，本次处理）

| # | 问题 | 状态 | 说明 |
|---|---|---|---|
| P2-1 | 多余 CAMERA 权限导致拒绝相机即不可用 | ✅ 已修复 | UVC 走 USB Host API，删除 `CAMERA` 权限与请求；只申请 `RECORD_AUDIO`，拒绝时以无声视频继续直播 |
| P2-2 | `WAKE_LOCK` / `ACCESS_NETWORK_STATE` 未使用 | ✅ 已修复 | 从 Manifest 删除 |
| P2-5 | `LoginDialog` 成功路径 executor 泄漏 | ✅ 已修复 | 成功分支显式 `shutdownNow()` |
| P2-6 | `UvcController` HandlerThread 从不退出 | ✅ 已修复 | 线程改为懒创建 + 1s 空闲 `quitSafely()`（短时 stop→start 复用），再次 start 自动重建 |
| P2-7 | 拿不到直播间分区仍以 `area_v2=0` 开播 | ✅ 已修复 | 开播前强制刷新 `roomInfo`；`areaId<=0` 直接提示「请刷新直播间信息后重试」，不发 startLive |
| P2-8 | UVC 实际 frame format 记录错误 | ✅ 已修复 | 以 `applyPreviewSize` 实际生效的 format 回写，降级后重新枚举尺寸 |
| P2-9 | 分辨率回退后 `currentSize` 未更新 → 编码尺寸错/丢帧 | ✅ 已修复 | 新增 `AppliedPreview(size, format)`；全失败时按实际 `getPreviewSize()`/候选尺寸推算真实尺寸并在 `onCameraOpened` 前发布 `currentSize` |
| P2-11 | 开播使用登录时缓存的旧 areaId | ✅ 已修复 | 每次开播前刷新 `roomInfo` |

未处理（当前作为原型可接受，已写入 README 限制）：
- P2-3 每帧 1.4MB 分配（GC 抖动，真机观察）
- P2-4 release 使用仓库内测试 keystore（发布前必须替换，README 已提示）
- P2-10 改分辨率固定 250ms 延时重开（真机验证时观察）
- P2-12 长时直播/断线重连策略

## P3（文档一致性）

全部已修复：`FlvWriter.kt` 引用、`connect` 对象参数（代码实际 `audioCodecs=3575`，无 `capabilities`）、logcat tag（`UvcController/H264Encoder/AacEncoder`）、README「已联调」范围限定为 generate/Area/版本/房间信息、「多摄像头」改为语义准确描述、「幽灵直播间」措辞加风险提示；删除 `AppPrefs` 中未使用的 `lastCameraId/autoReconnect`。

## 修复后的验证

- `:app:testDebugUnitTest`：**130 tests / 0 fail**（15 个测试类；修复未改变纯 JVM 协议逻辑）
- `:app:assembleDebug` / `:app:assembleRelease`：通过；产物 5.0 MB / 6.2 MB，包名 `com.biliuvc.live`、minSdk 26、targetSdk 34、arm64-v8a + armeabi-v7a 各 4 个 native 库
- `:app:lintDebug`：通过（0 error；`AacEncoder` 的 `AudioRecord` 加了 `@SuppressLint("MissingPermission")` 并附运行时权限说明，实际权限由 MainActivity 申请、`runAudioLoop` 捕获 SecurityException）
- 修复后重新打包的 APK SHA-256：release `0f2f39c8…22114`，debug `fec960ad…f97d`
- **仍未实机验证**：USB 授权（P0-1 修复后需真机确认）、UVC 编码、真实 B 站推流、人脸验证。请按 [HARDWARE_TEST.md](HARDWARE_TEST.md) 冒烟。

## 残余风险（按优先级）

1. MediaCodec `COLOR_FormatYUV420SemiPlanar` 兼容性（绿屏/花屏/configure 失败）——真机最可能出问题点
2. UVC `PIXEL_FORMAT_NV21` 输出布局、分辨率协商/回退
3. TextureView 销毁时序与改分辨率重开
4. 停止链路改为后台后，极端情况下 App 被立即杀死时 stopLive 仍可能丢失（B 站服务端会超时）
5. 人脸 60043 依赖 B 站官方风控页，不保证自动完成
6. B 站开播接口为非官方接口，随时可能变更
