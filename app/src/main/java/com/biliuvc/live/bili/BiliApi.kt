package com.biliuvc.live.bili

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * B 站直播 HTTP API 客户端（OkHttp 同步调用）。
 *
 * 与 `docs/ARCHITECTURE.md` 4.2 节的公开 API 契约一致；所有网络/格式错误都返回
 * null 或 Failure，不抛异常、不崩溃。超时 10s，调用方需自行放到后台线程。
 *
 * 参考实现：`ref/bilibili-stream-for-obs-main/src/bilibili_api.cpp`。
 */
class BiliApi(
    private val endpoints: BiliEndpoints = BiliEndpoints.PRODUCTION,
    private val client: OkHttpClient = defaultHttpClient(),
    /** 当前 unix 秒；测试可注入固定时间以获得确定性签名。 */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L }
) {

    // ---------------------------------------------------------------- 扫码登录

    /**
     * 获取登录二维码。
     *
     * 失败时返回 `QrTicket("", "")`（[QrTicket.isEmpty] 为 true），调用方应先检查；
     * 契约签名不允许返回 null，这里以空对象表达失败。
     */
    fun generateQr(): QrTicket {
        val reply = get(passportUrl(QR_GENERATE_PATH))
            ?: return QrTicket("", "")
        if (reply.status != 200) return QrTicket("", "")
        val json = Json.parse(reply.body).asObj() ?: return QrTicket("", "")
        if (json["code"].asInt() != 0) return QrTicket("", "")
        val data = json["data"]
        val url = data["url"].asString().orEmpty()
        val key = data["qrcode_key"].asString().orEmpty()
        if (url.isEmpty() || key.isEmpty()) return QrTicket("", "")
        return QrTicket(url, key)
    }

    /**
     * 轮询扫码状态。
     *
     * `data.code`：0 成功 / 86038 已过期 / 86090 已扫码未确认 / 86101 等待扫码。
     * 成功时从 Set-Cookie 聚合 SESSDATA/bili_jct/DedeUserID；不完整时先从 `data.url`
     * 的 query 提取，再（必要时）GET `data.url` 补票（限制跳转到受信任 host）。
     */
    fun pollQr(key: String): QrPollResult {
        if (key.isBlank()) return QrPollResult.Failure("二维码密钥为空")
        val query = "qrcode_key=" + LoginCookies.encodeComponent(key)
        val reply = get(withQuery(passportUrl(QR_POLL_PATH), query))
            ?: return QrPollResult.Failure(QR_POLL_ACTION + "失败: 网络错误或超时")
        if (reply.status != 200) {
            return QrPollResult.Failure("${QR_POLL_ACTION}失败，HTTP 状态码: ${reply.status}")
        }
        val json = Json.parse(reply.body).asObj()
            ?: return QrPollResult.Failure("${QR_POLL_ACTION}失败: 响应不是有效的 API 数据")
        val code = json["code"].asInt()
            ?: return QrPollResult.Failure("${QR_POLL_ACTION}失败: 响应不是有效的 API 数据")
        if (code != 0) return QrPollResult.Failure(apiMessage(json, QR_POLL_ACTION))
        val data = json["data"]
        when (val pollCode = data["code"].asInt()) {
            0 -> Unit
            86038 -> return QrPollResult.Expired
            86090 -> return QrPollResult.Scanned
            86101 -> return QrPollResult.Pending
            null -> return QrPollResult.Failure("${QR_POLL_ACTION}失败: 缺少有效的扫码状态")
            else -> return QrPollResult.Failure(dataMessage(data, "二维码登录", pollCode))
        }

        var candidate = LoginCookies.fromSetCookieHeaders(reply.setCookies)
        val loginUrl = data["url"].asString().orEmpty()
        if (!LoginCookies.isComplete(candidate) && loginUrl.isNotEmpty()) {
            val legacy = LoginCookies.fromLoginUrl(loginUrl, endpoints.trustedLoginHosts, endpoints.allowInsecureLoginTicket)
            candidate = LoginCookies.merge(legacy, candidate)
        }
        if (!LoginCookies.isComplete(candidate) && loginUrl.isNotEmpty()) {
            if (!LoginCookies.isTrustedLoginUrl(loginUrl, endpoints.trustedLoginHosts, endpoints.allowInsecureLoginTicket)) {
                return QrPollResult.Failure("二维码登录失败: 返回的登录跳转地址无效")
            }
            val ticket = fetchLoginTicketCookies(loginUrl)
                ?: return QrPollResult.Failure("获取登录凭据失败: 网络错误或超时")
            candidate = LoginCookies.merge(candidate, ticket)
        }
        if (!LoginCookies.isComplete(candidate)) {
            return QrPollResult.Failure("二维码已确认，但未获取到完整登录凭据，请重新扫码")
        }
        val uid = LoginCookies.extract(candidate, LoginCookies.DEDE_USER_ID)?.toLongOrNull()
            ?: return QrPollResult.Failure("二维码登录成功，但 Cookies 中缺少有效的账号标识")
        return QrPollResult.Success(candidate, uid)
    }

    // ---------------------------------------------------------------- 账号 / 房间

    /** 导航栏账号信息；未登录（code -101 或 isLogin=false）与失败都返回 null。 */
    fun nav(cookies: String): UserInfo? {
        if (cookies.isBlank()) return null
        val reply = get(webUrl(NAV_PATH), cookies) ?: return null
        if (reply.status != 200) return null
        val json = Json.parse(reply.body).asObj() ?: return null
        if (json["code"].asInt() != 0) return null
        val data = json["data"]
        if (data["isLogin"].asBool() != true) return null
        val uid = data["mid"].asLong() ?: return null
        if (uid <= 0) return null
        return UserInfo(uid = uid, uname = data["uname"].asString().orEmpty())
    }

    /** 按 uid 查直播间号；0 表示未开通直播间，null 表示请求失败。 */
    fun roomIdByUid(cookies: String, uid: Long): Long? {
        if (uid <= 0) return null
        val query = "uid=" + LoginCookies.encodeComponent(uid.toString())
        val reply = get(withQuery(liveUrl(ROOM_ID_BY_UID_PATH), query), cookies) ?: return null
        if (reply.status != 200) return null
        val json = Json.parse(reply.body).asObj() ?: return null
        if (json["code"].asInt() != 0) return null
        val roomId = json["data"]["room_id"].asLong() ?: return null
        if (roomId < 0) return null
        return roomId
    }

    /** 直播间基础信息（标题/分区/直播状态）。 */
    fun roomInfo(cookies: String, roomId: Long): RoomInfo? {
        if (roomId <= 0) return null
        val query = "req_biz=web_room_componet&room_ids=" + LoginCookies.encodeComponent(roomId.toString())
        val reply = get(withQuery(liveUrl(ROOM_BASE_INFO_PATH), query), cookies) ?: return null
        if (reply.status != 200) return null
        val json = Json.parse(reply.body).asObj() ?: return null
        if (json["code"].asInt() != 0) return null
        val byRoomIds = json["data"]["by_room_ids"].asObj() ?: return null
        val room = byRoomIds.fields[roomId.toString()] ?: byRoomIds.fields.values.firstOrNull() ?: return null
        val parsedRoomId = room["room_id"].asLong()?.takeIf { it > 0 } ?: roomId
        return RoomInfo(
            roomId = parsedRoomId,
            title = room["title"].asString().orEmpty(),
            parentAreaId = room["parent_area_id"].asInt() ?: 0,
            areaId = room["area_id"].asInt() ?: 0,
            parentAreaName = room["parent_area_name"].asString().orEmpty(),
            areaName = room["area_name"].asString().orEmpty(),
            liveStatus = room["live_status"].asInt() ?: 0
        )
    }

    /** 直播分区列表（一级分区 + 子分区）；失败返回空列表。 */
    fun areaGroups(): List<AreaGroup> {
        val reply = get(liveUrl(AREA_LIST_PATH)) ?: return emptyList()
        if (reply.status != 200) return emptyList()
        val json = Json.parse(reply.body).asObj() ?: return emptyList()
        if (json["code"].asInt() != 0) return emptyList()
        val groups = json["data"].asArr() ?: return emptyList()
        return groups.items.mapNotNull { group ->
            val id = group["id"].asInt() ?: return@mapNotNull null
            val name = group["name"].asString().orEmpty()
            val subAreas = (group["list"].asArr() ?: group["sub"].asArr())?.items.orEmpty().mapNotNull { sub ->
                val subId = sub["id"].asInt() ?: return@mapNotNull null
                Area(id = subId, name = sub["name"].asString().orEmpty())
            }
            AreaGroup(id = id, name = name, subAreas = subAreas)
        }
    }

    // ---------------------------------------------------------------- 开播 / 停播

    /**
     * 开播。
     *
     * 先取直播版本信息（appkey 签名），再以 `platform=pc_link` 等参数签名 POST startLive：
     * - code 0 → Success(addr, code)，RTMP 地址 = addr + code；
     * - 60024/60028 → FaceAuthRequired(code, data.qr)；
     * - 60043 → FaceAuthRequired(60043, face-auth-middle.html?source_event=400&mid=<uid>)；
     * - 其它 → Failure(message)。
     */
    fun startLive(cookies: String, csrf: String, roomId: Long, areaId: Int, uid: Long): StartLiveResult {
        if (cookies.isBlank() || csrf.isBlank() || roomId <= 0) {
            return StartLiveResult.Failure("开播配置无效，请重新登录并更新直播间信息")
        }
        val versionParams = linkedMapOf(
            "system_version" to "2",
            "ts" to nowSeconds().toString()
        )
        val versionQuery = AppSigner.sign(versionParams)
        val versionReply = get(withQuery(liveUrl(LIVE_VERSION_PATH), versionQuery), cookies)
            ?: return StartLiveResult.Failure("${VERSION_ACTION}失败: 网络错误或超时")
        if (versionReply.status != 200) {
            return StartLiveResult.Failure("${VERSION_ACTION}失败，HTTP 状态码: ${versionReply.status}")
        }
        val versionJson = Json.parse(versionReply.body).asObj()
            ?: return StartLiveResult.Failure("${VERSION_ACTION}失败: 响应不是有效的 API 数据")
        if (versionJson["code"].asInt() != 0) {
            return StartLiveResult.Failure(apiMessage(versionJson, VERSION_ACTION))
        }
        val build = versionJson["data"]["build"].asLong()?.takeIf { it >= 1 }
            ?: return StartLiveResult.Failure("无效的 build 或 curr_version")
        val currentVersion = versionJson["data"]["curr_version"].asString().orEmpty()
        if (currentVersion.isEmpty()) return StartLiveResult.Failure("无效的 build 或 curr_version")

        val startParams = linkedMapOf(
            "room_id" to roomId.toString(),
            "platform" to "pc_link",
            "area_v2" to areaId.toString(),
            "backup_stream" to "0",
            "csrf_token" to csrf,
            "csrf" to csrf,
            "build" to build.toString(),
            "version" to currentVersion,
            "ts" to nowSeconds().toString()
        )
        val startBody = AppSigner.sign(startParams)
        val reply = post(liveUrl(START_LIVE_PATH), startBody, cookies)
            ?: return StartLiveResult.Failure("${START_ACTION}失败: 网络错误或超时")
        if (reply.status != 200) {
            return StartLiveResult.Failure("${START_ACTION}失败，HTTP 状态码: ${reply.status}")
        }
        val json = Json.parse(reply.body).asObj()
            ?: return StartLiveResult.Failure("${START_ACTION}失败: 响应不是有效的 API 数据")
        val code = json["code"].asInt()
            ?: return StartLiveResult.Failure("${START_ACTION}失败: 响应不是有效的 API 数据")
        if (code != 0) {
            when (code) {
                60024, 60028 -> return StartLiveResult.FaceAuthRequired(code, json["data"]["qr"].asString().orEmpty())
                60043 -> return StartLiveResult.FaceAuthRequired(code, faceAuthMiddleUrl(uid))
                else -> return StartLiveResult.Failure(apiMessage(json, START_ACTION))
            }
        }
        val addr = json["data"]["rtmp"]["addr"].asString().orEmpty()
        val rtmpCode = json["data"]["rtmp"]["code"].asString().orEmpty()
        if (addr.isEmpty() || rtmpCode.isEmpty()) {
            return StartLiveResult.Failure("开播成功，但响应中缺少 RTMP 地址或推流码")
        }
        return StartLiveResult.Success(addr = addr, code = rtmpCode)
    }

    /**
     * 轮询人脸验证是否完成。
     *
     * @return true 已完成 / false 未完成 / null 请求或响应无效（调用方应继续等待或提示）。
     */
    fun isFaceAuthDone(cookies: String, csrf: String, roomId: Long, faceAuthCode: Int): Boolean? {
        if (roomId <= 0 || csrf.isBlank() || faceAuthCode !in FACE_AUTH_CODES) return null
        val body = LoginCookies.buildFormBody(
            listOf(
                "room_id" to roomId.toString(),
                "face_auth_code" to faceAuthCode.toString(),
                "csrf_token" to csrf,
                "csrf" to csrf
            )
        )
        val reply = post(liveUrl(FACE_AUTH_STATUS_PATH), body, cookies) ?: return null
        if (reply.status != 200) return null
        val json = Json.parse(reply.body).asObj() ?: return null
        if (json["code"].asInt() != 0) return null
        return json["data"]["is_identified"].asBool()
    }

    /** 停播；true 表示接口确认成功。 */
    fun stopLive(cookies: String, csrf: String, roomId: Long): Boolean {
        if (roomId <= 0 || csrf.isBlank()) return false
        val body = LoginCookies.buildFormBody(
            listOf(
                "room_id" to roomId.toString(),
                "platform" to "pc_link",
                "csrf_token" to csrf,
                "csrf" to csrf
            )
        )
        val reply = post(liveUrl(STOP_LIVE_PATH), body, cookies) ?: return false
        if (reply.status != 200) return false
        val json = Json.parse(reply.body).asObj() ?: return false
        return json["code"].asInt() == 0
    }

    /** 更新直播间标题；true 表示接口确认成功。 */
    fun updateRoomTitle(cookies: String, csrf: String, roomId: Long, title: String): Boolean {
        if (roomId <= 0 || csrf.isBlank()) return false
        val body = LoginCookies.buildFormBody(
            listOf(
                "room_id" to roomId.toString(),
                "platform" to "pc_link",
                "title" to title,
                "csrf_token" to csrf,
                "csrf" to csrf
            )
        )
        val reply = post(liveUrl(UPDATE_ROOM_PATH), body, cookies) ?: return false
        if (reply.status != 200) return false
        val json = Json.parse(reply.body).asObj() ?: return false
        return json["code"].asInt() == 0
    }

    /** 直播状态：0 未开播 / 1 直播中 / 2 轮播；null 表示查询失败。 */
    fun liveStatus(uid: Long): Int? {
        if (uid <= 0) return null
        val query = "mid=" + LoginCookies.encodeComponent(uid.toString())
        val reply = get(withQuery(liveUrl(LIVE_STATUS_PATH), query)) ?: return null
        if (reply.status != 200) return null
        val json = Json.parse(reply.body).asObj() ?: return null
        if (json["code"].asInt() != 0) return null
        val status = json["data"]["liveStatus"].asInt() ?: return null
        if (status !in 0..2) return null
        return status
    }

    // ---------------------------------------------------------------- HTTP 基础设施

    private class HttpReply(
        val status: Int,
        val body: String,
        val setCookies: List<String>,
        val location: String?
    )

    private fun get(url: String, cookies: String? = null): HttpReply? {
        val builder = Request.Builder().url(url).get().headers(baseHeaders())
        if (!cookies.isNullOrBlank()) builder.header("Cookie", cookies)
        return execute(client, builder.build())
    }

    private fun post(url: String, body: String, cookies: String? = null): HttpReply? {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(FORM_MEDIA_TYPE))
            .headers(baseHeaders())
        if (!cookies.isNullOrBlank()) builder.header("Cookie", cookies)
        return execute(client, builder.build())
    }

    /** 执行请求；IOException（含超时）返回 null。 */
    private fun execute(client: OkHttpClient, request: Request): HttpReply? = try {
        client.newCall(request).execute().use { response ->
            HttpReply(
                status = response.code,
                body = response.body?.string().orEmpty(),
                setCookies = response.headers.values("Set-Cookie"),
                location = response.header("Location")
            )
        }
    } catch (e: IOException) {
        null
    }

    /**
     * GET 登录跳转地址换取 Set-Cookie；手动跟随最多 5 次跳转，每一跳都校验受信任 host，
     * 与参考实现 `getLoginTicket` 的安全策略一致。
     */
    private fun fetchLoginTicketCookies(url: String): String? {
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        var current = url
        var merged = ""
        repeat(MAX_LOGIN_TICKET_REDIRECTS) {
            if (!LoginCookies.isTrustedLoginUrl(current, endpoints.trustedLoginHosts, endpoints.allowInsecureLoginTicket)) {
                return null
            }
            val reply = execute(noRedirectClient, Request.Builder().url(current).get().headers(baseHeaders()).build())
                ?: return null
            merged = LoginCookies.merge(merged, LoginCookies.fromSetCookieHeaders(reply.setCookies))
            if (reply.status in 300..399) {
                val location = reply.location ?: return merged.ifEmpty { null }
                current = resolveUrl(current, location) ?: return null
            } else {
                return if (reply.status == 200) merged else null
            }
        }
        return null
    }

    private fun baseHeaders(): Headers = BASE_HEADERS

    private fun passportUrl(path: String): String = joinUrl(endpoints.passportBase, path)

    private fun webUrl(path: String): String = joinUrl(endpoints.webBase, path)

    private fun liveUrl(path: String): String = joinUrl(endpoints.liveBase, path)

    private fun nowSeconds(): Long = clock()

    private fun faceAuthMiddleUrl(uid: Long): String =
        FACE_AUTH_MIDDLE_URL + LoginCookies.encodeComponent(uid.toString())

    private fun apiMessage(json: JsonValue.Obj, action: String): String {
        val detail = firstNonBlank(json["message"].asString(), json["msg"].asString())
            ?: json["code"].asInt()?.takeIf { it != 0 }?.let { "错误码 $it" }
            ?: "响应字段无效"
        return "${action}失败: " + truncate(detail)
    }

    private fun dataMessage(data: JsonValue?, action: String, code: Int): String {
        val detail = firstNonBlank(data["message"].asString(), data["msg"].asString())
            ?: if (code != 0) "错误码 $code" else "响应字段无效"
        return "${action}失败: " + truncate(detail)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun truncate(value: String): String =
        if (value.length <= MAX_MESSAGE_LENGTH) value else value.substring(0, MAX_MESSAGE_LENGTH) + "…"

    companion object {
        /** 桌面 Chrome UA（与参考实现一致）。 */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

        private const val MAX_LOGIN_TICKET_REDIRECTS = 6
        private const val MAX_MESSAGE_LENGTH = 512

        private const val QR_GENERATE_PATH = "/x/passport-login/web/qrcode/generate"
        private const val QR_POLL_PATH = "/x/passport-login/web/qrcode/poll"
        private const val NAV_PATH = "/x/web-interface/nav"
        private const val ROOM_ID_BY_UID_PATH = "/room/v2/Room/room_id_by_uid"
        private const val ROOM_BASE_INFO_PATH = "/xlive/web-room/v1/index/getRoomBaseInfo"
        private const val AREA_LIST_PATH = "/room/v1/Area/getList"
        private const val LIVE_VERSION_PATH = "/xlive/app-blink/v1/liveVersionInfo/getHomePageLiveVersion"
        private const val START_LIVE_PATH = "/room/v1/Room/startLive"
        private const val FACE_AUTH_STATUS_PATH = "/xlive/app-blink/v1/preLive/IsUserIdentifiedByFaceAuth"
        private const val STOP_LIVE_PATH = "/room/v1/Room/stopLive"
        private const val UPDATE_ROOM_PATH = "/room/v1/Room/update"
        private const val LIVE_STATUS_PATH = "/room/v1/Room/getRoomInfoOld"

        private const val FACE_AUTH_MIDDLE_URL =
            "https://www.bilibili.com/blackboard/live/face-auth-middle.html?source_event=400&mid="

        private const val QR_POLL_ACTION = "检查二维码登录状态"
        private const val VERSION_ACTION = "获取直播版本信息"
        private const val START_ACTION = "启动直播"

        private val FACE_AUTH_CODES = setOf(60024, 60028, 60043)

        private val FORM_MEDIA_TYPE: MediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

        private val BASE_HEADERS: Headers = Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("Origin", "https://link.bilibili.com")
            .add("Referer", "https://link.bilibili.com/p/center/index")
            .add("Sec-Ch-Ua", "\"Microsoft Edge\";v=\"129\", \"Not=A?Brand\";v=\"8\", \"Chromium\";v=\"129\"")
            .add("Sec-Ch-Ua-Mobile", "?0")
            .add("Sec-Ch-Ua-Platform", "\"Windows\"")
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-site")
            .add("User-Agent", BROWSER_USER_AGENT)
            .build()

        private fun joinUrl(base: String, path: String): String =
            base.trimEnd('/') + "/" + path.trimStart('/')

        private fun withQuery(url: String, query: String): String =
            if (query.isEmpty()) url else "$url?$query"

        private fun resolveUrl(base: String, location: String): String? = try {
            base.toHttpUrlOrNull()?.resolve(location)?.toString()
        } catch (e: RuntimeException) {
            null
        }
    }
}

