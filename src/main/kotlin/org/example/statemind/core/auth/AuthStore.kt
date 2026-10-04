package org.example.statemind.core.auth

import org.example.statemind.core.GameDir
import org.example.statemind.core.MiniJson
import java.io.File

/**
 * 第三方账号的令牌仓库，存在数据根目录的 `auth.json` 里；账号清单在同目录的 `accounts.txt`
 * （[org.example.statemind.core.AccountStore]），两边靠 [AuthEntry.accountId] 关联，删账号时一起删。
 *
 * **绝不落盘密码**：只存 accessToken，密码在登录那一次用完就丢。
 */
object AuthStore {

    /** 一条第三方账号的登录凭证。[profileId] 是**无横线**的 UUID。 */
    data class AuthEntry(
        val accountId: String,
        val serverApiRoot: String,
        val serverName: String,     // 界面直接显示，省得再请求一次 metadata
        val username: String,
        val profileId: String,
        val playerName: String,
        val accessToken: String,
    ) {
        /** 令牌操作用不着发请求解析 ALI，直接用存的字段拼。 */
        fun toServer(): AuthServer = AuthServer(serverApiRoot, serverName, nonEmailLogin = false)

        /** 皮肤站短名，界面小字用：取域名第一段（`littleskin.cn` → `littleskin`）。 */
        val serverLabel: String
            get() = serverApiRoot
                .substringAfter("://")
                .substringBefore('/')
                .substringAfter('@')      // 万一带了 user@host
                .substringBefore(':')
                .removePrefix("www.")
                .substringBefore('.')
                .ifBlank { serverName }
    }

    sealed interface TokenStatus {
        /** 令牌可用。[entry] 可能是刷新后的新凭证。 */
        data class Valid(val entry: AuthEntry) : TokenStatus

        /** 刷新也救不回来，要用户重输密码。 */
        data object NeedsRelogin : TokenStatus
    }

    private val file: File by lazy { File(GameDir.appRoot(), "auth.json") }

    private var cache: MutableList<AuthEntry>? = null

    fun find(accountId: String): AuthEntry? = entries().firstOrNull { it.accountId == accountId }

    fun save(entry: AuthEntry) {
        val list = entries()
        val i = list.indexOfFirst { it.accountId == entry.accountId }
        if (i >= 0) list[i] = entry else list.add(entry)
        persist()
    }

    fun remove(accountId: String) {
        val list = entries()
        if (list.removeAll { it.accountId == accountId }) persist()
    }

    /** @throws AuthException 会话里没有选中角色。 */
    fun saveFromSession(accountId: String, server: AuthServer, username: String, session: Session): AuthEntry {
        val profile = session.selected
            ?: throw AuthException(AuthError.NO_PROFILE, "这个账号还没有选中角色。")
        val entry = AuthEntry(
            accountId = accountId,
            serverApiRoot = server.apiRoot,
            serverName = server.name,
            username = username,
            profileId = profile.id,
            playerName = profile.name,
            accessToken = session.accessToken,
        )
        save(entry)
        return entry
    }

    /**
     * 令牌生命周期：validate →（有效就直接用）→ refresh 换新。
     *
     * 网络问题抛 [AuthException]，要不要放行离线启动由调用方决定。
     */
    fun ensureUsable(entry: AuthEntry, clientToken: String): TokenStatus {
        val server = entry.toServer()

        if (YggdrasilApi.validate(server, entry.accessToken, clientToken)) {
            return TokenStatus.Valid(entry)
        }

        return try {
            val session = YggdrasilApi.refresh(server, entry.accessToken, clientToken)
            val refreshed = entry.copy(
                accessToken = session.accessToken,
                profileId = session.selected?.id ?: entry.profileId,
                playerName = session.selected?.name ?: entry.playerName,
            )
            save(refreshed)
            TokenStatus.Valid(refreshed)
        } catch (e: AuthException) {
            if (e.error == AuthError.BAD_CREDENTIALS) TokenStatus.NeedsRelogin else throw e
        }
    }

    val location: File get() = file

    /** 丢掉内存缓存，下次从磁盘重读。自测用，正常流程不需要。 */
    fun reload() {
        cache = null
    }

    private fun entries(): MutableList<AuthEntry> {
        cache?.let { return it }
        val loaded = readAll().toMutableList()
        cache = loaded
        return loaded
    }

    private fun readAll(): List<AuthEntry> {
        if (!file.isFile) return emptyList()
        val root = runCatching { MiniJson.parse(file.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return emptyList()   // 文件坏了就当没有凭证（重新登录即可），别让启动器起不来
        val list = (root as? Map<*, *>)?.get("accounts") as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            AuthEntry(
                accountId = m["accountId"] as? String ?: return@mapNotNull null,
                serverApiRoot = m["serverApiRoot"] as? String ?: return@mapNotNull null,
                serverName = (m["serverName"] as? String).orEmpty(),
                username = (m["username"] as? String).orEmpty(),
                profileId = m["profileId"] as? String ?: return@mapNotNull null,
                playerName = (m["playerName"] as? String).orEmpty(),
                accessToken = m["accessToken"] as? String ?: return@mapNotNull null,
            )
        }
    }

    private fun persist() {
        val list = entries()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                MiniJson.write(linkedMapOf<String, Any?>("accounts" to list.map { it.toJson() })),
                Charsets.UTF_8
            )
        }
    }

    private fun AuthEntry.toJson(): Map<String, Any?> = linkedMapOf(
        "accountId" to accountId,
        "serverApiRoot" to serverApiRoot,
        "serverName" to serverName,
        "username" to username,
        "profileId" to profileId,
        "playerName" to playerName,
        "accessToken" to accessToken,
    )
}
