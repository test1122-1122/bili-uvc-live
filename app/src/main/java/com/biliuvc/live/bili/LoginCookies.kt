package com.biliuvc.live.bili

/**
 * Cookie 处理工具：解析、合并扫码登录返回的凭据。
 *
 * 规则与参考实现 `ref/bilibili-stream-for-obs-main/src/core/login_utils.cpp` /
 * `api_utils.cpp` 保持一致：
 * - 只保留 SESSDATA / bili_jct / DedeUserID 三个必需 cookie（按固定顺序拼接）；
 * - cookie 值不得为空、不得含 ';'、不得含控制字符、长度不超过 8192；
 * - 多个来源按“后者覆盖前者”合并。
 */
internal object LoginCookies {
    const val SESSDATA = "SESSDATA"
    const val BILI_JCT = "bili_jct"
    const val DEDE_USER_ID = "DedeUserID"

    /** 必需 cookie 的顺序，也是最终 cookie 字符串的顺序。 */
    private val REQUIRED = listOf(SESSDATA, BILI_JCT, DEDE_USER_ID)

    private const val MAX_COOKIE_VALUE_LENGTH = 8192
    private const val MAX_URL_LENGTH = 16384

    /** 从 `a=b; c=d` 形式的 cookie 串中取值，找不到返回 null。 */
    fun extract(cookies: String?, key: String): String? {
        if (cookies.isNullOrEmpty()) return null
        var start = 0
        while (start <= cookies.length) {
            var end = cookies.indexOf(';', start)
            if (end < 0) end = cookies.length
            val item = cookies.substring(start, end).trim()
            val equals = item.indexOf('=')
            if (equals > 0 && item.substring(0, equals).trim() == key) {
                val value = item.substring(equals + 1).trim()
                return if (isSafeCookieValue(value)) value else null
            }
            if (end >= cookies.length) break
            start = end + 1
        }
        return null
    }

    /** 合并两个 cookie 串，仅保留必需项；[second] 中有效的值覆盖 [first]。 */
    fun merge(first: String?, second: String?): String {
        val values = LinkedHashMap<String, String>()
        for (source in listOf(first, second)) {
            if (source.isNullOrEmpty()) continue
            var start = 0
            while (start <= source.length) {
                var end = source.indexOf(';', start)
                if (end < 0) end = source.length
                val item = source.substring(start, end).trim()
                val equals = item.indexOf('=')
                if (equals > 0) {
                    val name = item.substring(0, equals).trim()
                    val value = item.substring(equals + 1).trim()
                    if (name in REQUIRED && isSafeCookieValue(value)) values[name] = value
                }
                if (end >= source.length) break
                start = end + 1
            }
        }
        return buildCookieString(values)
    }

    /**
     * 从响应头的 Set-Cookie 值列表聚合必需 cookie。
     *
     * 每个元素是一条完整的 Set-Cookie 头（如 `SESSDATA=abc; Path=/; Domain=.bilibili.com`），
     * 只取第一个 ';' 之前的 name=value。
     */
    fun fromSetCookieHeaders(setCookieHeaders: List<String>): String {
        val values = LinkedHashMap<String, String>()
        for (header in setCookieHeaders) {
            if (header.isEmpty()) continue
            val pair = header.substringBefore(';').trim()
            val equals = pair.indexOf('=')
            if (equals <= 0) continue
            val name = pair.substring(0, equals).trim()
            val value = pair.substring(equals + 1).trim()
            if (name in REQUIRED && isSafeCookieValue(value)) values[name] = value
        }
        return buildCookieString(values)
    }

    /** 三个必需 cookie 是否都齐全且合法。 */
    fun isComplete(cookies: String?): Boolean {
        if (cookies.isNullOrEmpty()) return false
        return REQUIRED.all { extract(cookies, it) != null }
    }

    /**
     * 从登录跳转地址（`data.url`）的 query 里提取凭据（旧版 crossDomain 地址）。
     * 地址不受信任时返回空串；值保持原始（不 percent-decode），与浏览器 cookie 一致。
     */
    fun fromLoginUrl(url: String, trustedHosts: Set<String>, allowInsecure: Boolean): String {
        if (!isTrustedLoginUrl(url, trustedHosts, allowInsecure)) return ""
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart == url.length - 1) return ""
        var query = url.substring(queryStart + 1)
        val hash = query.indexOf('#')
        if (hash >= 0) query = query.substring(0, hash)
        val values = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val equals = part.indexOf('=')
            if (equals <= 0) continue
            val name = part.substring(0, equals)
            val value = part.substring(equals + 1)
            if (name in REQUIRED && isSafeCookieValue(value)) values[name] = value
        }
        return buildCookieString(values)
    }

    /**
     * 校验登录跳转地址：
     * 必须是 https（测试环境可通过 [allowInsecure] 放行 http），host 在 [trustedHosts] 内，
     * 无 userinfo、无 fragment，长度受限。参考 `login_utils.cpp::trustedLoginUrl`。
     */
    fun isTrustedLoginUrl(url: String, trustedHosts: Set<String>, allowInsecure: Boolean): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_LENGTH) return false
        for (ch in url) {
            if (ch.code <= 32 || ch.code == 127 || ch == '\\') return false
        }
        if (url.contains('#')) return false
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (allowInsecure) {
            if (scheme != "https" && scheme != "http") return false
        } else if (scheme != "https") {
            return false
        }
        var authority = url.substring(schemeEnd + 3)
        val pathStart = authority.indexOf('/')
        if (pathStart >= 0) authority = authority.substring(0, pathStart)
        val queryStart = authority.indexOf('?')
        if (queryStart >= 0) authority = authority.substring(0, queryStart)
        if (authority.isEmpty() || authority.contains('@')) return false
        var host = authority
        var port = ""
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            host = authority.substring(0, colon)
            port = authority.substring(colon + 1)
            if (port.isNotEmpty() && port.toIntOrNull() == null) return false
        }
        if (host.isEmpty()) return false
        if (!allowInsecure && port.isNotEmpty() && port != "443") return false
        return trustedHosts.any { it.equals(host, ignoreCase = true) }
    }

    /** percent-encode 单个 form 组件；与参考实现一致：仅 A-Z a-z 0-9 - . _ ~ 不编码，十六进制大写。 */
    fun encodeComponent(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%')
                out.append(HEX[code shr 4])
                out.append(HEX[code and 0x0F])
            }
        }
        return out.toString()
    }

    /** 按给定顺序拼接 form-urlencoded body；不做排序、不签名。 */
    fun buildFormBody(params: Iterable<Pair<String, String>>): String =
        params.joinToString("&") { (key, value) -> encodeComponent(key) + "=" + encodeComponent(value) }

    private fun isSafeCookieValue(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_COOKIE_VALUE_LENGTH) return false
        if (value.contains(';')) return false
        return value.none { it.code <= 32 || it.code == 127 }
    }

    private fun buildCookieString(values: Map<String, String>): String {
        val out = StringBuilder()
        for (name in REQUIRED) {
            val value = values[name] ?: continue
            if (out.isNotEmpty()) out.append("; ")
            out.append(name).append('=').append(value)
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
