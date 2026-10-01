package com.videodl.app.ytdlp

/** 只截取脚本中的 JSON 对象；字符串里的括号和转义不会提前结束截取。 */
object SharePageJson {
    fun extract(html: String): String? {
        val marker = Regex("window\\._ROUTER_DATA\\s*=\\s*").find(html) ?: return null
        val start = marker.range.last + 1
        if (html.getOrNull(start) != '{') return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else {
                when (c) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
