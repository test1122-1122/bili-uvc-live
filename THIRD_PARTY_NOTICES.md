# 第三方组件与许可证声明

本项目（BiliUVC Live）代码采用 MIT 许可证（见 `LICENSE`）。分发的 APK 包含以下第三方组件，请一并遵守其许可证。

## 打包进 APK 的 native 组件

`app/libs/libuvc.aar` 与 `app/libs/libuvccommon.aar` 来自 [jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera) 3.2.7（Apache-2.0）的 `libuvc`/`libuvccommon` 模块（后者是运行时必需依赖，提供 HandlerThreadHandler/BuildCheck 等工具类），是对 [saki4510t/UVCCamera](https://github.com/saki4510t/UVCCamera) 的复用/打包，包含：

| 组件 | 许可证 | 说明 |
|---|---|---|
| UVCCamera（Java + JNI 封装） | Apache License 2.0 | saki4510t，USB 摄像头访问与预览 |
| libuvc | BSD 3-Clause | UVC 协议实现 |
| libusb | LGPL-2.1（动态链接） | USB 设备访问 |
| libjpeg-turbo | BSD-3-Clause / IJG / zlib | MJPEG 解码 |
| libjpeg-turbo1500.so、libusb100.so、libuvc.so、libUVCCamera.so | 同上 | 打包于 AAR 的 `jni/arm64-v8a`、`jni/armeabi-v7a` |

> LGPL 合规提示：libusb 以动态链接（`.so`）方式打包。若你修改 libusb 源码，需要以 LGPL 允许的方式提供修改后的源码；单纯分发未修改的 `.so` 并保留本声明通常即可。商业分发前建议咨询法律意见。

## Android / Kotlin 依赖

| 组件 | 版本 | 许可证 |
|---|---|---|
| AndroidX AppCompat / Core KTX / ConstraintLayout | 见 `app/build.gradle.kts` | Apache-2.0 |
| Kotlin Standard Library | 1.9.0 | Apache-2.0 |
| OkHttp / Okio | 4.12.0 | Apache-2.0 |
| ZXing Core | 3.5.3 | Apache-2.0 |
| XLog（`com.elvishew:xlog`，UVCCamera 的 XLogWrapper 运行时依赖） | 1.11.0 | Apache-2.0 |
| JUnit / MockWebServer（仅测试） | 4.13.2 / 4.12.0 | EPL-1.0 / Apache-2.0 |

## 参考实现（未直接包含其代码）

- [JumpTwiceShou/bilibili-stream-for-obs](https://github.com/JumpTwiceShou/bilibili-stream-for-obs)（GPL-2.0）：B 站直播接口流程参考；本项目仅参考协议/接口调用方式，未复制其代码。
- [Zarosmm/obs-bilibili-stream](https://github.com/Zarosmm/obs-bilibili-stream)：上游思路参考。
- nExt Camera - USB（闭源商业应用）：仅作产品功能参考，未使用其代码/资源。

如权利人对本项目内容有异议，请提交 issue，我们会及时处理。
