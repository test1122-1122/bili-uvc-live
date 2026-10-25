# nExt Camera - USB 逆向对照笔记

用户提供的安装包：`nExt Camera_1.131-cardboard.apks`（base.apk + arm64_v8a/config 拆分）。这里记录它是如何检测/打开 USB 摄像头的，以及本项目采纳了哪些做法。

## 1. 清单与权限

- `package pl.nextcamera`，`versionName 1.131-cardboard`，**minSdk 32 / targetSdk 36**（compileSdk 36）。
- `uses-feature android.hardware.usb.host`（required），另外声明了 `CAMERA`、`RECORD_AUDIO`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CAMERA`、`POST_NOTIFICATIONS`。
- **没有 `android.hardware.camera2` 引用**（`apkanalyzer dex packages` 为空）→ UVC 摄像头走的是 USB Host + libusb，不是 Camera2 外接摄像头。`CAMERA` 权限更像历史/内建拍照用途，不是 UVC 必需。
- `<service android:name="pl.nextcamera.usb.UsbService" android:foregroundServiceType="0x40" />`（0x40 = camera）→ USB 采集跑在前台 Service 里。
- `assets/usb.db`（931 KB）：设备数据库（二进制/非 sqlite，非明文 SQLite），用于设备识别/兼容性处理。

## 2. 关键：USB 插入自动拉起 + device_filter

`pl.nextcamera.UsbDeepLinkActivity` 是一个 trampoline（跳板）Activity，只负责根据 UI 模式转去 `MainActivity` 或 `TvActivity`；它注册了：

```xml
<intent-filter>
    <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
</intent-filter>
<meta-data
    android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
    android:resource="@xml/device_filter" />
```

`res/xml/device_filter.xml` 的实际内容（`aapt2 dump xmltree`）：

```xml
<resources>
    <usb-device class="239" subclass="2" protocol="1" />
</resources>
```

即 **USB IAD（Interface Association Descriptor，0xEF/2/1）**。说明目标 UVC 摄像头在设备级描述符上经常是 `class=239, subclass=2, protocol=1`，而不是 `class=14`。系统插入设备时按这个 filter 拉起 App，随后 App 再去枚举/请求权限。

## 3. USB 权限与数据通路

- DEX 字符串可见自定义权限广播 action：`pl.nextcamera.action.USB_PERMISSION_REQ`，配合 `UsbManager.requestPermission(device, PendingIntent)`。
- 使用 `UsbManager.getDeviceList()` / `hasPermission()` / `requestPermission()` / `openDevice()`；
- JNI/native：`libusb1.0.so` + `libnextcamera.so`，符号如 `Java_pl_nextcamera_jni_USBContext_*`、`UVCContext_*`、`VideoDevice_*`；通过 `UsbDeviceConnection.getFileDescriptor()` 把 fd 交给 libusb，自行实现 UVC 控制/流传输（等价于 libuvc）。
- 整体链路：USB 插入 → `UsbDeepLinkActivity`/`MainActivity` → `UsbService`（前台）→ 枚举/申请权限 → `openDevice()` → fd → native libusb/UVC → 预览/编码/录制。

## 4. 本项目采纳/未采纳

| 做法 | 是否采纳 |
|---|---|
| `USB_DEVICE_ATTACHED` intent-filter + `device_filter.xml`（IAD + Video class） | ✅ v1.0.4 已加入 MainActivity，插入摄像头可自动拉起 App |
| `isUvcDevice` 识别 IAD（class 239/2/1） | ✅ v1.0.4 已加入，不再只认 class 14/interface 14 |
| 自定义 USB 权限广播 action + PendingIntent | ✅ 已有（动态 receiver，API33+ NOT_EXPORTED，API31+ FLAG_MUTABLE） |
| 前台 Service 持有 USB 采集 | ⏳ 暂未做（当前 Activity 生命周期内采集；退后台会停播） |
| 自研 libusb/UVC JNI + 设备数据库 | ❌ 未重写（使用 vendored saki UVCCamera/libuvc AAR，底层同为 libusb + fd） |
| CAMERA 权限 | ❌ 不需要（无 Camera2 路径；USB Host 授权即可） |

## 5. 对本项目问题的直接启示

之前 v1.0.0-v1.0.2 只按 `deviceClass==14` 或 `interfaceClass==14` 判定 UVC。若用户的摄像头是 IAD 复合设备（设备级 class 239/2/1），可能被 `isUvcDevice` 过滤掉 → `knownDevices` 为空 → 不会调用 `requestPermission` → **系统不弹 USB 授权框**。v1.0.4 起改为同时识别 IAD，并加上 attach intent-filter，和 nExt Camera 的检测方式一致。

> 注：仅做互操作分析，未复制 nExt Camera 的任何代码/资源；本项目仍使用 Apache-2.0 的 libuvc/libuvccommon 与自研 RTMP/B 站协议实现。
