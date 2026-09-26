package org.example.statemind.core.auth

import org.example.statemind.core.GameDir
import org.example.statemind.core.MiniJson
import java.io.File

/**
 * 第三方账号的**令牌仓库** —— 存在数据根目录的 `auth.json` 里。
 *
 * 和 [org.example.statemind.core.AccountStore] 的分工（用户拍板的方案 A）：
 *  - `accounts.txt`：账号清单（谁、什么类型），纯文本、记事本能改；
 *  - `auth.json`（这里）：只有第三方账号才有的令牌细节，机器读写、用户不看。
 * 两边用 [AuthEntry.accountId]（= Account.id）关联，删账号时两边一起删。
 *
 * **绝不落盘密码**：存的是 accessToken（服务器发的一次性长期凭证），
 * 密码只在登录那一次用完就丢；令牌失效就走 [ensureUsable] 刷新或让用户重输密码。
 */
object AuthStore {

    /** 一条第三方账号的登录凭证。[profileId] 是**无横线**的 UUID。 */
    data class AuthEntry(
        val accountId: String,      // 对应 AccountStore 里那条账号的 id
        val serverApiRoot: String,  // 认证服务器 API 根（LittleSkin 等）
        val serverName: String,     // 服务器名，界面显示用（省得再发请求查 metadata）
        val username: String,       // 登录用的邮箱（第三方登录只收邮箱，见 [LoginEmail]）
        val profileId: String,      // 角色的 UUID —— 启动游戏时的玩家标识
        val playerName: String,     // 角色名 —— 进游戏显示的名字
        val accessToken: String,    // 服务器发的令牌；refresh 之后会换新
    ) {
        /** 用存的字段直接拼一个服务器对象（令牌操作不需要再发请求解析 ALI）。 */
        fun toServer(): AuthServer = AuthServer(serverApiRoot, serverName, nonEmailLogin = false)

        /**
         * 皮肤站短名 —— 界面小字显示用，取域名第一段（`https://littleskin.cn/api/yggdrasil/` → `littleskin`）。
         * 拿不到域名时退回 [serverName]。
         */
        val serverLabel: String
            get() = serverApiRoot
                .substringAfter("://")
                .substringBefore('/')
                .substringAfter('@')      // 万一带了 user@host
                .substringBefore(':')     // 去掉端口
                .removePrefix("www.")
                .substringBefore('.')
                .ifBlank { serverName }
    }

    /** [ensureUsable] 的结果。 */
    sealed interface TokenStatus {
        /** 令牌可用。[entry] 可能是刷新后的新凭证（accessToken 换新），**调用方要落盘**。 */
        data class Valid(val entry: AuthEntry) : TokenStatus

        /** 令牌彻底失效（服务器明确拒绝），刷新也救不回来 —— 让用户重新输密码。 */
        data object NeedsRelogin : TokenStatus
    }

    private val file: File by lazy { File(GameDir.appRoot(), "auth.json") }

    private var cache: MutableList<AuthEntry>? = null

    /** 按账号 id 找凭证。 */
    fun find(accountId: String): AuthEntry? = entries().firstOrNull { it.accountId == accountId }

    /** 新增或替换一条凭证（按 accountId 匹配），立即落盘。 */
    fun save(entry: AuthEntry) {
        val list = entries()
        val i = list.indexOfFirst { it.accountId == entry.accountId }
        if (i >= 0) list[i] = entry else list.add(entry)
        persist()
    }

    /** 删一条凭证（账号被删时调用），立即落盘。 */
    fun remove(accountId: String) {
        val list = entries()
        if (list.removeAll { it.accountId == accountId }) persist()
    }

    /**
     * 登录成功后从 [Session] 提炼一条凭证存进来。
     * 要求 session 已选中角色（没角色的账号没法进游戏，登录流程里就拦掉了）。
     */
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
     * - 令牌还有效：原样返回 [TokenStatus.Valid]；
     * - 失效但能刷新：返回**新凭证**的 [TokenStatus.Valid]，调用方记得 `save`；
     * - 刷新也被拒（令牌在服务器侧被吊销 / 换了密码）：[TokenStatus.NeedsRelogin]；
     * - 网络问题：抛 [AuthException]（UNREACHABLE），调用方决定要不要放行离线启动。
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

    /** 凭证文件位置，给「打开数据目录」这类功能用。 */
    val location: File get() = file

    /**
     * 丢掉内存缓存、下次从磁盘重读。正常流程用不到（单进程内自己写自己读），
     * 自测和「外部改了文件再热读」的场景用。
     */
    fun reload() {
        cache = null
    }

    // ---------- 内部 ----------

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
