package com.biliuvc.live.bili

/**
 * [BiliSession] 与 JSON 文本之间的编解码（纯 Kotlin，便于 JVM 单元测试）。
 *
 * [SessionStore] 只负责把它读写到 SharedPreferences。
 */
internal object SessionCodec {
    fun encode(session: BiliSession): String = buildString {
        append('{')
        append("\"cookies\":").append(Json.quote(session.cookies)).append(',')
        append("\"uid\":").append(session.uid).append(',')
        append("\"uname\":").append(Json.quote(session.uname)).append(',')
        append("\"roomId\":").append(session.roomId)
        append('}')
    }

    /** 解析失败、字段缺失或 uid 无效时返回 null。 */
    fun decode(text: String?): BiliSession? {
        if (text.isNullOrBlank()) return null
        val obj = Json.parse(text).asObj() ?: return null
        val cookies = obj["cookies"].asString().orEmpty()
        if (cookies.isBlank()) return null
        val uid = obj["uid"].asLong() ?: return null
        if (uid <= 0) return null
        val uname = obj["uname"].asString().orEmpty()
        val roomId = obj["roomId"].asLong() ?: 0L
        return BiliSession(cookies = cookies, uid = uid, uname = uname, roomId = roomId)
    }
}
