package org.example.statemind.core

/**
 * 迷你 JSON 读写 —— 只用标准库，不引第三方依赖。
 *
 * 读的是游戏版本文件、认证服务器的响应这类结构简单的 JSON；写的是登录请求体。
 * 解析结果用最朴素的类型表示，取用时直接强转：
 *  - 对象 → `Map<String, Any?>`（保序）
 *  - 数组 → `List<Any?>`
 *  - 字符串 / 布尔 / null 原样；整数 → `Long`，带小数点或指数 → `Double`
 *
 * 原先藏在 [LaunchUtil] 里，2026-09-25 抽出来共用（第三方登录也要读写 JSON）。
 */
internal object MiniJson {

        fun parse(text: String): Any? {
            val p = Parser(text)
            return p.value()
        }

        private class Parser(private val s: String) {
            private var i = 0

            fun skipWs() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun value(): Any? {
                skipWs()
                if (i >= s.length) throw IllegalStateException("JSON 意外结束")
                return when (s[i]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> lit("true", true)
                    'f' -> lit("false", false)
                    'n' -> lit("null", null)
                    else -> num()
                }
            }

            private fun lit(word: String, v: Any?): Any? {
                if (!s.startsWith(word, i)) throw IllegalStateException("JSON 位置 $i 期望 $word")
                val end = i + word.length
                if (end < s.length && s[end].isLetterOrDigit()) {
                    throw IllegalStateException("JSON 位置 $i 非法字面量")
                }
                i = end
                return v
            }

            private fun obj(): Map<String, Any?> {
                val m = LinkedHashMap<String, Any?>()
                i++                       // {
                skipWs()
                if (i < s.length && s[i] == '}') { i++; return m }
                while (true) {
                    skipWs()
                    val k = str()
                    skipWs()
                    if (i >= s.length || s[i] != ':') throw IllegalStateException("JSON 位置 $i 期望 ':'")
                    i++
                    m[k] = value()
                    skipWs()
                    if (i >= s.length) throw IllegalStateException("JSON 意外结束")
                    when (s[i]) {
                        ',' -> i++
                        '}' -> { i++; return m }
                        else -> throw IllegalStateException("JSON 位置 $i 期望 ',' 或 '}'")
                    }
                }
            }

            private fun arr(): List<Any?> {
                val l = ArrayList<Any?>()
                i++                       // [
                skipWs()
                if (i < s.length && s[i] == ']') { i++; return l }
                while (true) {
                    l.add(value())
                    skipWs()
                    if (i >= s.length) throw IllegalStateException("JSON 意外结束")
                    when (s[i]) {
                        ',' -> i++
                        ']' -> { i++; return l }
                        else -> throw IllegalStateException("JSON 位置 $i 期望 ',' 或 ']'")
                    }
                }
            }

            private fun str(): String {
                if (i >= s.length || s[i] != '"') throw IllegalStateException("JSON 位置 $i 期望字符串")
                i++
                val sb = StringBuilder()
                while (true) {
                    if (i >= s.length) throw IllegalStateException("JSON 字符串没闭合")
                    val c = s[i]
                    when {
                        c == '"' -> { i++; return sb.toString() }
                        c == '\\' -> {
                            i++
                            if (i >= s.length) throw IllegalStateException("JSON 转义不完整")
                            when (val e = s[i]) {
                                '"', '\\', '/' -> { sb.append(e); i++ }
                                'b' -> { sb.append('\b'); i++ }
                                'f' -> { sb.append('\u000C'); i++ }
                                'n' -> { sb.append('\n'); i++ }
                                'r' -> { sb.append('\r'); i++ }
                                't' -> { sb.append('\t'); i++ }
                                'u' -> {
                                    if (i + 5 > s.length) throw IllegalStateException("JSON \\u 转义不完整")
                                    val hex = s.substring(i + 1, i + 5)
                                    val code = hex.toIntOrNull(16)
                                        ?: throw IllegalStateException("JSON 非法 \\u 转义：\\u$hex")
                                    sb.append(code.toChar())
                                    i += 5
                                }
                                else -> throw IllegalStateException("JSON 非法转义 \\$e")
                            }
                        }
                        else -> { sb.append(c); i++ }
                    }
                }
            }

            /** 整数给 Long、带小数点或指数才给 Double，别一律 Double（那样 as? Int 永远拿不到）。 */
            private fun num(): Any {
                val start = i
                if (i < s.length && s[i] == '-') i++
                while (i < s.length && s[i].isDigit()) i++
                var fractional = false
                if (i < s.length && s[i] == '.') {
                    fractional = true
                    i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                    fractional = true
                    i++
                    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                val text = s.substring(start, i)
                if (text.isEmpty() || text == "-") {
                    throw IllegalStateException("JSON 位置 $start 不是合法的值")
                }
                return if (fractional) text.toDouble()
                else text.toLongOrNull() ?: text.toDouble()
            }
        }
    // ---------- 写入（拼请求体用） ----------

    /** 把 map / list / 基本类型拼成 JSON 文本。 */
    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeTo(sb, value)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                value.forEach { (k, v) ->
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                value.forEach {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, it)
                }
                sb.append(']')
            }
            is Number -> sb.append(value.toString())
            else -> quote(sb, value.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
