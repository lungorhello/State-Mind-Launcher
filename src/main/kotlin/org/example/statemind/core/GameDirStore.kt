package org.example.statemind.core

import java.io.File

/**
 * 「实例」页里那份游戏目录清单的本地存储。
 *
 * 放在**数据根目录**下的 `gamedirs.txt`（和 accounts.txt / settings.properties 同一个父目录），
 * 一行一条、制表符分隔，可以直接用记事本改：
 *
 *     # State Mind Launcher · 游戏目录（每行 昵称<TAB>路径）
 *     我的整合包	D:\Minecraft\.minecraft
 *
 * 存的是**用户当时选中的那个目录**，不是解析出来的游戏目录 —— multi 系目录里以后新加的实例
 * 会在下次刷新时自己出现（解析见 [GameDir.resolve]）。这里只管「记下来」和「别记重」，
 * 不校验路径是否存在：目录被删/被移走时，界面要能如实显示成「路径不存在」。
 */
object GameDirStore {

    /** 一条记录：启动器里显示的名字 + 用户选中的那个目录。 */
    data class Entry(val name: String, val path: String) {
        val root: File get() = File(path)
    }

    private val file: File by lazy {
        File(GameDir.appRoot(), "gamedirs.txt")
    }

    private val list = ArrayList<Entry>()
    private var loaded = false

    /** 已添加的目录，按添加顺序。 */
    val entries: List<Entry>
        get() {
            ensureLoaded()
            return list.toList()
        }

    /** 存档文件位置，给需要展示路径的地方用。 */
    val location: File get() = file

    /** 这个路径是不是已经在列表里了（比路径，不管昵称）。 */
    fun hasPath(rawPath: String): Boolean {
        ensureLoaded()
        val path = rawPath.trim()
        return list.any { it.path.equals(path, ignoreCase = true) }
    }

    /** 这个昵称是不是已经用过了。昵称在列表里必须唯一 —— 分组标题就是它，重名认不出谁是谁。 */
    fun hasName(rawName: String): Boolean {
        ensureLoaded()
        val name = sanitize(rawName)
        return list.any { it.name.equals(name, ignoreCase = true) }
    }

    /** 添加一条。昵称/路径为空、或已存在同名同路径时返回 null。 */
    fun add(rawName: String, rawPath: String): Entry? {
        ensureLoaded()
        val name = sanitize(rawName)
        val path = rawPath.trim()
        if (name.isEmpty() || path.isEmpty()) return null
        if (hasPath(path) || hasName(name)) return null

        val entry = Entry(name, path)
        list.add(entry)
        save()
        return entry
    }

    /**
     * 按路径移除。
     * **只忘记这条记录** —— 硬盘上的文件一个都不动，也不会去删目录。
     */
    fun remove(rawPath: String) {
        ensureLoaded()
        val path = rawPath.trim()
        if (list.removeAll { it.path.equals(path, ignoreCase = true) }) save()
    }

    /** 重新从磁盘读一次（覆盖内存里的）。 */
    fun reload() {
        loaded = true
        list.clear()
        if (!file.isFile) return

        runCatching {
            file.readLines(Charsets.UTF_8).forEach { raw ->
                val line = raw.trimEnd()
                if (line.isBlank() || line.startsWith("#")) return@forEach

                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                val name = parts[0].trim()
                val path = parts[1].trim()
                // 同一个路径只算一条（手改文件时可能写重）
                if (name.isNotEmpty() && path.isNotEmpty() && list.none { it.path.equals(path, true) }) {
                    list.add(Entry(name, path))
                }
            }
        }
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
            sb.append("# State Mind Launcher · 游戏目录（每行 昵称<TAB>路径，可直接编辑）\n")
            list.forEach { sb.append(it.name).append('\t').append(it.path).append('\n') }
            file.writeText(sb.toString(), Charsets.UTF_8)
        }
    }
}
