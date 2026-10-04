package org.example.statemind.core.auth

import org.example.statemind.core.MiniJson
import java.net.URI

/**
 * 一个认证服务器（Yggdrasil 服务器）：第三方账号挂在某个服务器上，比如 LittleSkin。
 * 用户通常只输 `littleskin.cn` 这样的短地址，由 [locate] 解析成真正的 API 根。
 *
 * @param apiRoot 规范化后的 API 根地址，**一定以 `/` 结尾**
 */
data class AuthServer(
    val apiRoot: String,
    val name: String,
    val nonEmailLogin: Boolean,
) {
    /** 明文 http 的服务器：界面要提示账号密码会明文传输。 */
    val insecure: Boolean get() = apiRoot.startsWith("http://")

    companion object {

        /** 预置服务器：LittleSkin。 */
        const val LITTLESKIN = "https://littleskin.cn/api/yggdrasil/"

        private const val ALI_HEADER = "X-Authlib-Injector-API-Location"

        /** @throws AuthException 地址不合法、连不上，或响应不是 Yggdrasil 的 metadata */
        fun locate(input: String): AuthServer {
            val typed = normalizeInput(input)
            val first = try {
                Http.get(typed)
            } catch (e: Exception) {
                throw AuthException(
                    AuthError.UNREACHABLE,
                    "连不上这个地址，检查一下网络或者换个地址试试。",
                    cause = e
                )
            }

            // ALI：响应头指哪儿就去哪儿（可能是绝对地址，也可能是相对地址）
            val ali = first.header(ALI_HEADER)
            val root = withTrailingSlash(
                if (ali.isNullOrBlank()) first.uri else resolveAgainst(first.uri, ali)
            )

            // 头里给的地址和实际请求的地址不同 → 得重新请求一次，才能拿到真正的 metadata
            val body = if (sameIgnoringSlash(root, first.uri)) first.body else try {
                Http.get(root).body
            } catch (e: Exception) {
                throw AuthException(
                    AuthError.UNREACHABLE,
                    "连不上这个地址，检查一下网络或者换个地址试试。",
                    cause = e
                )
            }

            return parseMetadata(root, body)
        }

        /** 缺协议补 https —— 官方规范不允许降级成明文 http。 */
        private fun normalizeInput(raw: String): String {
            val text = raw.trim().trimEnd('/')
            if (text.isEmpty()) throw AuthException(AuthError.UNREACHABLE, "请先填认证服务器的地址。")
            return if (text.contains("://")) text else "https://$text"
        }

        private fun withTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

        private fun resolveAgainst(base: String, ali: String): String =
            runCatching { URI(base).resolve(ali.trim()).toString() }.getOrDefault(ali.trim())

        private fun sameIgnoringSlash(a: String, b: String) =
            a.trimEnd('/') == b.trimEnd('/')

        private fun parseMetadata(apiRoot: String, body: String): AuthServer {
            val root = try {
                MiniJson.parse(body) as? Map<*, *>
            } catch (e: Exception) {
                throw AuthException(
                    AuthError.MALFORMED,
                    "这个地址不像是一个认证服务器。",
                    cause = e
                )
            } ?: throw AuthException(AuthError.MALFORMED, "这个地址不像是一个认证服务器。")

            val meta = root["meta"] as? Map<*, *>
            if (meta == null) {
                throw AuthException(AuthError.MALFORMED, "这个地址不像是一个认证服务器。")
            }

            val name = (meta["serverName"] as? String)?.takeIf { it.isNotBlank() } ?: hostOf(apiRoot)
            val nonEmail = readNonEmailLogin(meta)
            return AuthServer(apiRoot, name, nonEmail)
        }

        /**
         * `feature.non_email_login` 在 LittleSkin 的响应里是 meta 下的**扁平键**，
         * 但规范也允许多包一层 `feature` 对象，两种都认。
         */
        private fun readNonEmailLogin(meta: Map<*, *>): Boolean {
            (meta["feature.non_email_login"] as? Boolean)?.let { return it }
            val feature = meta["feature"] as? Map<*, *>
            return feature?.get("non_email_login") as? Boolean ?: false
        }

        private fun hostOf(url: String): String =
            runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
    }
}
