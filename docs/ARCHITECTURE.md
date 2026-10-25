# BiliUVC Live — 架构与协作规范

> 目标：Android APK，用 USB(UVC) 摄像头直接推流到 Bilibili 直播间，弥补官方直播方式无法选择 USB 摄像头的问题。
>
> 参考实现：
> - `ref/bilibili-stream-for-obs-main/`（C++ OBS 插件，含完整 B 站扫码登录 / 开播 / 人脸验证 / 停播 API 调用，`src/bilibili_api.cpp` 为权威参考）
> - `ref/auc/`（AndroidUSBCamera 3.2.7 源码，`libuvc` 为 saki4510t UVCCamera 的 Java 层 + JNI 库，本仓库只 vendor 其 `libuvc.aar`）
> - nExt Camera - USB（商业闭源，仅作产品形态参考：UVC 预览、录制、推流）

## 1. 环境

- 项目：`bili-uvc-live`（GitHub: https://github.com/test1122-1122/bili-uvc-live）
- JDK 17，Gradle 8.7（`./gradlew`），Android SDK `compileSdk 34 / minSdk 26 / targetSdk 34`
- **必须**设置环境变量后再构建（否则 Gradle 找不到可写目录 / SDK）：

```bash
cd bili-uvc-live
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew --no-daemon :app:compileDebugKotlin
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

- 本项目已在真机（红魔 NX789J / Android 15 + Hagibis USB 采集卡）上通过无线 adb 验证：USB 授权/相机打开、B 站 App 同机登录。编码与真实推流需接入 HDMI 输入源后再验证。
- 项目已 vendor `app/libs/libuvc.aar`（Apache-2.0；内含 libusb/libusb/libuvc/libjpeg-turbo，arm64-v8a + armeabi-v7a，**无 x86**）。
- Kotlin 1.9.0，**不要引入协程依赖**（用 `Thread`/`ExecutorService` + Handler 回调）。已有依赖：appcompat、core-ktx、constraintlayout、okhttp 4.12、zxing core 3.5.3、junit4、mockwebserver。

## 2. 模块分工（文件所有权，禁止跨界修改）

| 目录 | 负责人 | 内容 |
|---|---|---|
| `app/src/main/java/com/biliuvc/live/rtmp/**` | rtmp-dev | 纯 JVM RTMP 客户端、FLV/AMF0/H264 Annex-B 封装 |
| `app/src/main/java/com/biliuvc/live/bili/**` | bili-dev | B 站 HTTP API、扫码登录、开播/停播/人脸验证、会话持久化 |
| `app/src/main/java/com/biliuvc/live/capture/**` | capture-dev | UVC 摄像头控制、H.264/AAC 硬编码 |
| `app/src/main/java/com/biliuvc/live/live/**`、`ui/**`、`data/**`、`app/src/main/res/**`、Gradle 文件、`docs/**`、`README.md` | lead | 编排、UI、设置、构建、APK、文档、集成测试 |
| `app/src/test/java/com/biliuvc/live/rtmp/**` | rtmp-dev | RTMP/FLV/AMF 测试 + Mock RTMP 服务器 |
| `app/src/test/java/com/biliuvc/live/bili/**` | bili-dev | B 站 API MockWebServer 测试、签名测试 |
| `app/src/test/java/com/biliuvc/live/**`（其它） | lead | 集成/工具测试 |

公共模型 `data/StreamConfig.kt` 由 lead 提供，`rtmp/bili/capture` 可直接引用或调用；不要修改它（有需要发消息给 lead）。

## 3. 数据流

```
UVC camera (USBMonitor/UVCCamera, NV21 frames)
   └─> H264Encoder (MediaCodec, NV21→NV12, 视频 H.264 Annex-B)
   └─> (手机麦克风) AacEncoder (AudioRecord + MediaCodec, raw AAC)
          └─> RtmpPublisher (FLV over RTMP, 自动发 AVC/AAC sequence header)
                 └─> B 站 RTMP 地址（startLive 返回 addr + code）
UI: MainActivity (TextureView 预览 + 状态/设置/登录/开播) ──> BiliApi (OkHttp) ──> B 站 HTTP API
```

线程模型：UI 主线程只做展示；所有网络/编码/推流在后台线程；组件通过 listener 回调（回调到主线程或工作线程由组件说明）。

## 4. 对外 API 契约（实现必须严格遵守，签名可直接复制）

### 4.1 `rtmp`（rtmp-dev）

```kotlin
package com.biliuvc.live.rtmp

enum class RtmpState { IDLE, CONNECTING, CONNECTED, PUBLISHING, STOPPED, ERROR }

data class RtmpStats(
    val state: RtmpState,
    val publishing: Boolean,
    val durationMs: Long,
    val videoFramesSent: Long,
    val audioFramesSent: Long,
    val droppedVideoFrames: Long,
    val droppedAudioFrames: Long,
    val bytesSent: Long,
    val bitrateBps: Long,
    val lastError: String? = null
)

data class RtmpTarget(
    val scheme: String, val host: String, val port: Int,
    val app: String, val streamName: String, val tcUrl: String, val originalUrl: String
)

/** B 站 startLive 返回 addr(`rtmp://live-push.bilivideo.com/live-bvc/`) + code(`?streamname=..&key=..`)；也支持整条 URL。 */
object RtmpTargetParser {
    fun fromParts(addr: String, code: String): RtmpTarget?   // 拼接：addr.trimEnd('/') + "/" + code（保留 code 前的 '?'）
    fun parse(url: String): RtmpTarget?                      // 解析整条推流 URL；app=第一段 path，streamName=其余部分(含 query，含前导 '?')
}

