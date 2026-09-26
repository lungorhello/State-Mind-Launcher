package org.example.statemind.core.auth

import org.example.statemind.core.MiniJson

/** 一个角色（Yggdrasil 的 profile）。[id] 是**无横线**的 UUID 字符串。 */
data class Profile(val id: String, val name: String)

/**
 * 一次登录 / 刷新之后拿到的凭证。
 *
 * @param selected  本次选中的角色；还没选就是 null
 * @param available 这个账号下的全部角色（有的服务器允许一个账号多个角色）
 */
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
 * 用到的四个端点（都挂在 [AuthServer.apiRoot] 下）：
 *  - `authserver/authenticate`：账号密码换令牌
 *  - `authserver/refresh`：旧令牌换新令牌，也能顺带绑定角色
 *  - `authserver/validate`：令牌还有效吗
 *  - `authserver/invalidate`：令牌作废（登出这台设备）
 *
 * 令牌要**持久化**（同一份 clientToken 才能继续 refresh），但**绝不落盘密码**。
 * 具体怎么存、怎么给界面用，不归这里管；这里只管协议。
 */
object YggdrasilApi {

    /** 请求里的 agent 字段，官方规定写死这样。 */
    private const val AGENT_NAME = "Minecraft"
    private const val AGENT_VERSION = 1

    /** 账号密码换令牌。 */
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
     * 用旧令牌换新令牌。给了 [select] 就顺带绑定这个角色（官方规范要求响应里选中的正是它）。
     *
     * @throws AuthException [AuthError.BAD_CREDENTIALS] 令牌彻底失效时——这时要请用户重新输密码
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

    /**
     * 令牌还有效吗。令牌失效返回 false（不算错误）；网络问题照旧抛 [AuthException]。
     */
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

    /** 让令牌作废（登出这台设备）。令牌本来就已经无效的话，当作成功。 */
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
     * 完整的登录流程：authenticate →（有多个角色时交给 [choose] 选）→ refresh 绑定角色。
     *
     * [choose] 拿到可选角色列表，返回用户选的那个；返回 null 表示用户放弃了选择，
     * 这时会话仍然有效，只是没有选中角色（界面可以之后再让他选）。
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

    // ---------- 内部 ----------

    /** 发一个 POST，并把服务器返回的业务错误翻译成 [AuthException]。 */
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

    /** 空正文是合法的（validate / invalidate 就返回空）。 */
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
