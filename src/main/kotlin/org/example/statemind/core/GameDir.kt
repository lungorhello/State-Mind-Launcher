package org.example.statemind.core

import java.io.File

/**
 * Minecraft 游戏数据目录（「.minecraft 等价物」）。
 *
 * 两种布局：单目录式（官方 / HMCL / PCL）根目录自己就是游戏目录；
 * 分实例式（MultiMC / PolyMC / Prism）根目录下有 instances/，每个实例一份游戏目录，
 * libraries/ 与 assets/ 则在根目录被所有实例共享。
 */
object GameDir {

    enum class Type(val label: String) {
        OFFICIAL("传统目录"),
        HMCL("传统目录 · HMCL"),
        MULTIMC("multi 系"),
        UNKNOWN("未识别"),
        EMPTY("路径不存在")
    }

    data class Analysis(
        val root: File,
        val type: Type,
        /** 单目录式 = root；multi 系 = 实例里的 minecraft，还没建实例时为 null。 */
        val gameDir: File?,
        val instanceName: String?,
        val hasVersions: Boolean,
        val hasLibraries: Boolean,
        val hasAssets: Boolean
    )

    private val STD_DIRS = listOf(
        "versions", "libraries",
        "assets", "assets/indexes", "assets/objects", "assets/virtual"
    )

    /** root 可以是游戏目录本身、multi 系根目录，或某个 multi 系实例目录。 */
    fun analyze(root: File): Analysis {
        val files = root.listFiles()
        if (files == null || files.isEmpty()) {
            return Analysis(root, Type.EMPTY, null, null, false, false, false)
        }
        val fileSet = files.map { it.name }.toSet()

        if ("instances" in fileSet) return analyzeMultiRoot(root)
        if ("instance.cfg" in fileSet || "mmc-pack.json" in fileSet) {
            val gd = instanceGameDir(root)
            return Analysis(root, Type.MULTIMC, gd, root.name,
                hasVersions(gd), hasLibraries(root), hasAssets(root))
        }

        // 单目录式：PCL 与官方布局一致，只有 HMCL 多一个标记文件
        val type = if ("hmclversion.cfg" in fileSet) Type.HMCL else Type.OFFICIAL
        return Analysis(root, type, root, null,
            hasVersions(root), hasLibraries(root), hasAssets(root))
    }

    private fun analyzeMultiRoot(root: File): Analysis {
        val instances = File(root, "instances").listFiles { f -> f.isDirectory } ?: emptyArray()
        val inst = instances.firstOrNull { instanceGameDir(it) != null }
        val gd = inst?.let { instanceGameDir(it) }
        return Analysis(root, Type.MULTIMC, gd, inst?.name,
            hasVersions(gd), hasLibraries(root), hasAssets(root))
    }

    /** 新版 MultiMC / Prism 用 `minecraft/`，旧实例可能是 `.minecraft/`；都没有（还没建实例）返回 null。 */
    private fun instanceGameDir(inst: File): File? =
        File(inst, "minecraft").takeIf { it.isDirectory }
            ?: File(inst, ".minecraft").takeIf { it.isDirectory }

    private fun hasVersions(dir: File?): Boolean = dir != null && File(dir, "versions").isDirectory
    private fun hasLibraries(dir: File?): Boolean = dir != null && File(dir, "libraries").isDirectory
    private fun hasAssets(dir: File?): Boolean = dir != null && File(dir, "assets").isDirectory

    /**
     * 数据根目录（放 `.minecraft/`、`settings.properties`、`accounts.txt` 的地方）：便携版在
     * 解压包内，安装版在 `%APPDATA%\StateMind` —— 不放安装目录下，卸载启动器不会误删游戏数据。
     *
     * 只判定一次并缓存（[Prefs] / [AccountStore] 取得频繁）；想强制重判（测试）得换进程。
     */
    private val appRootDir: File by lazy {
        Portable.dataRootOrNull() ?: run {
            val appData = System.getenv("APPDATA")
            val base = if (!appData.isNullOrBlank()) File(appData) else File(System.getProperty("user.home"))
            File(base, "StateMind")
        }
    }

    fun appRoot(): File = appRootDir

    /** 本机默认游戏目录 `<数据根>\.minecraft`，不碰官方的 .minecraft。 */
    fun defaultLocation(): File = File(appRootDir, ".minecraft")

    /** 只补齐缺的子目录和标记文件，已存在的目录不动。 */
    fun ensure(root: File): File {
        root.mkdirs()
        for (rel in STD_DIRS) File(root, rel).mkdirs()
        val marker = File(root, "statemind.cfg")
        if (!marker.isFile) {
            marker.writeText("generatedBy=StateMindLauncher\nversion=0.1\n")
        }
        return root
    }

