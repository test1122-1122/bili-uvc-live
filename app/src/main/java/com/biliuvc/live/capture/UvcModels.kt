package com.biliuvc.live.capture

/**
 * 一个已接入的 UVC（USB Video Class）摄像头。
 *
 * 说明：`deviceId` 是 Android 的 [android.hardware.usb.UsbDevice.getDeviceId]，
 * 只保证在「本次 USB 接入期间」稳定；拔插后可能变化，不能持久化。
 */
data class UvcDeviceInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val name: String
)

/**
 * 一帧摄像头原始数据（NV21 / YVU420SemiPlanar）。
 *
 * 内存约定：`nv21` 是**每帧独立新分配**的数组（不是 native 复用 buffer 的视图），
 * 因此调用方可以安全地把它放进异步队列 / 交给编码线程持有。
 * 数据布局：长度 = width * height * 3 / 2；先是 width*height 的 Y，然后是交错 VU（V 在前）。
 *
 * 注意：data class 对 ByteArray 的 equals/hashCode 是引用比较，不要依赖内容比较。
 */
data class UvcFrame(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val timestampUs: Long
)