class RtmpPublisher(private val listener: Listener) {
    interface Listener {
        fun onState(state: RtmpState, detail: String?)
        fun onStats(stats: RtmpStats)
        fun onError(message: String, cause: Throwable?)
    }
    fun setVideoInfo(width: Int, height: Int, fps: Int)      // 可选，用于 onMetaData
    fun setAudioInfo(sampleRate: Int, channels: Int)         // 默认 44100/2
    fun setAudioSpecificConfig(asc: ByteArray)               // 可选；AAC encoder 的 codec-config
    fun connect(url: String)                                 // 异步：handshake/connect/createStream/publish
    fun disconnect()                                         // 幂等；关闭 socket + 线程
    fun sendVideoFrame(annexB: ByteArray, ptsUs: Long)       // 线程安全；自动提取 SPS/PPS，发 AVCDecoderConfigurationRecord；非关键帧丢弃可选
    fun sendAudioFrame(rawAac: ByteArray, ptsUs: Long)       // 原始 AAC(无 ADTS)；首帧前发 AAC sequence header
    val stats: RtmpStats
    val isPublishing: Boolean
}
```

硬性要求：
- 纯 JVM（可被 JUnit 直接测试，**禁止 `android.*`**）。
- 标准 simple handshake；AMF0 `connect`/`createStream`/`publish`；处理 `_result`、`onStatus`、`SetChunkSize`、`WindowAckSize`、`SetPeerBandwidth`、`UserControl(PingRequest→Pong)`、Ack。
- publish 命令的 stream name **必须保留 B 站 code 中的前导 `?`**（与 OBS 行为一致）。
- 媒体消息 FLV 封装：video tag `0x17/0x27 0x01 + compositionTime(0) + [4字节 NAL 长度 + NAL]*`；AVC sequence header `0x17 0x00 + AVCDecoderConfigurationRecord`；audio tag `0xAF 0x00/0x01 + AAC`；首帧前发 `@setDataFrame onMetaData`（必要时）。
- 发送分块支持 >128 字节（out chunk size 4096），fmt0+fmt3 续块，timestamp 单调；超长 delta 用扩展时间戳。
- 发送满/未连接时丢帧计数，不阻塞编码线程；断线回调 ERROR。
- 每个公开方法线程安全、不抛异常。
- `disconnect()` 后 stats 状态 STOPPED，线程退出。

### 4.2 `bili`（bili-dev）

```kotlin
package com.biliuvc.live.bili