    private val VERSION_DIRS = listOf("mods", "saves", "resourcepacks", "logs", "crash-reports", "screenshots")

    /** 版本隔离：给该版本目录建自己的 mods / saves / resourcepacks。 */
    fun ensureVersion(versionDir: File) {
        versionDir.mkdirs()
        for (rel in VERSION_DIRS) File(versionDir, rel).mkdirs()
    }

    /** 解析出真正传给 JVM 的 game_directory；multi 系根目录取默认实例，没有就按单目录式生成。 */
    fun locate(selected: File): File {
        val a = analyze(selected)
        return when {
            a.type == Type.MULTIMC && a.gameDir != null -> a.gameDir
            a.type == Type.MULTIMC -> ensure(selected)   // 还没建实例，按单目录式生成
            else -> ensure(selected)                       // 单目录式，确保结构存在
        }
    }

    /**
     * 添加游戏目录时用它决定收不收：传统（`versions/`）、multi 系，以及装着 `.minecraft`
     * 的外层文件夹（如 `%APPDATA%`）都算。
     */
    fun looksLikeMinecraft(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "versions").isDirectory ||
                File(dir, "instances").isDirectory ||
                File(dir, "instance.cfg").isFile ||
                File(dir, "mmc-pack.json").isFile ||
                File(dir, "minecraft/versions").isDirectory ||
                File(dir, ".minecraft/versions").isDirectory
    }

    data class Resolved(
        val gameDir: File,
        /** 单目录式为 null。 */
        val instanceName: String?,
        val type: Type
    )

    /**
     * 把用户选中的目录摊平成若干真正的游戏目录：multi 系根目录每个实例一条，其余一条。
     *
     * 目录不存在时也返回一条（[Type.EMPTY]），让界面能如实显示成「路径不存在」。
     */
    fun resolve(selected: File): List<Resolved> {
        if (!selected.isDirectory) return listOf(Resolved(selected, null, Type.EMPTY))
        val names = selected.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        if ("instances" in names) {
            val instances = File(selected, "instances").listFiles { f -> f.isDirectory } ?: emptyArray()
            return instances.sortedBy { it.name.lowercase() }.map { inst ->
                Resolved(instanceGameDir(inst) ?: File(inst, "minecraft"), inst.name, Type.MULTIMC)
            }
        }
        if ("instance.cfg" in names || "mmc-pack.json" in names) {
            return listOf(Resolved(instanceGameDir(selected) ?: File(selected, "minecraft"), selected.name, Type.MULTIMC))
        }
        // 选中的是「装着 .minecraft 的外层文件夹」（比如 %APPDATA%）：往里再看一层
        if (!File(selected, "versions").isDirectory) {
            val inner = File(selected, ".minecraft")
            if (File(inner, "versions").isDirectory) {
                return listOf(Resolved(inner, null, typeOfSingle(names)))
            }
        }
        return listOf(Resolved(selected, null, typeOfSingle(names)))
    }

    private fun typeOfSingle(names: Set<String>): Type =
        if ("hmclversion.cfg" in names) Type.HMCL else Type.OFFICIAL

    /** 探测本机已有的安装，供「导入已有游戏」用。multi 系每个实例出一条。 */
    fun discover(): List<Analysis> {
        val out = ArrayList<Analysis>()
        val candidates = buildList {
            System.getenv("APPDATA")?.let { add(File(it, ".minecraft")) }
            add(File(System.getProperty("user.home"), ".minecraft"))
            add(File(System.getProperty("user.home"), "Desktop/.minecraft"))
            System.getenv("APPDATA")?.let {
                add(File(it, "MultiMC"))
                add(File(it, "PolyMC"))
                add(File(it, "PrismLauncher"))
            }
        }
        for (c in candidates.distinct()) {
            if (!c.isDirectory) continue
            val a = analyze(c)
            if (a.type == Type.MULTIMC) {
                val instances = File(c, "instances").listFiles { f -> f.isDirectory } ?: emptyArray()
                for (inst in instances) {
                    val gd = instanceGameDir(inst)
                    if (gd != null) {
                        out.add(Analysis(inst, Type.MULTIMC, gd, inst.name,
                            hasVersions(gd), hasLibraries(c), hasAssets(c)))
                    }
                }
            } else if (a.type != Type.EMPTY) {
                out.add(a)
            }
        }
        return out
    }
}
