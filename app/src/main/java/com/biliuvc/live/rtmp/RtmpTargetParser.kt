package com.biliuvc.live.rtmp

/**
 * Parser for RTMP publish endpoints.
 *
 * Bilibili `startLive` returns a split endpoint:
 *  - `addr` = `rtmp://live-push.bilivideo.com/live-bvc/`
 *  - `code` = `?streamname=live_xxx&key=yyy`
 *
 * The concatenated URL is `rtmp://live-push.bilivideo.com/live-bvc/?streamname=live_xxx&key=yyy`
 * where the app is `live-bvc` and the stream name **keeps its leading `?`**
 * (`?streamname=live_xxx&key=yyy`), matching OBS behaviour.
 */
object RtmpTargetParser {

    private const val DEFAULT_RTMP_PORT = 1935
    private const val DEFAULT_RTMPS_PORT = 443

    /**
     * Concatenates a Bilibili `addr` + `code` pair and parses it.
     * Returns `null` for blank/invalid input; never throws.
     */
    fun fromParts(addr: String, code: String): RtmpTarget? {
        return try {
            val address = addr.trim()
            val streamCode = code.trim()
            if (address.isEmpty() || streamCode.isEmpty()) return null
            if (!address.contains("://")) return null
            parse(address.trimEnd('/') + "/" + streamCode)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Parses a complete publish URL. Supports both
     * `rtmp://host/app/stream` and `rtmp://host/app/?query=1` forms.
     * Returns `null` when the URL is invalid or has no stream name; never throws.
     */
    fun parse(url: String): RtmpTarget? {
        return try {
            parseInternal(url)
        } catch (t: Throwable) {
            null
        }
    }

    private fun parseInternal(url: String): RtmpTarget? {
        val raw = url.trim()
        if (raw.isEmpty()) return null

        val schemeSeparator = raw.indexOf("://")
        if (schemeSeparator == 0) return null
        val scheme: String
        val remainder: String
        if (schemeSeparator < 0) {
            // Tolerate scheme-less input such as `live-push.bilivideo.com/live-bvc/stream`.
            scheme = "rtmp"
            remainder = raw
        } else {
            scheme = raw.substring(0, schemeSeparator).lowercase()
            remainder = raw.substring(schemeSeparator + 3)
        }
        if (scheme != "rtmp" && scheme != "rtmps") return null
        if (remainder.isEmpty()) return null

        val defaultPort = if (scheme == "rtmps") DEFAULT_RTMPS_PORT else DEFAULT_RTMP_PORT

        // ---- authority ----
        var authorityEnd = remainder.length
        for (i in remainder.indices) {
            val ch = remainder[i]
            if (ch == '/' || ch == '?' || ch == '#') {
                authorityEnd = i
                break
            }
        }
        var authority = remainder.substring(0, authorityEnd)
        if (authority.isEmpty()) return null
        // Ignore user info (`user:pass@host`).
        if (authority.contains('@')) authority = authority.substringAfterLast('@')
        if (authority.isEmpty()) return null

        var host: String
        var port = defaultPort
        var explicitPort = false
        if (authority.startsWith("[")) {
            // IPv6 literal, e.g. [::1]:1935
            val closing = authority.indexOf(']')
            if (closing < 0) return null
            host = authority.substring(0, closing + 1)
            val after = authority.substring(closing + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith(":")) return null
                port = after.substring(1).toIntOrNull() ?: return null
                explicitPort = true
            }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).toIntOrNull() ?: return null
                explicitPort = true
            } else {
                host = authority
            }
        }
        if (host.isEmpty()) return null
        if (port !in 1..65535) return null

        // ---- path / query ----
        var pathAndQuery = remainder.substring(authorityEnd)
        val fragment = pathAndQuery.indexOf('#')
        if (fragment >= 0) pathAndQuery = pathAndQuery.substring(0, fragment)
        var path = pathAndQuery
        while (path.startsWith("/")) path = path.substring(1)
        if (path.isEmpty()) return null

        val firstSlash = path.indexOf('/')
        val app: String
        val rest: String
        if (firstSlash < 0) {
            app = path
            rest = ""
        } else {
            app = path.substring(0, firstSlash)
            rest = path.substring(firstSlash + 1)
        }
        if (app.isEmpty()) return null
        // Keep the leading '?' of the query, strip exactly the separator slash(es) before it.
        val streamName = rest
        if (streamName.isEmpty()) return null

        val tcUrl = buildString {
            append(scheme).append("://").append(host)
            if (explicitPort && port != defaultPort) append(':').append(port)
            append('/').append(app)
        }

        return RtmpTarget(
            scheme = scheme,
            host = host,
            port = port,
            app = app,
            streamName = streamName,
            tcUrl = tcUrl,
            originalUrl = raw
        )
    }
}
