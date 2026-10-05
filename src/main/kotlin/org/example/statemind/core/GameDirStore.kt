package org.example.statemind.core

import java.io.File

/**
 * 「实例」页里那份游戏目录清单，存在数据根目录下的 `gamedirs.txt`（一行一条，制表符分隔，
 * 可直接用记事本改）。
 *
 * 存的是用户当时选中的那个目录，不是解析出来的游戏目录：multi 系目录里以后新加的实例
 * 会在下次刷新时自己出现（[GameDir.resolve]）。不校验路径是否存在 —— 目录被删或挪走时，
 * 界面要能如实显示成「路径不存在」。
 *
 * 另外永远有一条内置的默认目录（[builtin]，StateMind 自带那份 `.minecraft`）：它**不进这个文件**、
 * 也不可移除，界面上的完整清单是 [all]。这样即使用户把自加的目录全删光，也总有一个能用。
 */
object GameDirStore {

    data class Entry(val name: String, val path: String) {
        val root: File get() = File(path)
    }

    private val file: File by lazy {
        File(GameDir.appRoot(), "gamedirs.txt")
    }

    private val list = ArrayList<Entry>()
    private var loaded = false

    private const val BUILTIN_NAME = "StateMind 默认目录"

    /** 按添加顺序。 */
    val entries: List<Entry>
        get() {
            ensureLoaded()
            return list.toList()
        }

    val location: File get() = file

    /** 内置的默认游戏目录。**永远排在最前，且不可移除** —— 保证任何时刻都至少有一个能用的目录。 */
    val builtin: Entry = Entry(BUILTIN_NAME, GameDir.defaultLocation().absolutePath)

    /** 界面上看到的全部目录：内置那条在前，用户添加的在后。 */
    val all: List<Entry> get() = listOf(builtin) + entries

    /** 内置那条不能移除，也不允许被重复添加一次。 */
    fun isBuiltin(rawPath: String): Boolean =
        rawPath.trim().equals(builtin.path, ignoreCase = true)

    fun hasPath(rawPath: String): Boolean {
        ensureLoaded()
        val path = rawPath.trim()
        return isBuiltin(path) || list.any { it.path.equals(path, ignoreCase = true) }
    }

    /** 昵称必须唯一 —— 分组标题就是它，重名认不出谁是谁。 */
    fun hasName(rawName: String): Boolean {
        ensureLoaded()
        val name = sanitize(rawName)
        // 内置那条的名字也占着：不然列表里会出现两个同名分组
        return name.equals(BUILTIN_NAME, ignoreCase = true) ||
                list.any { it.name.equals(name, ignoreCase = true) }
    }

    /** 昵称或路径为空、已存在同名或同路径时返回 null。 */
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

    /** 按路径移除记录。**只忘记这条记录** —— 硬盘上的文件一个都不动。 */
    fun remove(rawPath: String) {
        ensureLoaded()
        val path = rawPath.trim()
        if (list.removeAll { it.path.equals(path, ignoreCase = true) }) save()
    }

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
