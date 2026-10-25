# BiliUVC Live — 用 USB 摄像头直接直播 B 站（Android）

> **GitHub**: <https://github.com/test1122-1122/t508n_kernel/tree/import/bili-uvc-live>
> （当前发布在该镜像仓库的 `import/bili-uvc-live` 分支；tag `v1.0.12`）
>
> ```bash
> git clone -b import/bili-uvc-live https://github.com/test1122-1122/t508n_kernel.git bili-uvc-live
> ```

这是一个 Android 直播 App：把 **UVC/USB 摄像头** 通过 OTG 接到手机上，在 App 内直接推流到 **哔哩哔哩直播间**。

它解决的是官方路径的一个缺口：B 站官方的手机直播/PC 直播工具通常只能使用设备自带摄像头或虚拟摄像头，不能直接选择 USB 摄像头；而「手机当电脑摄像头（如 nExt Camera - USB）→ 电脑 OBS → bilibili-stream-for-obs 插件」的链路又必须依赖电脑和 OBS。本项目把这条链路收进一个 APK：

- 参考 **nExt Camera - USB** 的产品形态：UVC 外接摄像头预览、编码、推流；
- 参考 **bilibili-stream-for-obs** 的 B 站协议实现：扫码登录、读取直播间、调用开播接口拿到 `rtmp.addr` / `rtmp.code`、人脸验证轮询、停播；
- 自带纯 Kotlin 的 RTMP/FLV 推流客户端（不依赖 ffmpeg / OBS / 电脑）。

> ⚠️ 本项目调用的是 B 站**非官方**开播接口。接口随时可能调整；存在人脸验证、风控、账号限制等风险。请遵守 B 站直播规范，自行承担账号风险。请勿用于任何违规内容。

## 功能

- 📷 UVC 摄像头（OTG，MJPEG）实时预览，设备枚举/热插拔（默认自动打开第一个摄像头）
- 🎞️ H.264 硬件编码（MediaCodec，1280×720 / 1920×1080 等由摄像头支持的分辨率可选）
- 🎙️ 手机麦克风 AAC 编码（44.1kHz 立体声，可关闭）
- 📤 纯 Kotlin RTMP 推流：握手、AMF0、chunk、FLV/AVC/AAC sequence header、时间戳、ack/ping
- 🔐 B 站登录：二维码本地生成并轮询；v1.0.11 起自动把登录 URL 交给 B 站 App，在 B 站 App 里点「确认登录」即可（同机完成，无需第二台设备；真机已验证）
- 🔐 cookie 本机保存（SharedPreferences）
- 🟢 一键开播：读取直播间标题/分区 → 调 B 站开播接口 → 自动连接推流
- 🧑‍⚖️ 人脸验证：自动弹出验证二维码并轮询结果，验证通过后自动重试开播
- 🛑 停止直播：断开 RTMP 并调用 B 站停播接口；App 退到后台会自动尝试停播（若停播接口失败，可能需到直播中心手动关闭）
- 📊 实时状态：直播时长、码率、预览帧率、丢帧数

## 安装

1. 下载构建产物：`dist/BiliUVC-Live-1.0.12.apk`（release，测试签名）或 `dist/BiliUVC-Live-1.0.12-debug.apk`（debug）。
2. 手机需支持 **OTG** 且系统为 **Android 8.0+（API 26+）**；APK 仅包含 `arm64-v8a` / `armeabi-v7a`，不支持 x86 模拟器。
3. `adb install -r app-debug.apk`，或把 APK 传到手机点击安装（需允许「安装未知来源应用」）。

> Debug APK 使用仓库内 `keystore/debug.keystore` 签名（仅用于测试）。正式分发请替换为自己的 keystore。

## 使用

