package com.biliuvc.live.bili

/**
 * B 站直播 HTTP API 客户端的数据模型。
 *
 * 本文件必须保持纯 Kotlin（不依赖 android.*），以便在 JVM 单元测试中直接使用。
 */

/**
 * 已登录会话。
 *
 * @param cookies `a=b; c=d` 形式的 cookie 头（至少包含 SESSDATA / bili_jct / DedeUserID）
 * @param uid 账号 mid（DedeUserID）
 * @param uname 昵称（可能为空串）
 * @param roomId 直播间号；0 表示未开通/未知
 */
data class BiliSession(
    val cookies: String,
    val uid: Long,
    val uname: String,
    val roomId: Long
) {
    /** CSRF token，即 cookie 中的 `bili_jct`；缺失时为空串。 */
    val csrf: String
        get() = LoginCookies.extract(cookies, LoginCookies.BILI_JCT) ?: ""

    /** 可直接用于 `Cookie:` 请求头的字符串。 */
    fun cookieHeader(): String = cookies

    companion object {
        /**
         * 从 cookie 字符串构造会话：缺少合法的 DedeUserID 时返回 null。
         * 用于扫码登录成功后把 [QrPollResult.Success] 转成会话。
         */
        fun fromCookies(cookies: String, uname: String = "", roomId: Long = 0L): BiliSession? {
            val uid = LoginCookies.extract(cookies, LoginCookies.DEDE_USER_ID)?.toLongOrNull() ?: return null
            if (uid <= 0) return null
            if (!LoginCookies.isComplete(cookies)) return null
            return BiliSession(cookies = cookies, uid = uid, uname = uname, roomId = roomId)
        }
    }
}

/** 扫码登录二维码：内容为 [url]（B 站页面链接/二维码内容），轮询时用 [key]。 */
data class QrTicket(val url: String, val key: String) {
    /** 接口失败时为 true（url/key 为空），UI 应提示重试。 */
    val isEmpty: Boolean get() = url.isEmpty() || key.isEmpty()
}

/** 二维码轮询结果。 */
sealed class QrPollResult {
    /** 86101：等待扫码 */
    object Pending : QrPollResult()

    /** 86090：已扫码，等待手机端确认 */
    object Scanned : QrPollResult()

    /** 86038：二维码已失效 */
    object Expired : QrPollResult()

    /** 登录成功；[cookies] 至少包含 SESSDATA / bili_jct / DedeUserID。 */
    data class Success(val cookies: String, val uid: Long) : QrPollResult()

    /** 网络/接口错误（含未获取到完整凭据）。 */
    data class Failure(val message: String) : QrPollResult()
}

/** 账号信息（导航栏接口）。 */
data class UserInfo(val uid: Long, val uname: String)

/** 直播间基础信息。 */
data class RoomInfo(
    val roomId: Long,
    val title: String,
    val parentAreaId: Int,
    val areaId: Int,
    val parentAreaName: String,
    val areaName: String,
    val liveStatus: Int
)

/** 直播二级分区。 */
data class Area(val id: Int, val name: String)

/** 直播一级分区及其子分区。 */
data class AreaGroup(val id: Int, val name: String, val subAreas: List<Area>)

/** 开播结果。 */
sealed class StartLiveResult {
    /** 开播成功：推流地址 = addr + code（code 以 '?' 开头，需原样保留）。 */
    data class Success(val addr: String, val code: String) : StartLiveResult()

    /**
     * 需要人脸验证。
     *
     * @param faceAuthCode 60024 / 60028 / 60043
     * @param qrContent 二维码内容：60024/60028 为服务端返回的 data.qr；
     *                  60043 为 face-auth-middle.html?source_event=400&mid=<uid>
     */
    data class FaceAuthRequired(val faceAuthCode: Int, val qrContent: String) : StartLiveResult()

    /** 其它失败（业务错误或网络错误）。 */
    data class Failure(val message: String) : StartLiveResult()
}