/**
 * API 基地址。默认 [PRODUCTION]；单元测试可全部指向 MockWebServer。
 *
 * @param trustedLoginHosts 允许 GET 登录跳转地址的 host（防 open redirect）
 * @param allowInsecureLoginTicket 测试用：允许 http 跳转地址（生产必须为 false）
 */
class BiliEndpoints(
    val passportBase: String,
    val webBase: String,
    val liveBase: String,
    val trustedLoginHosts: Set<String> = DEFAULT_TRUSTED_LOGIN_HOSTS,
    val allowInsecureLoginTicket: Boolean = false
) {
    companion object {
        val DEFAULT_TRUSTED_LOGIN_HOSTS: Set<String> = setOf(
            "passport.bilibili.com",
            "passport.biligame.com",
            "www.bilibili.com",
            "bilibili.com"
        )

        val PRODUCTION: BiliEndpoints = BiliEndpoints(
            passportBase = "https://passport.bilibili.com",
            webBase = "https://api.bilibili.com",
            liveBase = "https://api.live.bilibili.com",
            trustedLoginHosts = DEFAULT_TRUSTED_LOGIN_HOSTS
        )
    }
}

/** 生产环境允许取登录凭据的 host；默认参数的求值不依赖 BiliEndpoints 的初始化顺序。 */
private val DEFAULT_TRUSTED_LOGIN_HOSTS: Set<String> = setOf(
    "passport.bilibili.com",
    "passport.biligame.com",
    "www.bilibili.com",
    "bilibili.com"
)

private const val HTTP_TIMEOUT_SECONDS = 10L

private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()