1. 用 OTG 线（必要时带供电的 USB Hub）连接 UVC 摄像头；首次会弹出系统「允许访问 USB 设备」对话框，选择允许。
2. 打开 App，按提示授予**相机权限**（Android 12+ 对 UVC 视频类 USB 设备的系统要求，缺少会直接拒绝 USB 授权且不弹窗）和麦克风权限（麦克风可拒绝，拒绝后为无声直播）。首次插入摄像头时系统会弹出 USB 访问授权，选择允许。画面出现预览即表示 UVC 链路正常；若采集卡没有 HDMI 输入，App 会提示“摄像头已打开但收不到画面”，这是正常现象。
3. 点「登录 B 站」→ App 会自动打开本机 B 站 App 的「哔哩哔哩扫码登录」确认页 → 点「确认」即可（无需第二台设备）。若未安装 B 站 App，则显示二维码，可用其他设备扫描。登录成功后顶部显示昵称与直播间。
4. 点「设置」选择分辨率、帧率、视频码率、是否采集麦克风（直播中不可改画面参数）。
5. 点「开始直播」→ 确认房间信息 → App 调 B 站开播接口：
   - 若返回人脸验证，会弹出二维码；用 B 站手机客户端扫码完成验证后会自动重试开播；
   - 开播成功后自动连接 RTMP，右上角出现「● 直播中」与实时码率/丢帧。
6. 点「停止直播」结束；**直播期间请保持本 App 在前台**，退到后台会自动停播（Android 会回收 TextureView 预览）。

### 常见问题

- **没有预览 / 提示无摄像头**：确认 OTG 线和摄像头支持 UVC（免驱 MJPEG）；部分摄像头供电不足需要带电源 Hub；重新插拔后等待 2~3 秒。
- **开播失败**：先确认账号已在 B 站开通直播间、完成实名认证；接口返回的错误信息会显示在页面顶部的消息条中。人脸验证失败时可在人脸验证窗口里点「我已完成认证，重试」。
- **推流连接失败**：检查网络（建议上行 ≥ 5 Mbps）；码率过高会被网络丢弃，可在设置里降低码率/分辨率。
- **画面比例不对**：预览按摄像头原始比例居中显示；推流画面即摄像头原始画面。

## 构建

要求：JDK 17、Android SDK（compileSdk 34 / build-tools 34）、Gradle 8.7+。若无 `local.properties`，请创建并写入 `sdk.dir=/path/to/Android/Sdk`，或设置 `ANDROID_HOME`。

```bash
cd bili-uvc-live
export ANDROID_HOME=$HOME/Android/Sdk   # 改成你的 Android SDK 路径
export ANDROID_SDK_ROOT=$ANDROID_HOME

./gradlew :app:assembleDebug         # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest     # JVM 单元测试（130 tests）
```

首次构建会自动下载 Gradle 8.7 与依赖；如果 `local.properties` 存在，请把 `sdk.dir` 改成你的 SDK 路径（该文件已在 `.gitignore` 中）。

依赖：AndroidX AppCompat/Core/ConstraintLayout、OkHttp、ZXing core，以及 **vendored** 的 `app/libs/libuvc.aar`（saki4510t UVCCamera + libusb + libuvc + libjpeg-turbo，arm64-v8a/armeabi-v7a）。不需要 NDK。

## 架构

```
app/src/main/java/com/biliuvc/live/
├── bili/        B 站 HTTP API：扫码登录、房间信息、开播/停播、人脸验证、会话持久化
├── rtmp/        纯 JVM RTMP 客户端：Target 解析、AMF0、chunk、FLV、H.264 Annex-B、AAC
├── capture/     UVC 摄像头（USBMonitor/UVCCamera）+ H.264/AAC MediaCodec 编码
├── live/        StreamEngine：摄像头 → 编码 → RTMP 的编排与状态/统计
├── data/        StreamConfig、AppPrefs
└── ui/          MainActivity、登录/人脸验证/设置对话框、二维码与格式化工具
```

数据流：

```
UVC(NV21) → H264Encoder(MediaCodec) ─┐
手机麦克风 → AacEncoder(MediaCodec) ─┴→ RtmpPublisher(FLV/RTMP) → B 站 rtmp 地址
B 站 HTTP API ← MainActivity（扫码/开播/人脸验证/停播）→ RtmpPublisher
```

