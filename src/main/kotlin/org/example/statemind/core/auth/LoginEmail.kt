package org.example.statemind.core.auth

/**
 * 第三方登录的账号标识校验 —— **只收邮箱**。
 *
 * 皮肤站（LittleSkin 之类）自己允许用「用户名」登录，但启动器这边统一按邮箱收：
 * 玩家页那张卡片的小字要显示「皮肤站短名 · 账号」，用户名长得跟角色名差不多，
 * 一眼看不出是哪家的账号、也确认不了填对没有（用户 2026-09-26 拍板）。
 *
 * 纯函数、不碰网络：界面一边打字一边调 [error] 做实时校验。
 */
object LoginEmail {

    /** 邮箱名（`@` 左边）允许的字符。 */
    private const val NAME_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._%+-"

    /** 域名（`@` 右边）允许的字符。 */
    private const val HOST_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-"

    /**
     * 校验一个邮箱。
     *
     * @return 格式不对时返回一句提示；**空输入返回 null** —— 空只是「还没填完」，
     *         界面靠把按钮置灰来处理，不必先报一条。
     *
     * 提示文案一律**陈述句、不带语气词、不举例子**（用户 2026-09-26 明确），
     * 比如「邮箱名无效，请检查拼写」；像「别用中文」这种口语说法不要写进界面。
     */
    fun error(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 中文输入法下的全角字符是最常见的坑（＠、。），单独报这一句最省事
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

    /** 格式对不对。空输入算「还没填」，返回 false。 */
    fun isValid(raw: String): Boolean {
        val text = raw.trim()
        return text.isNotEmpty() && error(text) == null
    }
}