data class BiliSession(val cookies: String, val uid: Long, val uname: String, val roomId: Long) {
    val csrf: String                    // cookie bili_jct
    fun cookieHeader(): String
}

data class QrTicket(val url: String, val key: String)
sealed class QrPollResult {
    object Pending : QrPollResult(); object Scanned : QrPollResult(); object Expired : QrPollResult()
    data class Success(val cookies: String, val uid: Long) : QrPollResult()
    data class Failure(val message: String) : QrPollResult()
}
data class UserInfo(val uid: Long, val uname: String)
data class RoomInfo(val roomId: Long, val title: String, val parentAreaId: Int, val areaId: Int,
                    val parentAreaName: String, val areaName: String, val liveStatus: Int)
data class Area(val id: Int, val name: String)
data class AreaGroup(val id: Int, val name: String, val subAreas: List<Area>)
sealed class StartLiveResult {
    data class Success(val addr: String, val code: String) : StartLiveResult()
    data class FaceAuthRequired(val faceAuthCode: Int, val qrContent: String) : StartLiveResult()
    data class Failure(val message: String) : StartLiveResult()
}

class BiliApi {
    fun generateQr(): QrTicket
    fun pollQr(key: String): QrPollResult
    fun nav(cookies: String): UserInfo?                  // null = 未登录
    fun roomIdByUid(cookies: String, uid: Long): Long?   // null/0 = 未开通直播间
    fun roomInfo(cookies: String, roomId: Long): RoomInfo?
    fun areaGroups(): List<AreaGroup>
    fun startLive(cookies: String, csrf: String, roomId: Long, areaId: Int, uid: Long): StartLiveResult
    fun isFaceAuthDone(cookies: String, csrf: String, roomId: Long, faceAuthCode: Int): Boolean?
    fun stopLive(cookies: String, csrf: String, roomId: Long): Boolean
    fun updateRoomTitle(cookies: String, csrf: String, roomId: Long, title: String): Boolean
    fun liveStatus(uid: Long): Int?                       // 0 未开播 / 1 直播中 / null 失败
}

object AppSigner { fun sign(params: LinkedHashMap<String, String>): String }  // appkey=aae92bc66f3edfab, appsecret=af125a0d5279fd576c1b4418a3e8276d