详细协议说明见 [docs/PROTOCOL.md](docs/PROTOCOL.md)，协作与模块契约见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，真机测试清单见 [docs/HARDWARE_TEST.md](docs/HARDWARE_TEST.md)，独立验收报告与修复记录见 [docs/VERIFICATION_REPORT.md](docs/VERIFICATION_REPORT.md) 与 [docs/FIXES_AFTER_VERIFICATION.md](docs/FIXES_AFTER_VERIFICATION.md)。

## 验证状态（请务必阅读）

本项目的开发环境**没有真机、没有模拟器、没有 adb**，因此：

| 项目 | 状态 |
|---|---|
| Gradle 构建 APK | ✅ 可构建（debug/release） |
| 纯 JVM 单元测试（15 个测试类，**130 个用例全部通过**：RTMP/FLV/AMF0、H.264 Annex-B、B 站 API Mock、签名、URL 解析、跨组件集成） | ✅ `./gradlew :app:testDebugUnitTest` |
| B 站只读接口真实联调（qrcode/generate、Area/getList、liveVersionInfo 签名、房间信息；扫码成功后的登录链路未联调） | ✅ 见 docs/PROTOCOL.md |
| APK 构建产物 | ✅ `dist/BiliUVC-Live-1.0.0.apk`（release，约 5 MB）+ `dist/BiliUVC-Live-debug.apk`（约 6 MB），arm64-v8a + armeabi-v7a |
| Android Lint（`:app:lintDebug`） | ✅ 通过（0 error，剩余若干 warning：硬编码文案/未使用资源等） |
| 独立验收（verifier） | ⚠️ 修复前报告发现 2×P0 + 6×P1，已全部修复（见 [docs/FIXES_AFTER_VERIFICATION.md](docs/FIXES_AFTER_VERIFICATION.md)）；修复后编译/Lint/130 单测回归通过，真机行为仍需验证 |
| UVC 摄像头授权/打开、B 站 App 同机登录 | ✅ 已在红魔 NX789J / Android 15 + Hagibis 采集卡上验证（无 HDMI 输入时无画面属正常） |
| UVC 实机画面/编码/真实推流 | ❌ 未验证（测试时采集卡未接 HDMI 输入源） |
| 真实推流到 B 站直播间 | ❌ 未实机验证（需已开通直播间的账号） |
| 人脸验证/风控分支 | ❌ 未实机验证（依赖 B 站返回） |

所以：**这是一个可构建、核心协议有单元测试覆盖、并经过独立代码验收与问题修复的实现，但首次使用前请按 `docs/HARDWARE_TEST.md` 做真机冒烟测试。** 独立验收做的是静态审计 + 构建/单测复现（无真机能力），不能替代真机验证。如果遇到问题，请提供 logcat 与 B 站接口返回信息。

### v1.0.1（2026-10-25）修复「探测不到 USB 摄像头」

**根因**：项目只 vendor 了 `libuvc.aar`，漏掉了它的运行时依赖 `libuvccommon.aar`（提供 `com.serenegiant.utils.HandlerThreadHandler` / `BuildCheck` 等）与 `com.elvishew:xlog`（`XLogWrapper` 依赖）。`USBMonitor` 构造时立即抛 `NoClassDefFoundError`，相机初始化失败、设备列表为空。v1.0.1 已补齐两个依赖，并增加每个 USB 设备的 logcat 诊断日志（VID/PID/class/interface classes）。**v1.0.2** 处理「USB 授权被拒绝」：相机错误弹窗提供「重新授权」按钮；**v1.0.4** 对照 nExt Camera 的做法（见 [docs/NEXT_CAMERA_NOTES.md](docs/NEXT_CAMERA_NOTES.md)）：加入 `USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml`（IAD 239/2/1 + Video 14），`isUvcDevice` 同时识别 IAD 复合设备；并增加 App 内「USB 诊断/请求授权」入口（长按顶部摄像头文字或点提示区按钮）。**v1.0.5**：所有提示/错误改为**页面内非模态消息条**（不再用 Dialog/Toast，避免遮挡系统 USB 授权框）；屏幕方向改为**竖屏**。**v1.0.6**：修复「系统选择应用/授予 USB 权限后 App 仍停在拒绝状态」——收到 `USB_DEVICE_ATTACHED` intent 和 `onResume` 时强制重新枚举并打开（或重新申请）USB 设备，而不是调用幂等的 `start()`；提示条增加「选择 BiliUVC Live 并点始终」引导。**v1.0.7**：真机日志确认 Android 12+ 对 UVC 设备要求 CAMERA 权限，补回并请求；**v1.0.10**：支持 60fps；**v1.0.11**：登录 URL 直接交给 B 站 App 确认（同机登录）；**v1.0.12**：修复竖屏 dialog 宽度、设置默认分辨率、无 HDMI 信号提示（不误判带宽）。安装后如仍不能探测，请抓 `adb logcat -d -s UvcController:V AndroidRuntime:E UsbManager:V`。可用以下命令静态核验 APK：`apkanalyzer dex packages dist/BiliUVC-Live-1.0.6.apk | grep -E "HandlerThreadHandler|BuildCheck"`。

