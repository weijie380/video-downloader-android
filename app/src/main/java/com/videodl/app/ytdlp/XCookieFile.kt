package com.videodl.app.ytdlp

/** 只接收 X 的会话 Cookie，不保留导入文件中其他网站的数据。 */
object XCookieFile {
    private val names = setOf("auth_token", "ct0", "twid")

    fun fromHeader(header: String): String {
        require(header.none { it.isISOControl() }) { "登录 Cookie 格式无效，请重新登录" }
        return normalize(buildString {
            append("# Netscape HTTP Cookie File\n")
            header.split(';').forEach { item ->
                val name = item.substringBefore('=').trim()
                val value = item.substringAfter('=', "").trim()
                if (name in names) append(".x.com\tTRUE\t/\tTRUE\t0\t$name\t$value\n")
            }
        })
    }

    fun normalize(raw: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        require(raw.length <= 262144) { "Cookie 文件过大，请仅导出 X 的 Cookie" }
        val rows = linkedMapOf<String, String>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.removePrefix("#HttpOnly_")
            if (line.isBlank() || line.startsWith('#')) return@forEach
            val fields = line.split('\t')
            if (fields.size != 7) return@forEach
            val domain = fields[0].removePrefix(".").lowercase()
            // yt-dlp 的认证接口使用 api.x.com；兼容旧 twitter.com 导出的会话。
            if (domain !in setOf("x.com", "twitter.com")) return@forEach
            val name = fields[5]
            val value = fields[6]
            val expires = fields[4].toLongOrNull() ?: return@forEach
            if (name !in names || expires < 0 || (expires != 0L && expires <= nowSeconds)) return@forEach
            if (value.isBlank() || value.any { it.isWhitespace() || it.isISOControl() }) return@forEach
            rows[name] = ".x.com\tTRUE\t/\tTRUE\t$expires\t$name\t$value"
        }
        require(rows.containsKey("auth_token") && rows.containsKey("ct0")) {
            "没有找到有效的 X 登录 Cookie（auth_token 和 ct0），请先登录 X 再导出"
        }
        return "# Netscape HTTP Cookie File\n" + rows.values.joinToString("\n", postfix = "\n")
    }
}
