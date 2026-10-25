# BiliUVC Live — 独立验收报告（verifier）


> **注**：本报告是 2026-10-25 修复前的独立验收快照。P0/P1/P2 的修复状态与残余风险见同目录 [FIXES_AFTER_VERIFICATION.md](FIXES_AFTER_VERIFICATION.md)。

> 验收对象：`bili-uvc-live`
> 验收时间：2026-10-25（Asia/Shanghai）
> 环境：无真机 / 无模拟器 / 无 adb；JDK 17 + Gradle 8.7 + Android SDK 34 构建，JVM 单测 + 静态审计。
> 原则：不盲信 README 与开发者说法；代码、文档、构建产物、B 站线上接口、参考实现、vendored AAR 字节码交叉核对。

## 0. 结论

**有条件交付。** 构建、单测、APK 元数据、核心 RTMP/FLV/AMF0/签名/URL 解析逻辑 **PASS**；
但真机核心链路上存在 **2 个 P0 级代码缺陷 + 多个 P1/P2 问题**，修复并完成真机冒烟前，**不能作为"真机可用产品"交付**。
README 对"未实机验证"的披露总体诚实，但有 3 处轻微夸大（见 §7），另有 2 处以代码实现并未达成的功能描述（人脸自动重试）。

---

## 1. 实测结果（可复现）

实际执行（干净构建，禁用构建缓存，强制全量任务）：

```bash
cd bili-uvc-live
export GRADLE_USER_HOME=$GRADLE_USER_HOME \
       ANDROID_HOME=$ANDROID_HOME \
       ANDROID_SDK_ROOT=$ANDROID_HOME

./gradlew --no-daemon --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:clean :app:testDebugUnitTest :app:assembleDebug        # BUILD SUCCESSFUL, 45 tasks executed
./gradlew --no-daemon --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease   # BUILD SUCCESSFUL, 51 tasks executed
```

