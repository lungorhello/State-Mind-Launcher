package org.example.statemind.core.auth

/**
 * 第三方登录的账号标识校验 —— **只收邮箱**。
 *
 * 皮肤站允许用用户名登录，这里统一收邮箱：玩家页卡片要显示「皮肤站短名 · 账号」，
 * 用户名和角色名长得太像，看不出是哪家的账号。
 */
object LoginEmail {

    private const val NAME_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._%+-"

    private const val HOST_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-"

    /** @return 格式不对时返回提示；**空输入返回 null** —— 空只是还没填完，由界面把按钮置灰。 */
    fun error(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 中文输入法的全角字符（＠、。）是最常见的坑，单独报这一句最省事
        if (text.any { it.code > 0x7E }) return "邮箱格式无效，只能使用英文字符"
        if (text.any { it.isWhitespace() }) return "邮箱格式无效，不能包含空格"

        val at = text.indexOf('@')
        if (at < 0) return "邮箱格式无效，缺少 @"
        if (text.indexOf('@', at + 1) >= 0) return "邮箱格式无效，只能有一个 @"

        val name = text.substring(0, at)
        val host = text.substring(at + 1)

        if (name.isEmpty() || name.any { it !in NAME_CHARS }) return "邮箱名无效，请检查拼写"
        if (name.startsWith('.') || name.endsWith('.') || name.contains("..")) {
            return "邮箱名无效，请检查拼写"
        }

        if (host.isEmpty() || !host.contains('.') || host.any { it !in HOST_CHARS }) {
            return "邮箱域名无效，请检查拼写"
        }
        if (host.startsWith('.') || host.endsWith('.') || host.contains("..")) {
            return "邮箱域名无效，请检查拼写"
        }
        if (host.substringAfterLast('.').length < 2) return "邮箱域名无效，请检查拼写"
        return null
    }

    fun isValid(raw: String): Boolean {
        val text = raw.trim()
        return text.isNotEmpty() && error(text) == null
    }
}
