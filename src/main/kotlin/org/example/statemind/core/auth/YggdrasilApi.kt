package org.example.statemind.core.auth

import org.example.statemind.core.MiniJson

/** 一个角色。[id] 是**无横线**的 UUID。 */
data class Profile(val id: String, val name: String)

/** 一次登录 / 刷新之后拿到的凭证。[selected] 还没选角色时是 null。 */
data class Session(
    val accessToken: String,
    val clientToken: String,
    val selected: Profile?,
    val available: List<Profile>,
    val userId: String?,
)

/**
 * Yggdrasil 协议本身 —— 跟具体是哪家服务器无关（LittleSkin、自建皮肤站都走这一套）。
 *
 * 令牌要持久化：只有同一份 clientToken 才能继续 refresh。
 */
object YggdrasilApi {

    /** 请求里的 agent 字段，官方规定就是这两个值。 */
    private const val AGENT_NAME = "Minecraft"
    private const val AGENT_VERSION = 1

    fun authenticate(
        server: AuthServer,
        username: String,
        password: String,
        clientToken: String,
    ): Session {
        val body = post(
            server, "authserver/authenticate", linkedMapOf(
                "agent" to linkedMapOf("name" to AGENT_NAME, "version" to AGENT_VERSION),
                "username" to username,
                "password" to password,
                "clientToken" to clientToken,
                "requestUser" to true,
            )
        )
        return toSession(body, clientToken)
    }

    /**
     * 用旧令牌换新令牌。给了 [select] 就顺带绑定角色 —— 官方规范要求响应里选中的正是它。
     *
     * @throws AuthException [AuthError.BAD_CREDENTIALS] 令牌彻底失效，要请用户重输密码
     */
    fun refresh(
        server: AuthServer,
        accessToken: String,
        clientToken: String,
        select: Profile? = null,
    ): Session {
        val req = linkedMapOf<String, Any?>(
            "accessToken" to accessToken,
            "clientToken" to clientToken,
            "requestUser" to true,
        )
        if (select != null) {
            req["selectedProfile"] = linkedMapOf("id" to select.id, "name" to select.name)
        }

        val session = toSession(post(server, "authserver/refresh", req), clientToken)
        if (select != null && session.selected?.id != select.id) {
            throw AuthException(AuthError.MALFORMED, "认证服务器没有按预期选中角色。")
        }
        return session
    }

    /** 令牌有效吗。无效返回 false；网络问题仍然抛 [AuthException]。 */
    fun validate(server: AuthServer, accessToken: String, clientToken: String? = null): Boolean {
        val req = linkedMapOf<String, Any?>("accessToken" to accessToken)
        if (clientToken != null) req["clientToken"] = clientToken
        return try {
            post(server, "authserver/validate", req)
            true
        } catch (e: AuthException) {
            if (e.error == AuthError.BAD_CREDENTIALS) false else throw e
        }
    }

    /** 让令牌作废（登出）。令牌本来就无效时当作成功。 */
    fun invalidate(server: AuthServer, accessToken: String, clientToken: String? = null) {
        val req = linkedMapOf<String, Any?>("accessToken" to accessToken)
        if (clientToken != null) req["clientToken"] = clientToken
        try {
            post(server, "authserver/invalidate", req)
        } catch (e: AuthException) {
            if (e.error != AuthError.BAD_CREDENTIALS) throw e
        }
    }

    /**
     * 完整登录流程：authenticate →（多角色时交给 [choose]）→ refresh 绑定角色。
     *
     * [choose] 返回 null 表示用户放弃选择：会话仍然有效，只是没选中角色。
     *
     * @throws AuthException [AuthError.NO_PROFILE] 账号下一个角色都没有
     */
    fun login(
        server: AuthServer,
        username: String,
        password: String,
        clientToken: String,
        choose: (List<Profile>) -> Profile? = { it.firstOrNull() },
    ): Session {
        val session = authenticate(server, username, password, clientToken)
        session.selected?.let { return session }

        if (session.available.isEmpty()) {
            throw AuthException(
                AuthError.NO_PROFILE,
                "这个账号在「${server.name}」上还没有角色，先去它的网站上创建一个角色再来登录。"
            )
        }

        val pick = choose(session.available) ?: return session
        return refresh(server, session.accessToken, session.clientToken, pick)
    }

    private fun post(server: AuthServer, path: String, body: Map<String, Any?>): Map<String, Any?> {
        val res = try {
            Http.postJson(server.apiRoot + path, MiniJson.write(body))
        } catch (e: Exception) {
            throw AuthException(
                AuthError.UNREACHABLE,
                "连不上认证服务器「${server.name}」，检查一下网络。",
                cause = e
            )
        }

        val parsed = parseObject(res.body)
        (parsed["error"] as? String)?.let { throw fromServerError(it, parsed) }

        if (res.status >= 400) {
            throw AuthException(
                AuthError.REJECTED,
                "认证服务器拒绝了这次请求（HTTP ${res.status}）。"
            )
        }
        return parsed
    }

    /** validate / invalidate 返回空正文，是合法的。 */
    private fun parseObject(body: String): Map<String, Any?> {
        if (body.isBlank()) return emptyMap()
        val map = try {
            MiniJson.parse(body) as? Map<*, *>
        } catch (e: Exception) {
            throw AuthException(AuthError.MALFORMED, "认证服务器返回了看不懂的内容。", cause = e)
        } ?: throw AuthException(AuthError.MALFORMED, "认证服务器返回了看不懂的内容。")
        return map.entries.associate { it.key.toString() to it.value }
    }

    /** 官方定义的错误对象：`{error, errorMessage, cause}`。 */
    private fun fromServerError(error: String, body: Map<String, Any?>): AuthException {
        val message = (body["errorMessage"] as? String)?.takeIf { it.isNotBlank() }
        return when (error) {
            "ForbiddenOperationException" -> AuthException(
                AuthError.BAD_CREDENTIALS, "账号或密码不对。", error, message
            )

            else -> AuthException(
                AuthError.REJECTED,
                message ?: "认证服务器拒绝了这次请求。",
                error, message
            )
        }
    }

    private fun toSession(body: Map<String, Any?>, clientToken: String): Session {
        val access = body["accessToken"] as? String
            ?: throw AuthException(AuthError.MALFORMED, "认证服务器没有返回登录令牌。")

        // 服务器必须原样回我们发过去的 clientToken，不然刷新时会接不上
        val returned = body["clientToken"] as? String
        if (returned != null && returned != clientToken) {
            throw AuthException(AuthError.MALFORMED, "认证服务器返回了不匹配的 clientToken。")
        }

        val available = (body["availableProfiles"] as? List<*>)
            ?.mapNotNull { toProfile(it) }
            ?: emptyList()

        return Session(
            accessToken = access,
            clientToken = returned ?: clientToken,
            selected = toProfile(body["selectedProfile"]),
            available = available,
            userId = (body["user"] as? Map<*, *>)?.get("id") as? String,
        )
    }

    private fun toProfile(value: Any?): Profile? {
        val map = value as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val name = map["name"] as? String ?: return null
        return Profile(id, name)
    }
}
