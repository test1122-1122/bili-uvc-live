package com.biliuvc.live.bili

import java.security.MessageDigest

/**
 * B 站 App 端 API 签名（appkey + appsecret + MD5），与参考实现
 * `ref/bilibili-stream-for-obs-main/src/bilibili_api.cpp::BiliApi::appsign` 一致。
 *
 * 算法：
 * 1. 在参数中补上 `appkey`；
 * 2. 按 key 升序排序；
 * 3. 每个 k/v 做 form percent-encode（仅 A-Z a-z 0-9 - . _ ~ 保留，十六进制大写），
 *    用 `&` 连成 `k=v&...`；
 * 4. 末尾拼接 appsecret 后取 MD5 小写十六进制；
 * 5. 返回 `排序后的k=v&...&sign=<md5>`（即完整 POST body / GET query）。
 *
 * 返回值为可直接发送的完整表单字符串（已包含 appkey 与 sign），
 * 不要再额外拼接 appkey/sign。
 */
object AppSigner {
    const val APP_KEY = "aae92bc66f3edfab"
    const val APP_SECRET = "af125a0d5279fd576c1b4418a3e8276d"

    fun sign(params: LinkedHashMap<String, String>): String {
        // TreeMap：按 key 升序，语义等价于参考实现中对 pair 列表的 std::sort。
        val sorted = java.util.TreeMap<String, String>()
        sorted.putAll(params)
        sorted[APP_KEY_NAME] = APP_KEY
        val query = sorted.entries.joinToString("&") { (key, value) ->
            LoginCookies.encodeComponent(key) + "=" + LoginCookies.encodeComponent(value)
        }
        val signature = md5Hex(query + APP_SECRET)
        return query + "&" + SIGN_NAME + "=" + signature
    }

    private const val APP_KEY_NAME = "appkey"
    private const val SIGN_NAME = "sign"
}

/** MD5 小写十六进制。 */
internal fun md5Hex(text: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        out.append(HEX[value ushr 4])
        out.append(HEX[value and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
