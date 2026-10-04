package org.example.statemind.core

import org.example.statemind.core.auth.AuthStore
import java.io.File

/**
 * 账号清单的本地存储，在数据根目录下的 `accounts.txt`。
 *
 * 刻意放在游戏数据之外：将来「清理游戏数据」之类的操作不会把账号连带清掉。
 * 首次访问时自动读盘，之后增删改立刻写回。
 */
object AccountStore {

    /** 跟 Minecraft 账号名规则一致。 */
    const val MIN_NAME_LEN = 3
    const val MAX_NAME_LEN = 16

    /** 官方允许的玩家名字符。 */
    private const val ALLOWED_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"

    private val file: File by lazy {
        File(GameDir.appRoot(), "accounts.txt")
    }

    private val list = ArrayList<Account>()
    private var currentId: String? = null
    private var loaded = false

    /** 按添加顺序。 */
    val accounts: List<Account>
        get() {
            ensureLoaded()
            return list
        }

    /** 没明确选过就取第一个；一个都没有则返回 null。 */
    val current: Account?
        get() {
            ensureLoaded()
            return list.firstOrNull { it.id == currentId } ?: list.firstOrNull()
        }

    val location: File get() = file

    sealed interface NameCheck {
        data object Ok : NameCheck

        data class Invalid(val reason: String) : NameCheck

        data class Risky(val reason: String) : NameCheck
    }

    /**
     * 空 / 超长 / 重名 → [NameCheck.Invalid]，直接拦；
     * 含 `A-Z a-z 0-9 _` 以外的字符（如中文）→ [NameCheck.Risky]，警告但放行。
     */
    fun checkOfflineName(rawName: String): NameCheck {
        ensureLoaded()
        val name = sanitize(rawName)

        if (name.isEmpty()) return NameCheck.Invalid("玩家名不能为空")
        if (name.length < MIN_NAME_LEN) {
            return NameCheck.Invalid("玩家名至少 $MIN_NAME_LEN 个字符，现在只有 ${name.length} 个")
        }
        if (name.length > MAX_NAME_LEN) {
            return NameCheck.Invalid("玩家名最多 $MAX_NAME_LEN 个字符，现在有 ${name.length} 个")
        }
        if (list.any { it.name.equals(name, ignoreCase = true) }) {
            return NameCheck.Invalid("已经有叫「$name」的账号了")
        }

        val illegal = name.filter { it !in ALLOWED_CHARS }.toSet().joinToString("")
        if (illegal.isNotEmpty()) {
            return NameCheck.Risky("名字里有非英文字符「$illegal」，进部分服务器可能被拒。")
        }
        return NameCheck.Ok
    }

    /** 调用前应先过 [checkOfflineName]；这里只做空名和重名的兜底，不合规则返回 null。 */
    fun addOffline(rawName: String): Account? {
        ensureLoaded()
        val name = sanitize(rawName)
        if (name.isEmpty() || list.any { it.name.equals(name, ignoreCase = true) }) return null

        val acc = Account(System.currentTimeMillis().toString(), name, AccountType.OFFLINE)
        list.add(acc)
        if (currentId == null) currentId = acc.id
        save()
        return acc
    }

    /**
     * 添加一个第三方账号，名字取角色名；重名就补上服务器名（同一角色名出现在不同皮肤站是常事）。
     *
     * 这里**不碰令牌**：accessToken、服务器地址那些字段只有 [AuthStore] 存得下。
     */
    fun addThirdParty(playerName: String, serverName: String): Account {
        ensureLoaded()
        val base = sanitize(playerName).ifBlank { "第三方账号" }
        val name = if (list.any { it.name.equals(base, ignoreCase = true) }) {
            "$base@${sanitize(serverName).ifBlank { "皮肤站" }}"
        } else {
            base
        }

        val acc = Account(System.currentTimeMillis().toString(), name, AccountType.THIRD_PARTY)
        list.add(acc)
        if (currentId == null) currentId = acc.id
        save()
        return acc
    }

    /** 删掉的正好是当前账号时自动顶上后一个；第三方账号的令牌一并清掉。 */
    fun remove(id: String) {
        ensureLoaded()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list.removeAt(index)
        if (currentId == id) currentId = (list.getOrNull(index) ?: list.lastOrNull())?.id
        save()
        AuthStore.remove(id)   // 别在 auth.json 里留孤儿凭证
    }

    /** 设为当前账号（顶部横幅显示的那个）。 */
    fun setCurrent(id: String) {
        ensureLoaded()
        if (currentId == id || list.none { it.id == id }) return
        currentId = id
        save()
    }

    fun reload() {
        loaded = true
        list.clear()
        currentId = null
        if (!file.isFile) return

        runCatching {
            file.readLines(Charsets.UTF_8).forEach { raw ->
                val line = raw.trimEnd()
                if (line.isBlank() || line.startsWith("#")) return@forEach

                if (line.startsWith(CURRENT_PREFIX)) {
                    currentId = line.removePrefix(CURRENT_PREFIX).trim().ifBlank { null }
                    return@forEach
                }

                val parts = line.split('\t')
                if (parts.size < 3) return@forEach
                val id = parts[1].trim()
                val name = parts[2].trim()
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    list.add(Account(id, name, AccountType.fromName(parts[0])))
                }
            }
        }

        // 存档里记的当前账号已经不在了（被手改过），退回第一个
        if (currentId != null && list.none { it.id == currentId }) currentId = list.firstOrNull()?.id
    }

    /** 去掉会破坏存档格式的空白与制表符。 */
    private fun sanitize(raw: String): String =
        raw.trim().replace("\t", "").replace("\n", "").replace("\r", "")

    private fun ensureLoaded() {
        if (!loaded) reload()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("# State Mind Launcher · 账号（每行 type<TAB>id<TAB>名字，可直接编辑）\n")
            currentId?.let { sb.append(CURRENT_PREFIX).append(it).append('\n') }
            list.forEach {
                sb.append(it.type.name).append('\t').append(it.id).append('\t').append(it.name).append('\n')
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
        }
    }

    private const val CURRENT_PREFIX = "@current="
}
