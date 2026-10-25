package com.biliuvc.live.bili

import android.content.Context

/**
 * 登录会话持久化（SharedPreferences + JSON）。
 *
 * 这是 `com.biliuvc.live.bili` 中**唯一**允许使用 `android.*` 的文件，
 * 其余类保持纯 JVM 可测。凭据仅存本机 SharedPreferences，不上传、不硬编码。
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取已保存的会话；没有或数据损坏时返回 null。 */
    fun load(): BiliSession? = SessionCodec.decode(prefs.getString(KEY_SESSION, null))

    /** 保存会话（覆盖旧值）。 */
    fun save(s: BiliSession) {
        prefs.edit().putString(KEY_SESSION, SessionCodec.encode(s)).apply()
    }

    /** 清除会话（退出登录 / 登录失效）。 */
    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    companion object {
        const val PREFS_NAME = "bili_session"
        private const val KEY_SESSION = "session_json"
    }
}
