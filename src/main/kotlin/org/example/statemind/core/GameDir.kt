package org.example.statemind.core

import java.io.File

/**
 * Minecraft 游戏数据目录（「.minecraft 等价物」）的分析、自动生成与调用。
 *
 * 不同启动器把游戏数据放在不同布局里：
 *  - 单目录式（官方 / HMCL / PCL 等，界面上叫「传统目录」）：根目录本身就是游戏目录，下面直接挂
 *    versions/ libraries/ assets/ mods/ saves/ …；JVM 的 game_directory 就是这个根。
 *  - 分实例式（MultiMC / PolyMC / Prism，俗称「multi 系」）：根目录有 instances/，
 *    每个实例里有自己的游戏目录（新版叫 `minecraft/`，旧版是 `.minecraft/`），
 *    而 libraries/ 与 assets/ 在根目录被所有实例共享；JVM 的 game_directory 要指到实例里的那个。
 *
 * 本对象负责四件事：
 *  1) analyze()         —— 辨认一个目录属于哪种布局、真正的游戏目录在哪、版本/库/资源齐不齐；
 *  2) defaultLocation() —— 算出本机标准存放位置（取代原来写死桌面的实现）；
 *  3) ensure()          —— 首次使用时自动生成标准骨架（已存在则不破坏，只补齐缺项）；
 *  4) locate()/resolve()/discover() —— 「调用能力」：把用户选中的目录解析成真正传给 JVM 的
 *                            game_directory（一个选中的 multi 系根目录会摊成多个），并探测本机已有安装。
 */
object GameDir {

    /** 目录布局。括号里是会显示给用户的名字（「设置 · 实例」页的分组小字、添加弹窗的验证结果）。 */
    enum class Type(val label: String) {
        OFFICIAL("传统目录"),          // 官方启动器的 .minecraft（HMCL/PCL 也用同一布局）
        HMCL("传统目录 · HMCL"),       // Hello Minecraft Launcher：.minecraft 布局 + hmclversion.cfg 标记
        MULTIMC("multi 系"),          // MultiMC / PolyMC / Prism：instances/ 分实例
        UNKNOWN("未识别"),             // 有文件但认不出是哪家
        EMPTY("路径不存在")            // 不存在或为空
    }

    data class Analysis(
        val root: File,
        val type: Type,
        /** 真正的游戏目录：单目录式 = root；multi 系 = instances/<名字>/minecraft（可能为空 = 还没建实例）。 */
        val gameDir: File?,
        val instanceName: String?,
        val hasVersions: Boolean,
        val hasLibraries: Boolean,
        val hasAssets: Boolean
    )

    /** 根目录骨架（共享部分）：版本隔离开启后，玩家数据不放在根，只留共享的版本/库/资源。 */
    private val STD_DIRS = listOf(
        "versions", "libraries",
        "assets", "assets/indexes", "assets/objects", "assets/virtual"
    )

    // ---------- 分析 ----------

    /**
     * 辨认 root 属于哪种启动器布局。root 可以是：
     *  - 单目录式游戏目录本身（如 C:\Users\Q\AppData\Roaming\.minecraft）
     *  - multi 系根目录（含 instances/）
     *  - 某个 multi 系实例目录（含 instance.cfg / mmc-pack.json）
     */
    fun analyze(root: File): Analysis {
        val files = root.listFiles()
        if (files == null || files.isEmpty()) {
            return Analysis(root, Type.EMPTY, null, null, false, false, false)
        }
        val fileSet = files.map { it.name }.toSet()

        // multi 系：根目录有 instances/，或自身就是某实例（含 instance.cfg / mmc-pack.json）
        if ("instances" in fileSet) return analyzeMultiRoot(root)
        if ("instance.cfg" in fileSet || "mmc-pack.json" in fileSet) {
            val gd = instanceGameDir(root)
            return Analysis(root, Type.MULTIMC, gd, root.name,
                hasVersions(gd), hasLibraries(root), hasAssets(root))
        }

        // 单目录式：辨认具体是哪家（HMCL 用 hmclversion.cfg 标记，PCL 与官方布局一致故归 OFFICIAL）
        val type = if ("hmclversion.cfg" in fileSet) Type.HMCL else Type.OFFICIAL
        return Analysis(root, type, root, null,
            hasVersions(root), hasLibraries(root), hasAssets(root))
    }

    private fun analyzeMultiRoot(root: File): Analysis {
        val instances = File(root, "instances").listFiles { f -> f.isDirectory } ?: emptyArray()
        // 默认挑第一个带游戏目录的实例作为代表；没有就只报类型
        val inst = instances.firstOrNull { instanceGameDir(it) != null }
        val gd = inst?.let { instanceGameDir(it) }
        return Analysis(root, Type.MULTIMC, gd, inst?.name,
            hasVersions(gd), hasLibraries(root), hasAssets(root))
    }

    /**
     * multi 系实例里真正的游戏目录：新版 MultiMC / Prism 用 `minecraft/`，旧实例可能是 `.minecraft/`。
     * 两个都没有（还没建实例）返回 null。
     */
    private fun instanceGameDir(inst: File): File? =
        File(inst, "minecraft").takeIf { it.isDirectory }
            ?: File(inst, ".minecraft").takeIf { it.isDirectory }