class SessionStore(context: Context) {   // 唯一允许使用 android.* 的文件；SharedPreferences JSON
    fun load(): BiliSession?; fun save(s: BiliSession); fun clear()
}
```

硬性要求（参考 `ref/bilibili-stream-for-obs-main/src/bilibili_api.cpp`）：
- 纯 JVM 可测（除 `SessionStore`），HTTP 用 OkHttp；测试用 MockWebServer。
- 请求头：`Accept: application/json, text/plain, */*`、`Content-Type: application/x-www-form-urlencoded; charset=UTF-8`、`Origin: https://link.bilibili.com`、`Referer: https://link.bilibili.com/p/center/index`、桌面 Chrome UA、Cookie。
- 扫码：`GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate` → `data.url`/`data.qrcode_key`；轮询 `.../qrcode/poll?qrcode_key=`，`data.code`: 0 成功 / 86038 过期 / 86090 已扫 / 86101 等待。成功从响应 `Set-Cookie` 收集 `SESSDATA`、`bili_jct`、`DedeUserID` 等；若不全，GET `data.url` 补票。cookie 字符串格式 `a=b; c=d`。
- `startLive`：先 `GET https://api.live.bilibili.com/xlive/app-blink/v1/liveVersionInfo/getHomePageLiveVersion?system_version=2&ts=..&appkey=..&sign=..` 取 `build`/`curr_version`；再 `POST https://api.live.bilibili.com/room/v1/Room/startLive`，参数 `room_id, platform=pc_link, area_v2, backup_stream=0, csrf_token, csrf, build, version, ts` + `appkey`+`sign`；成功取 `data.rtmp.addr`/`data.rtmp.code`；`code=60024/60028` → `data.qr` 人脸二维码；`code=60043` → 使用 `https://www.bilibili.com/blackboard/live/face-auth-middle.html?source_event=400&mid=<uid>`；其它 code 返回 `message`。
- 人脸轮询：`POST https://api.live.bilibili.com/xlive/app-blink/v1/preLive/IsUserIdentifiedByFaceAuth`，参数 `room_id, face_auth_code, csrf_token, csrf`，`data.is_identified` 为真（bool 或 1/字符串）。
- 停播：`POST .../room/v1/Room/stopLive` 参数 `room_id, platform=pc_link, csrf_token, csrf`。
- 房间号：`GET .../room/v2/Room/room_id_by_uid?uid=`；房间信息：`GET .../xlive/web-room/v1/index/getRoomBaseInfo?req_biz=web_room_componet&room_ids=`；分区：`GET .../room/v1/Area/getList`；标题：`POST .../room/v1/Room/update`。
- 签名：参数（含 `appkey`）按 key 排序 → `k=v&...` 拼 `appsecret` → MD5 hex → 追加 `sign=`；POST body 为 `k=v&...&appkey=..&sign=..`（form-urlencoded）。
- 网络错误/异常 → 返回 null/Failure，不崩溃；超时 10s。

### 4.3 `capture`（capture-dev）

```kotlin
package com.biliuvc.live.capture

data class UvcDeviceInfo(val deviceId: Int, val vendorId: Int, val productId: Int, val name: String)
data class UvcFrame(val nv21: ByteArray, val width: Int, val height: Int, val timestampUs: Long)

class UvcController(context: Context) {
    interface Listener {
        fun onDeviceAttached(devices: List<UvcDeviceInfo>)
        fun onDeviceDetached(device: UvcDeviceInfo)      // 主线程
        fun onCameraOpened(supportedSizes: List<Pair<Int, Int>>)  // 主线程
        fun onCameraError(message: String)               // 主线程
        fun onFrame(frame: UvcFrame)                     // USB 线程，勿做耗时操作
    }
    fun setListener(l: Listener)
    fun start()                       // 注册 USBMonitor；已有授权设备自动打开；无设备则等待插拔
    fun stop()                        // 停止预览/关闭摄像头/注销 monitor
    fun devices(): List<UvcDeviceInfo>
    fun selectDevice(deviceId: Int)
    fun setPreviewSize(width: Int, height: Int)   // 下次打开生效
    fun attachPreview(surfaceTexture: SurfaceTexture)  // TextureView 就绪后调用，开始 UVC 预览渲染
    fun detachPreview()
    fun isStreaming(): Boolean
    fun supportedSizes(): List<Pair<Int, Int>>
    fun requestUsbPermission(device: UvcDeviceInfo)
}

class H264Encoder(
    width: Int, height: Int, fps: Int, bitrateBps: Int,
    private val listener: Listener
) {
    interface Listener { fun onEncoded(annexB: ByteArray, ptsUs: Long); fun onError(message: String) }
    fun start()
    fun encodeNv21Frame(nv21: ByteArray, ptsUs: Long)   // 非阻塞；队列满丢最旧帧
    fun requestKeyFrame()
    fun stop()
    val droppedFrames: Long
}

class AacEncoder(
    sampleRate: Int = 44100, channels: Int = 2, bitrateBps: Int = 128_000,
    private val listener: Listener
) {
    interface Listener {
        fun onConfig(asc: ByteArray)                       // MediaCodec codec-config (AudioSpecificConfig)
        fun onEncoded(rawAac: ByteArray, ptsUs: Long)      // 无 ADTS
        fun onError(message: String)
    }
    fun start()   // 需要 RECORD_AUDIO 已授权
    fun stop()
}
```

硬性要求：
- `UvcController` 基于 `app/libs/libuvc.aar`：`com.serenegiant.usb.USBMonitor` / `UVCCamera` / `IFrameCallback`。参考 `ref/auc/libausbc/src/main/java/com/jiangdg/ausbc/camera/CameraUvcStrategy.kt`（Apache-2.0）的完整用法：`USBMonitor.OnDeviceConnectListener` → `requestPermission` → `onConnect` 拿 `UsbControlBlock` → `UVCCamera().open(ctrlBlock)` → `setPreviewSize(w,h,minFps=30,maxFps=30,FRAME_FORMAT_MJPEG)` → `setPreviewTexture(st)` → `startPreview()` → `setFrameCallback(cb, UVCCamera.PIXEL_FORMAT_NV21)`。
- USB 权限申请：`USBMonitor.requestPermission(device)`；系统对话框由框架处理；断连时 `stopPreview/destroy/close ctrlBlock`。
- 帧回调 `onFrame` 里只做引用传递/入队，**不要**在回调线程做编码/IO。
- `H264Encoder`：`MediaFormat` MIME `video/avc`，`COLOR_FormatYUV420SemiPlanar`，`KEY_I_FRAME_INTERVAL=2`，`KEY_FRAME_RATE=fps`，`KEY_BIT_RATE=bitrateBps`；NV21→NV12 转换（Y 拷贝 + 色度 U/V 交换）；输出回调 Annex-B（含 codec-config buffer）；PTS 用传入 `ptsUs`（单调），用 `System.nanoTime()/1000` 兜底。队列长度 3，满时丢最旧。
- `AacEncoder`：`AudioRecord`（MIC、16bit、channels、sampleRate，buffer = minBuffer*2）独立线程读取；`MediaCodec` MIME `audio/mp4a-latm`、AAC LC、bitrate；输出 raw AAC 回调；`INFO_OUTPUT_FORMAT_CHANGED` 时把 `csd-0` 作为 `onConfig` 回调；PTS 按累计字节数换算 µs；stop 时释放。
- **无设备可测**：确保编译通过，逻辑清晰；在 `docs/` 或代码注释中写明无法实机验证的风险点（如某些编码器不支持 YUV420SemiPlanar、UVC 分辨率不支持）。
- 不要使用 libausbc（未 vendor）；只依赖 libuvc.aar + android SDK。

### 4.4 lead 侧编排（供 UI 使用）

Lead 会实现 `live/StreamEngine.kt`（相机→编码→推流）与 `ui/*`、`data/AppPrefs.kt`；rtmp/bili/capture 组件完成后由 lead 集成，组件负责人只需保证 API 与测试。

## 5. 验证与交付

- 每个组件：`gradle :app:compileDebugKotlin` 通过 + 自己的单元测试通过（`gradle :app:testDebugUnitTest`）。
- 不要修改 Gradle 依赖、`AndroidManifest.xml`、`data/StreamConfig.kt`、其他人的文件；需要时联系 lead。
- 交付：源码 + 测试 + 简短交接说明（构建命令、已验证/未验证项、已知风险）。
- 禁止：把未验证的功能说成已实测通过；提交真实 B 站账号/cookie；在代码中硬编码任何账号凭据。

## 6. 已知限制（需在 README 告知用户）

- 未在真机/模拟器验证（环境无 adb/emulator）；UVC/编码/推流需真机冒烟测试。
- B 站开播 API 为非官方接口，可能因平台升级失效或有账号风险（人脸验证/风控）；扫码登录凭据仅存本机。
- 仅支持 arm64-v8a / armeabi-v7a 设备；手机需支持 OTG + UVC 摄像头（MJPEG）。
- 音频取自手机麦克风，非 USB 摄像头音频。
- 直播期间需保持应用前台（预览绑定 TextureView；AudioRecord/摄像头在 Activity 生命周期内）。
