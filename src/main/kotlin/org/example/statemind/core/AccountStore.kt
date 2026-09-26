package org.example.statemind.core

import org.example.statemind.core.auth.AuthStore
import java.io.File

/**
 * 账号清单的本地存储。
 *
 * 放在**数据根目录**下的 `accounts.txt` —— 与游戏数据同一个父目录、但**不在 .minecraft 里面**，
 * 免得将来「清理游戏数据」之类的操作把账号连带清掉。
 * （数据根目录见 [GameDir.appRoot]：安装版 = `%APPDATA%\StateMind`，便携版 = `<解压目录>\data`。）
 *
 * 存档格式刻意做得极简、可以直接用记事本改：
 *
 *     # State Mind Launcher · 账号（每行 type<TAB>id<TAB>名字）
 *     @current=1694999999999
 *     OFFLINE	1694999999999	lungor
 *
 * 首次访问时自动从磁盘读一次，之后增删改都会立刻写回。
 */
object AccountStore {

    /** 玩家名长度限制，跟 Minecraft 的账号名规则一致。 */
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

    /** 全部账号，按添加顺序。 */
    val accounts: List<Account>
        get() {
            ensureLoaded()
            return list
        }

    /** 当前账号。没明确选过就取第一个；一个都没有则返回 null。 */
    val current: Account?
        get() {
            ensureLoaded()
            return list.firstOrNull { it.id == currentId } ?: list.firstOrNull()
        }

    /** 存档文件位置，给需要展示路径的地方用。 */
    val location: File get() = file

    // ---------- 名字校验 ----------

    /** 玩家名校验结果。 */
    sealed interface NameCheck {
        /** 合法，可以直接用。 */
        data object Ok : NameCheck

        /** 不合法，不允许添加。 */
        data class Invalid(val reason: String) : NameCheck

        /** 能添加，但有风险，需要用户确认后再继续。 */
        data class Risky(val reason: String) : NameCheck
    }

    /**
     * 校验一个离线玩家名。
     *  - 空 / 长度不在 3–16 / 与已有账号重名 → [NameCheck.Invalid]，直接拦；
     *  - 含 `A-Z a-z 0-9 _` 以外的字符（如中文）→ [NameCheck.Risky]，弹警告但允许继续。
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

    // ---------- 增删改 ----------

    /**
     * 添加一个离线账号。调用前应先过 [checkOfflineName]；这里只留最后一道空名/重名保护。
     * @return 添加成功的账号；名字不合规时返回 null。
     */
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
     * 添加一个第三方（皮肤站）账号。名字取角色名；若跟已有账号重名就补上服务器名
     * ——同一个角色名出现在不同皮肤站是常事，不区分的话列表里认不出来。
     *
     * 这里**不碰令牌**：只有 [AuthStore] 存得下 accessToken / 服务器地址那套字段。
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

    /** 删除账号。删掉的正好是当前账号时，自动顶上下一个；第三方账号的令牌一并清掉。 */
    fun remove(id: String) {
        ensureLoaded()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list.removeAt(index)
        if (currentId == id) currentId = (list.getOrNull(index) ?: list.lastOrNull())?.id
        save()
        AuthStore.remove(id)   // 别在 auth.json 里留孤儿凭证
    }

    /** 把某个账号设为「当前」（顶部横幅显示的那个）。 */
    fun setCurrent(id: String) {
        ensureLoaded()
        if (currentId == id || list.none { it.id == id }) return
        currentId = id
        save()
    }

    /** 重新从磁盘读一次（覆盖内存里的）。 */
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

    // ---------- 内部 ----------

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