| 项目 | 实测值 | 判定 |
|---|---|---|
| 单测数量 | **130**（`app/build/test-results/testDebugUnitTest/*.xml`，15 个类，合计 1.77s） | PASS（与预期 130 一致） |
| 单测失败/错误/跳过 | **0 / 0 / 0**（无 `@Ignore`、无 `Assume`、无真实外网测试） | PASS |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`，**6,168,434 B（5.88 MiB）**，sha256 `861017ac…444d` | PASS |
| Release APK | `app/build/outputs/apk/release/app-release.apk`，**5,129,783 B（4.89 MiB）**，sha256 `7e24c007…142c`（README "debug/release 均可构建" 属实） | PASS |
| package / minSdk / targetSdk | `com.biliuvc.live` / 26 / 34（compileSdk 34，versionCode 1，versionName 1.0.0） | PASS |
| 权限（aapt2 badging） | CAMERA、RECORD_AUDIO、INTERNET、ACCESS_NETWORK_STATE、WAKE_LOCK，+ AndroidX 自动注入 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | PASS（其中 CAMERA/WAKE_LOCK/ACCESS_NETWORK_STATE 实际未被使用，见 P2-4） |
| native libs | `lib/{arm64-v8a,armeabi-v7a}/` 各 4 个：`libUVCCamera.so、libuvc.so、libusb100.so、libjpeg-turbo1500.so`；无 x86 | PASS |
| launchable activity | `com.biliuvc.live.ui.MainActivity` | PASS |
| 构建告警 | 2 条 Kotlin 警告（`BiliApi.kt:163` label 歧义、`StreamEngine.kt:100` 参数名） | 不影响构建 |

构建前 `:app:clean` + `--no-build-cache`，45 个任务全部 **executed**（非 FROM-CACHE），因此上述测试/APK 是真实重新执行的结果。

---

## 2. PASS / FAIL 清单

| # | 检查项 | 结果 | 依据 |
|---|---|---|---|
| 1 | 干净构建（debug/release） | **PASS** | 见 §1 |
| 2 | 单测 130 / 0 失败 | **PASS** | test-results XML |
| 3 | APK 包名/minSdk/targetSdk/权限/ABI/launchable | **PASS** | aapt2 badging + unzip -l |
| 4 | README 构建命令可复现 | **PASS** | 用给定 Gradle 8.7 + `-P…in-process` 实测 |
| 5 | RTMP 握手/AMF0/chunk/FLV/AVC/AAC 实现正确性 | **PASS（JVM/Mock 层面）** | 单测 + 独立 MockRtmpServer（独立 chunk 解析器） |
| 6 | B 站 URL 语义：`app=live-bvc`、streamName 保留前导 `?` | **PASS** | `RtmpTargetParserTest`、`RtmpPublisherIntegrationTest`、`BilibiliRtmpIntegrationTest` 断言到 publish 命令的 stringAt(3) |
| 7 | AppSigner MD5 逻辑 | **PASS（强证据）** | 签名向量 + 与参考 C++ 实现逐行一致 + **今日对真实 B 站 `getHomePageLiveVersion` 验证返回 `code:0, build:11202`** |
| 8 | 扫码登录请求头/接口路径/Set-Cookie 聚合/可信跳转 | **PASS（Mock 层面）** | `BiliApiQrTest`、`LoginCookiesTest`；与参考 `qrLogin`/`login_utils.cpp` 逻辑一致 |
| 9 | startLive / stopLive / 人脸验证参数与路径 | **PASS（静态对齐参考实现）**，**真实调用未验证** | 与 `ref/bilibili-stream-for-obs-main/src/bilibili_api.cpp` 的 URL/参数/签名逐一比对一致 |
| 10 | SessionStore 安全（MODE_PRIVATE、allowBackup=false、不上传） | **PASS**（明文存储已在 README/文档披露） | manifest `allowBackup=false`；代码无上传 |
| 11 | 无硬编码账号/Cookie/用户凭据 | **PASS** | 仅 `AppSigner` 硬编码 B 站 **App 公共 appkey/appsecret**（与参考实现相同，非用户凭据） |
| 12 | 文档与代码一致性 | **FAIL（轻微）** | 见 P3-1~P3-6（FlvWriter.kt 不存在、connect 参数不符、logcat tag 不存在等） |
| 13 | README 诚实性（未验证项是否如实标注） | **PASS（主体诚实）**，3 处轻微夸大 | 见 §7 |
| 14 | 真机可用性关键路径 | **FAIL** | P0-1 USB 权限结果丢失（Android 12+ 首次授权后摄像头打不开）；P0-2 人脸验证自动/手动重试无效 |
| 15 | 生命周期/线程/ANR/幽灵直播间 | **FAIL（风险）** | P1-2/P1-3/P1-4 |
| 16 | 人脸验证流程真实可用 | **FAIL（代码缺陷）** | P0-2 |
| 17 | 测试质量（是否假测试/过弱断言） | **PASS（良好）**，有覆盖盲区 | 见 §6 |

---

## 3. 缺陷列表

### P0-1（阻断真机核心链路）Android 12+ USB 授权结果被系统丢弃，首次授权后摄像头永远不会打开

- 位置：`app/src/main/java/com/biliuvc/live/capture/UvcController.kt:363-369`、`:383-401`、`:403-429`
- 现象：`permissionIntent` 在 `SDK_INT >= S` 使用 `PendingIntent.FLAG_IMMUTABLE`（L365）。系统 USB 授权弹窗通过
  `PendingIntent.send(context, 0, fillInIntent)` 把 `UsbManager.EXTRA_DEVICE` / `EXTRA_PERMISSION_GRANTED` 放进
  **fill-in intent**；AOSP 明确规定 immutable PendingIntent 会 **忽略** fill-in intent（`PendingIntent.java` FLAG_IMMUTABLE 注释、
  `PendingIntentRecord.sendInner` 中 `if (!immutable) finalIntent.fillIn(...)`；SystemUI `UsbDialogHelper.sendPermissionDialogResponse`
  正是用 fill-in intent 回传结果）。因此应用收到的广播只有 action，`onPermissionResult` 里
  `usbDeviceExtra(intent) ?: return`（L384）直接 return——**用户点"允许"后既不打开摄像头、也不提示**。
- 影响：Android 12/13/14（绝大多数在用设备）**首次插摄像头**时按 README 步骤 1→2 操作会一直黑屏；只有拔插重来（attach 广播走
  `handleDeviceAttached`→已有权限→打开）或重启 App（`autoOpenFirstAvailable`）才能恢复。Android 8–11（API 26–30）不受影响（flag=0 即 mutable）。
- 佐证：同一 AAR 的 `USBMonitor.register()`（`ref/auc/libuvc/.../USBMonitor.java:176`）也用了 immutable，是上游已知隐患；本项目绕开
  `register()` 的设计动机正确（该 API 在 targetSdk≥31 会因缺 mutability flag 直接抛异常），但**自己实现时把 flag 选错**。
- 建议：改为 `PendingIntent.FLAG_MUTABLE`（`intent.setPackage(...)` 已经是显式 intent，targetSdk 34 可接受）；或保留 immutable 但在
  `onReceive` 中按 `requestedPermissions` 遍历 `UsbManager.deviceList` 反查设备（不推荐，语义不严）。这是**发布前必须修复**项。

### P0-2（阻断人脸验证分支）`startInProgress` 未复位，人脸验证后自动重试/手动重试均无效

- 位置：`app/src/main/java/com/biliuvc/live/ui/MainActivity.kt:337-339`（`if (startInProgress) return`）、`:354-373`（`FaceAuthRequired` 分支未复位）、`:405-409`（手动"我已完成认证，重试"）、`:430-434`（轮询命中后自动重试）
- 现象：`startLiveAsync()` 入口要求 `startInProgress == false` 并置 `true`；`StartLiveResult.Failure/Success` 分支都会复位，
  **唯独 `FaceAuthRequired` 分支不复位**。于是：
  - 轮询到 `is_identified == true` → 关弹窗 → `startLiveAsync(s)` → 立即 return，**不会重新开播**；
  - 点"我已完成认证，重试" → 同样 return，**按钮无响应**；
  - 此时 `btnStream` 仍处于 `startLiveAsync` 时的 disabled 状态、`tvLiveState` 停在"连接中"，用户被判死（只能返回键→停止流程后重来）。
  轮询本身、60024/60028/60043 映射与参考实现一致，坏在 UI 状态机这一处。
- 建议：`handleStartLiveResult(FaceAuthRequired)` 中 `startInProgress = false`（或在两个重试入口先复位），并为弹窗重试加 loading/幂等保护。

### P1-1（UI 死锁）停止直播后"登录/设置"按钮永久禁用

- 位置：`MainActivity.kt:486-494`（`setLiveUi()` 置 `btnLogin.isEnabled=false`、`btnSettings.isEnabled=false`）vs `:478-484`（`resetIdleUi()` 未恢复）
- 现象：一次直播结束后 `resetIdleUi()` 只恢复 `btnStream`，**没有把 `btnLogin`/`btnSettings` 重新置 true**（全工程 grep 无其它 `isEnabled=true`）。
  此后用户无法切换账号/改设置，只能杀掉 App 重开。
- 建议：`resetIdleUi()` 内加 `binding.btnLogin.isEnabled = true; binding.btnSettings.isEnabled = true`。

### P1-2（ANR 风险）停止链路在主线程做多线程 join

- 位置：`MainActivity.kt:466`（onStop/按钮/摄像头断开都走这里）→ `StreamEngine.kt:268-279` → `RtmpPublisher.kt:1102-1127`、`:1190-1197`（4 个线程各 join 1.5s）、`H264Encoder.kt:161-173`、`AacEncoder.kt:99-115`（各 join 1s）
- 现象：`onStop()`（`:584-590`）、`onCameraDetached`（`:521-530`）、点"停止直播"都在**主线程**同步执行
  `audioEncoder.stop()→videoEncoder.stop()→publisher.disconnect()`；最坏合计 ≈ 1+1+4×1.5 = 8s，超过 5s 输入 ANR 阈值。
  正常网络下通常 <200ms，但弱网/USB 异常时可能触发 ANR。
- 建议：stop 编排挪到后台线程（或把 `disconnect()` 改为非阻塞 + 回调），主线程只更新 UI。

### P1-3（幽灵直播间）`onDestroy` 的 `io.shutdownNow()` 可能取消已排队的 B 站停播请求

- 位置：`MainActivity.kt:467`（`stopBiliLiveAsync`→`io.execute`，`:474-476`）、`:592-599`（`onDestroy` 里 `io.shutdownNow()`）
- 现象：`onStop` 先 `engine.stopStreaming()` 再排队 `api.stopLive`；若紧接着 `onDestroy`（如返回/被系统回收/多任务划掉），
  `shutdownNow()` 会把尚未执行的停播任务直接丢弃 → 推流已断但 B 站侧仍显示"直播中"（直到服务端超时）。
- 建议：停播请求不要放在会被 shutdown 的队列；`onDestroy` 前等待/或用独立 executor + `awaitTermination`。

### P1-4（生命周期竞态）Home 键打断"开播中"时可能先停后又被启动

- 位置：`MainActivity.kt:354-385`、`:459-472`、`:584-590`
- 现象：点"开始直播"后 `startInProgress=true`，`startLive` HTTP 尚在飞行时按 Home：`onStop→stopLiveInternal` 复位
  `startInProgress` 且 400ms 后把 `stopping` 复位；随后迟到的 `StartLiveResult.Success` 在主线程仍会执行
  `engine.startStreaming()`（未校验 `stopping`/activity 状态），出现"已请求停播却又开始推流"的短时错乱（最终因 B 站 stopLive
  或推流 ERROR 收敛，但状态机不严谨）。
- 建议：`handleStartLiveResult` 入口校验 `stopping || isFinishing || isDestroyed` 并直接 `stopBili`；用单一"会话代次"标记丢弃过期回调。

### P1-5（误登出）网络抖动导致启动时清空登录态

- 位置：`MainActivity.kt:238-255` + `BiliApi.kt:103-115`（`nav()` 把"未登录/网络错误/解析失败"统一返回 null）
- 现象：App 启动 `loadSession()→refreshRoomInfo()`；只要 `nav()` 因一次网络失败返回 null，就 `session=null; sessionStore.clear()`，
  用户被登出、必须重新扫码。
- 建议：`nav()` 区分 `-101/未登录` 与网络错误（返回 sealed 结果），网络错误时保留本地会话。

### P1-6（孤儿直播）摄像头错误不停推流

- 位置：`StreamEngine.kt:110-113`
- 现象：`onCameraError` 只置 `cameraOpened=false` 并上报 UI；若正在直播（如 startPreview 失败、UVC 内部错误），
  `streaming` 仍为 true、`RtmpPublisher` 保持连接但再也收不到帧；UI 不会进入停止流程，B 站房间处于"有推流但无内容"状态。
- 建议：错误回调时若 `streaming` 则 `stopStreaming()`（并让 Activity 调 stopLive）。

### P2 级（建议修，不单独阻断）

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| P2-1 | `MainActivity.kt:74-85,105-117`；`AndroidManifest.xml:11` | UVC 走 USB，不需要 `CAMERA` 权限，但 `permissionLauncher` 要求 CAMERA+RECORD_AUDIO 全授予；用户拒绝"相机"（合理隐私选择）或只想视频不要麦时 App 完全不可用 | 只申请真正需要的权限；音频权限按设置按需申请；移除 CAMERA（或改为可选） |
| P2-2 | `AndroidManifest.xml:14-15` | `WAKE_LOCK`、`ACCESS_NETWORK_STATE` 声明但全工程未使用（grep 无引用） | 删除多余权限（最小权限原则） |
| P2-3 | `UvcController.kt:727` | USB 回调每帧 `ByteArray(w*h*3/2)`（720p≈1.4MB @30fps≈41MB/s） | 帧池/环形缓冲复用，降低 GC 抖动 |
| P2-4 | `build.gradle.kts:22-35` | debug 与 **release 均用仓库内 `keystore/debug.keystore`（密码 android）** 签名 | 发布前换正式 keystore；README 已提示风险，但 release 配置本身就是"演示用" |
| P2-5 | `LoginDialog.kt:43-48` vs `:86-90` | 登录成功路径先 `cancelled=true` 再 `dialog.dismiss()`，`onDismissListener` 因 `!cancelled` 不成立而 **不会 shutdownNow** → 单线程 executor（非 daemon）泄漏 | dismiss 前/后显式 shutdown，或 onDismiss 里无条件 shutdown |
| P2-6 | `UvcController.kt:76-77`；`StreamEngine.kt:282-288` | `cameraThread`（HandlerThread）从不 `quitSafely()`；Activity 重建时泄漏线程+Activity 引用 | `release()` 中 dispose UvcController（新增 `destroy()`） |
| P2-7 | `MainActivity.kt:345-348` | `roomInfo` 为空且二次拉取失败时 `areaId=0` 仍发起 startLive；B 站大概率拒绝，且错误信息不可读 | 拉不到分区信息就中止并提示重试 |
| P2-8 | `UvcController.kt:546` | `applyPreviewSize` 内部可能因 MJPEG 失败落到 YUYV 成功，但外部又用首选 format 覆盖 `currentFrameFormat` → 记录与实际不符 | 以 `applyPreviewSize` 返回值/实际 format 为准 |
| P2-9 | `UvcController.kt:820-827` + `:714-726` | 全部 setPreviewSize 失败时回退 `getPreviewSize()`，实际帧尺寸可能既非 desired 也非 `currentSize`，`handleFrame` 静默丢帧（仅 log） | 回退后用真实尺寸重建 currentSize，并向 UI 报错 |
| P2-10 | `StreamEngine.kt:156-173` | 改分辨率用 `uvc.stop()+250ms 延迟 start()` 重开；与插拔/前后台切换并发时存在时序窗口 | 用一次性回调/状态机替代固定延时 |
| P2-11 | `MainActivity.kt:55-56` 等 | `roomInfo` 依赖登录时一次性拉取；直播前不刷新，分区变更后 startLive 仍用旧 areaId | 开播前 refresh roomInfo |

### P3 级（文档/清理）

| # | 位置 | 问题 |
|---|---|---|
| P3-1 | `docs/PROTOCOL.md:184` | 声称 FLV 封装在 `rtmp/FlvWriter.kt`，**该文件不存在**（实现内联在 `RtmpPublisher.kt`） |
| P3-2 | `docs/PROTOCOL.md:187`、`docs/ARCHITECTURE.md:113` | 文档称 connect 对象含 `capabilities=15`、`audioCodecs=4071`；代码实际为 `audioCodecs=3575`（`RtmpPublisher.kt:785`）且**无 capabilities 字段**（对服务端无影响，但与文档不符） |
| P3-3 | `docs/HARDWARE_TEST.md:17` | 要求 `logcat -s BiliUvc UvcController`，代码中不存在 `BiliUvc` tag（只有 UvcController/H264Encoder/AacEncoder） |
| P3-4 | `README.md:104` | "B 站扫码登录接口真实联调（只读，不登录）✅"：实际只真实请求过 qrcode/generate、Area/getList、liveVersionInfo（今天复验仍可用），**qrcode/poll 与登录成功路径从未真实联调**；措辞宜改为"二维码生成/分区/版本接口只读联调" |
| P3-5 | `README.md:22,42` | "退到后台会自动停播，避免幽灵直播间"是设计意图；P1-3/P1-4 说明仍可能残留直播间，建议加"仍可能残留，需到直播中心确认" |
| P3-6 | `README.md:15` | "支持多摄像头"：`selectDevice/requestUsbPermission` 有 API，但 **UI 没有任何设备选择入口**，实际只会自动打开第一个 |
| P3-7 | `AppPrefs.kt:202-208` | `lastCameraId`、`autoReconnect` 从未被使用（死配置） |
| P3-8 | `BiliApi.kt:153,281,299` | `areaGroups()`、`updateRoomTitle()`、`liveStatus()` 生产代码未调用（仅单测覆盖）；`areaGroups` 内 `BiliApi.kt:163` 的 `return@mapNotNull` 有同名 label 歧义警告 |
| P3-9 | `README.md`/代码 | 未声明 RTMP 为明文（1935），推流 key 在网络中明文传输（RTMP 固有，但应告知用户） |

---

## 4. 独立事实核查（不依赖开发者说法）

### 4.1 B 站线上接口（今日真实请求，未登录只读）

| 接口 | 结果 |
|---|---|
| `GET passport…/x/passport-login/web/qrcode/generate` | 200，`code:0`，返回 `data.url` + `data.qrcode_key`，结构与 `docs/PROTOCOL.md` §2.1 完全一致 |
| `GET api.live…/room/v1/Area/getList` | 200，`code:0`，一级 `id` 为数字、**子分区 `id` 为字符串**（`"86"`）——`Json.asInt` 支持字符串数字，解析正确 |
| `GET api.live…/getHomePageLiveVersion?system_version=2&ts=…&appkey=…&sign=<按代码算法独立计算>` | **200，`code:0`，`data.build=11202`、`curr_version=8.11.1.11202`** → 证明 `AppSigner` 的排序+percent-encode+MD5+appsecret 算法与真实 B 站一致 |
| `GET api.bilibili.com/x/web-interface/nav` | 200，`code:-101 账号未登录` → 与代码"未登录返回 null"一致 |
| `GET …/getRoomBaseInfo?req_biz=web_room_componet&room_ids=6` | 200，`by_room_ids` 的 key 是真实房间号 `7734200`（不是短号 6）→ 代码 `roomInfo` 的"按 key 取不到则取第一个"回退路径是**必要且正确**的 |
| `GET …/room/v2/Room/room_id_by_uid?uid=2` | 200，`data.room_id=1024` |
| `GET …/room/v1/Room/getRoomInfoOld?mid=2` | 200，`data.liveStatus` 存在 |

> 以上说明 `docs/PROTOCOL.md` 的"只读实测"部分属实；`startLive/stopLive/人脸验证` 仍未真实验证（也不敢在验收中拿账号操作），README 表格对此标注 ❌ 属实。

### 4.2 与参考实现对比（`ref/bilibili-stream-for-obs-main`）

- `appsign`：参考实现先 `buildFormBody`（percent-encode）再拼 appsecret 取 MD5（`src/bilibili_api.cpp:80-105` + `src/core/api_utils.cpp:47-76`），Kotlin `AppSigner` 逐步等价。**签名无偏差。**
- `startLive` 版本查询→签名 POST、60024/60028/60043 分支、`isFaceAuthDone` 参数、`stopLive` 参数、`updateRoomTitle`、`roomInfo`、`liveStatus`、`room_id_by_uid`、`Area/getList`：URL、参数顺序/内容、Cookie/CSRF 用法**逐项一致**。
- 扫码 Cookie 规则（仅保留 SESSDATA/bili_jct/DedeUserID、值安全校验、Set-Cookie→data.url→GET 补票、trusted host 白名单、仅 https/443）
  与参考 `src/core/login_utils.cpp` 等价；Kotlin 额外做了长度/控制字符校验，属加强。

### 4.3 vendored `app/libs/libuvc.aar` 字节码核对（`javap`）

- `USBMonitor.register()` 内部 `PendingIntent.getBroadcast(..., 0)`（无 mutability flag）→ 在 targetSdk≥31 会抛异常，**代码绕开 register() 的注释属实**。
- `USBMonitor.openDevice(device)`：只做 `mUsbManager.hasPermission(device)`→`new UsbControlBlock`→缓存，**不要求 register()**；`getDeviceList()` 直接读 `UsbManager` → 绕开 register 的设计**可行**（除 P0-1 的 flag 问题）。
- `UVCCamera.open(ctrlBlock)` 确实 `mCtrlBlock = ctrlBlock.clone()`（第二次 `openDevice`）；`close()/destroy()` 关闭 clone。`UvcController` 再 `block.close()` 关闭原始 block，`UsbControlBlock.close()` 对 `mConnection==null` 幂等 → **不是双重释放，资源回收路径正确**（此前注释描述准确）。
- `USBMonitor.destroy()` 在从未 register 时 `unregister()` 因 `mPermissionIntent==null` 不会抛 → 调用安全。
- AAR 内 4 个 `.so` × 2 ABI 均在 APK `lib/` 下，arm64-v8a / armeabi-v7a 正确，无 x86；`.so` 为 4KB 页对齐（targetSdk 34 下 Android 15 16KB 页设备用兼容模式，暂无阻断）。

### 4.4 RTMP 实现核对（人工 + 单测）

- 握手：C0/C1、读 S0/S1/S2、C2=S1（simple handshake）正确；未校验 S2（simple 模式允许）。
- chunk：fmt0 发送 + fmt3 续块、out chunk size=4096、扩展时间戳（%0xFFFFFF + 续块重复）正确；入站支持 fmt0-3、1/2/3 字节 basic header、SetChunkSize。
- 控制：SetChunkSize / WindowAckSize / SetPeerBandwidth / PingRequest→Pong / Acknowledgement（按入站 WindowAckSize 计数）齐全；connect 对象 txn=1、createStream=4、publish=5（与 ffmpeg 一致），`app`/`tcUrl`/`flashVer`/`fpad` 存在。
- FLV：video `0x17|0x27 0x01 000000 + AVCC(4字节长度)`、AVC seq header `0x17 0x00 + AVCDecoderConfigurationRecord`、audio `0xAF 0x00/0x01`、`@setDataFrame onMetaData` 先于首个媒体帧；时间戳 ms、单调、分轨钳制，首帧 rebase 正确。
- B 站语义：`streamName` 保留前导 `?`（`?streamname=…&key=…`）——单测断言到 publish 命令第 4 个 AMF 值；这正是 OBS/ffmpeg 的推流方式。
- 线程安全：out 写 `writeLock` 串行；pending 队列上限 120、满则优先丢非关键帧；disconnect 幂等、token 隔离旧会话；公开方法吞异常。**未发现协议级 bug。**

---

## 5. 最可能在真机失败的点（Top 5）

1. **（P0-1）Android 12+ 首次 USB 授权后摄像头不打开** —— 系统弹窗"允许"后无任何反应（授权结果被 immutable PendingIntent 丢弃）。最高概率、最影响首用。
2. **H.264 MediaCodec 输入格式** —— `COLOR_FormatYUV420SemiPlanar` 在部分机型不可配置；Flexible 分支的 NV21→plane 布局/stride 处理（`H264Encoder.fillImage`）未实机验证，可能 configure 失败或绿屏/花屏。
3. **UVC 预览生命周期/分辨率切换** —— 后台/返回时 `onSurfaceTextureDestroyed→detachPreview` 与 native 渲染的时序、`setPreviewSize` 触发的 stop/start 重开、1080p MJPEG 的 USB2 带宽与 30fps 限制，最可能表现为黑屏/卡顿/偶发 native crash。
4. **（P0-2）人脸验证分支不可用** —— 一旦返回 60024/60028/60043，自动重试与"我已完成认证，重试"都无效；60043 本身还依赖 B 站官方风控页（参考实现也注明不保证）。
5. **停止链路 ANR / 幽灵直播间** —— 后台/断连时主线程 join、`onDestroy` 取消停播请求、Home 打断开播的竞态；加上 `startLive` 真实契约未验证、`roomInfo` 失败时 `area_v2=0`，真机可能遇到"停不住/开不了/房间已开但没画面"。

---

## 6. 测试质量评估（是否只测 happy path / 假测试 / 断言过弱）

- **不是假测试**：无 `@Ignore`、无 `Assume`、测试不打真实外网（全部 MockWebServer / 127.0.0.1 MockRtmpServer）；`MockRtmpServer` 是**独立实现的 chunk 解析器**（不是复用被测代码），因此 RTMP 集成测试有真实区分力。
- **断言强度较好**：
  - AMF0/H264 Annex-B/AVCDecoderConfigurationRecord/AAC ASC 有 **golden bytes**；`AppSignerTest` 期望值声称由独立 Python 计算（本次验收又用真实 B 站接口验证）。
  - `BiliApiLiveTest` 断言到 **完整 form body/query 字符串**（含 sign）、请求头、Cookie、错误分支（HTTP 500/网络失败/业务码/字段缺失）。
  - RTMP 集成测试覆盖：握手、publish 命令、out chunk size=4096、WindowAck/PeerBandwidth、ping→pong、ack、大帧分块（64KB→16 chunk）、扩展时间戳、publish 被拒、连接失败重试、pre-publish 帧丢弃计数、AVC/AAC sequence header 顺序。
- **覆盖盲区（诚实说明）**：`capture/*`、`live/StreamEngine`、`ui/MainActivity` 全部无 JVM 测试（依赖 android.*，未引入 Robolectric）；`SessionStore` 未测；B 站真实契约、UVC、MediaCodec、Activity 生命周期均不可在 JVM 覆盖——这正是 P0/P1 缺陷没被测试抓到、也无法抓到的地方。
- 结论：README 声称的"核心协议有单元测试覆盖"**准确**；不能据此推断"App 可正常使用"。

---

## 7. README / docs 诚实性评估

**属实且可复现**：构建 debug/release、130 单测、APK 包名/SDK/ABI/权限、UVC 摄像头/真实推流/人脸验证未实机验证的 ❌ 表格、已知限制（仅 ARM、MJPEG、UVC 音频、前台限制、Cookie 明文、非官方接口）。

**轻微夸大/不准确**：
1. `README.md:104` "B 站扫码登录接口真实联调（只读，不登录）✅"——实际只验证了 qrcode **generate**、分区、版本接口；**poll/登录成功路径未联调**（措辞应为"二维码生成/只读接口"）。
2. `README.md:21` "人脸验证…验证通过后自动重试开播"——代码 P0-2 导致**该功能实际不可用**，属实现未达标而非文档夸大，但对用户是"说了做不到"。
3. `README.md:22,42` "退到后台自动停播，避免幽灵直播间"——基本实现，但 P1-3/P1-4 表明仍可能残留，建议弱化承诺。
4. `README.md:15` "支持多摄像头/热插拔"——热插拔有，多摄像头**无 UI 选择入口**（仅自动用第一个）。
5. `docs` 3 处与代码不一致（P3-1/2/3）。
6. **没有发现**"未验证说成已验证"的严重造假：README 验证状态表明确把 UVC/真实推流/人脸验证列为 ❌，HARDWARE_TEST 也要求真机逐项打勾。

---

## 8. 交付建议

1. 必须修复 **P0-1**（USB 权限 PendingIntent 改 `FLAG_MUTABLE`）与 **P0-2**（人脸验证重试复位 `startInProgress`），否则核心卖点在 Android 12+ / 触发风控账号上直接不可用。
2. 建议同期修复 P1-1（按钮恢复）、P1-3（停播请求不可被 shutdownNow 丢弃）、P1-2（stop 移出主线程）、P1-5（网络错误勿清会话）。
3. 按 `docs/HARDWARE_TEST.md` 在至少 2 台设备（Android 13/14 + 一台 Android 10 或更早）做冒烟：UVC 首授权→预览→720p 推流→观众端确认→停播→直播中心确认未开播。
4. 文档同步 P3 项（尤其删除/改名不存在的 `FlvWriter.kt`、修正 connect 参数与 logcat tag、把"已联调"限定为 generate/分区/版本）。
5. 若能提供真机 logcat 与 B 站接口返回，可在下一轮把 P0/P1 修复验证为 PASS。