    private fun hasVersions(dir: File?): Boolean = dir != null && File(dir, "versions").isDirectory
    private fun hasLibraries(dir: File?): Boolean = dir != null && File(dir, "libraries").isDirectory
    private fun hasAssets(dir: File?): Boolean = dir != null && File(dir, "assets").isDirectory

    // ---------- 标准位置 ----------

    /**
     * 启动器自身的数据根目录（放 `.minecraft/`、`settings.properties`、`accounts.txt` 的地方）。
     *
     *  - **便携版**（解压目录里带 portable.txt）→ 解压包内的 `data/`，整个文件夹搬走就是完整备份；
     *  - **安装版** → `%APPDATA%\StateMind`。放这儿而不是安装目录下，卸载启动器不会误删游戏数据。
     *
     * 只在这里判断一次、缓存下来：路径在进程生命周期内不会变，而 [Prefs] / [AccountStore]
     * 会频繁取用。想强制重新判定（测试用）就换进程或直接用 [Portable.rootOrNull]。
     */
    private val appRootDir: File by lazy {
        Portable.dataRootOrNull() ?: run {
            val appData = System.getenv("APPDATA")
            val base = if (!appData.isNullOrBlank()) File(appData) else File(System.getProperty("user.home"))
            File(base, "StateMind")
        }
    }

    /** 见 [appRootDir]。 */
    fun appRoot(): File = appRootDir

    /**
     * 本机标准存放位置：`<数据根>\.minecraft`（独立文件夹，不碰官方的 .minecraft）。
     * 安装版即 `%APPDATA%\StateMind\.minecraft`；便携版即 `<解压目录>\data\.minecraft`。
     */
    fun defaultLocation(): File = File(appRootDir, ".minecraft")

    // ---------- 自动生成 ----------

    /**
     * 自动生成（首次使用时创建）标准布局的游戏目录。
     * 已存在的目录不破坏，只补齐缺失的子目录与必要文件。
     * 返回真正的游戏目录（单目录式 = root）。
     */
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

    /** 版本隔离模式：给某个版本目录建独立的 mods / saves / resourcepacks。 */
    fun ensureVersion(versionDir: File) {
        versionDir.mkdirs()
        for (rel in VERSION_DIRS) File(versionDir, rel).mkdirs()
    }

    // ---------- 调用（定位 / 摊平 / 探测） ----------

    /**
     * 把用户选中的「游戏目录」解析成真正传给 JVM 的 game_directory。
     *  - 选中某 multi 系实例目录 → 返回它里面的游戏目录
     *  - 选中 multi 系根目录 → 返回默认实例的游戏目录（没有则按单目录式在本启动器生成）
     *  - 选中普通 .minecraft → 确保结构存在后原样返回
     */
    fun locate(selected: File): File {
        val a = analyze(selected)
        return when {
            a.type == Type.MULTIMC && a.gameDir != null -> a.gameDir
            a.type == Type.MULTIMC -> ensure(selected)   // 还没建实例，按单目录式生成
            else -> ensure(selected)                       // 单目录式，确保结构存在
        }
    }

    /**
     * 这个目录看起来是不是一个 Minecraft 目录 —— 「添加游戏目录」时用它决定收不收。
     * 传统（`versions/`）和 multi 系（`instances/` 或实例标记）都算；
     * 选中「装着 .minecraft 的外层文件夹」（比如 `%APPDATA%`）也算。
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

    /** 一个选中的目录摊平出来的一个游戏目录。 */
    data class Resolved(
        /** 真正的游戏目录（multi 系 = 实例里的 minecraft/ 或 .minecraft/）。 */
        val gameDir: File,
        /** multi 系里这条对应的实例名；单目录式（传统目录）为 null。 */
        val instanceName: String?,
        val type: Type
    )

    /**
     * 把一个「用户选中的目录」摊平成若干个真正的游戏目录。
     *  - 传统目录（官方 / HMCL / PCL）→ 一条，就是它自己；
     *  - multi 系根目录（含 instances/）→ **每个实例一条**，游戏目录取实例里的 `minecraft/` 或 `.minecraft/`；
     *  - 直接选中某个实例目录、或选中装着 .minecraft 的外层文件夹 → 一条。
     *
     * 目录不存在时也原样返回一条（type = [Type.EMPTY]）—— 让界面能如实显示成「路径不存在」，
     * 而不是让这条记录悄悄消失。
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

    /**
     * 探测本机已有的安装，供「导入已有游戏」使用。
     * 返回所有识别到的安装（单目录式逐个、multi 系每个实例一条）。
     */
    fun discover(): List<Analysis> {
        val out = ArrayList<Analysis>()
        val candidates = buildList {
            System.getenv("APPDATA")?.let { add(File(it, ".minecraft")) }
            add(File(System.getProperty("user.home"), ".minecraft"))
            add(File(System.getProperty("user.home"), "Desktop/.minecraft"))
            // 常见 multi 系根目录
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