### 构建产物（2026-10-25）

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `dist/BiliUVC-Live-1.0.12.apk`（release） | 5.5 MB | `6b4983e0864eedb6253d16c223353e26bb74d99c28ca762e33978bc5ada1b225` |
| `dist/BiliUVC-Live-1.0.12-debug.apk`（debug） | 6.5 MB | `277b75b8d3841581355ef3ebf1d8d09a6f1efd5d3de7dcb43b082e712de8f178` |

两者均使用仓库内 `keystore/debug.keystore` 测试签名（可直接安装），正式分发前请更换签名。

## 已知限制

- 仅 Android 8.0+、ARM 设备；不支持 x86 模拟器；APK 仅含 arm64-v8a/armeabi-v7a。
- 音频来自手机麦克风，不支持 USB 摄像头自带麦克风/UAC 音频。
- UVC 以 MJPEG 模式工作（多数免驱摄像头均支持）；个别只输出 YUYV 的摄像头可能不兼容。
- 部分设备的硬件 H.264 编码器对 `YUV420SemiPlanar` 输入支持不同，已做 fallback，但仍需真机确认颜色/花屏问题。
- **CAMERA 权限是 Android 12+ 的硬性要求**：UVC 设备属于 USB video class，`UsbUserPermissionManager` 会检查 CAMERA，否则 USB 授权直接返回拒绝且不弹窗。v1.0.7 已修复（真机验证）。
- 帧率支持 15/20/25/30/50/60 fps（v1.0.10 起；此前默认 30 是代码限制，不是硬件限制）。60fps 需要采集卡/HDMI 源支持，且码率建议相应提高。
- 不支持后台/锁屏继续直播（预览依赖 TextureView；退到后台会自动停播）。
- 不支持美颜、滤镜、水印、多机位、录制 MP4（可作为后续迭代）。
- 不支持修改直播间标题/分区（后续迭代可基于 `BiliApi.updateRoomTitle()` 扩展）。
- RTMP 仅支持 `rtmp://`（TCP），不支持 `rtmps://`（TLS）。
- 断线不会自动重连（会回调错误并停播）；重连/码率自适应为后续迭代。
- B 站接口是非官方接口，可能失效；登录 cookie 仅存于本机 SharedPreferences（未加密）。RTMP 为明文 TCP 协议，推流 key 在链路中不额外加密（与 OBS 等 RTMP 推流一致）。

## 目录

```
bili-uvc-live/
├── app/                    Android 应用
├── docs/                   架构、协议、真机测试清单
├── keystore/debug.keystore 测试签名（正式发布请替换）
├── gradle/ gradlew         构建包装器
└── README.md
```

## 第三方与许可

本项目代码采用 MIT 许可证。内置/依赖的第三方组件：

- saki4510t **UVCCamera**（Apache-2.0）与 **AndroidUSBCamera** 打包的预编译 AAR（Apache-2.0）；
- **libuvc**（BSD-3-Clause）、**libusb**（LGPL-2.1）、**libjpeg-turbo**（BSD-3-Clause/IJG/zlib）；
- **OkHttp**（Apache-2.0）、**ZXing**（Apache-2.0）、AndroidX（Apache-2.0）。

完整说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。分发本 APK 时请保留相应许可证与声明。
