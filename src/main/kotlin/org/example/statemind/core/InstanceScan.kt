package org.example.statemind.core

import java.io.File

/**
 * 扫一个游戏目录里的「实例」：`versions/` 下每个带同名 json 的子目录算一个。只读，不写盘。
 *
 * 加载器靠 `libraries` 里的 maven group 认，比看文件夹名可靠 —— 文件夹名是用户自己起的。
 *
 * 界面上要的是「所有游戏目录里的所有版本」，那是 [scanAll]；[scan] 只管一个目录。
 */
object InstanceScan {

    /** 声明顺序 = 同时命中多个时的优先级（Forge + OptiFine 要报 Forge）。 */
    enum class Loader(val label: String) {
        VANILLA("原版"),
        NEOFORGE("NeoForge"),
        FORGE("Forge"),
        FABRIC("Fabric"),
        QUILT("Quilt"),
        LITELOADER("LiteLoader"),
        OPTIFINE("OptiFine"),
        UNKNOWN("未知")
    }

    data class Version(
        /** versions 下的文件夹名，也就是启动时用的版本 id。 */
        val id: String,
        val dir: File,
        val loader: Loader,
        val mcVersion: String,
        /** 上次下载没走完（见 [InstallMarker]）。界面不该把它当成能启动的版本。 */
        val incomplete: Boolean = false
    ) {
        /** 卡片第二行。 */
        val description: String get() = "${loader.label} · $mcVersion"
    }

    /** 一个游戏目录（multi 系里一个实例算一个）扫出来的结果，界面按它分组。 */
    data class Directory(
        /** 目录昵称。 */
        val sourceName: String,
        /** multi 系实例名；单目录式为 null。 */
        val instanceName: String?,
        /** 记录里那条路径 —— 移除时按它找记录（multi 系的多个分组共用同一条）。 */
        val rootPath: String,
        /** 真正传给游戏的游戏目录。 */
        val gameDir: File,
        /** `libraries/` 与 `assets/` 所在目录：传统目录就是 [gameDir]，multi 系在目录根（多实例共享）。 */
        val sharedRoot: File,
        val type: GameDir.Type,
        val versions: List<Version>
    ) {
        /** 分组标题。multi 系要带上实例名，才认得出是哪一个实例。 */
        val title: String get() = if (instanceName == null) sourceName else "$sourceName · $instanceName"
    }

    /** 把一批目录记录摊平并扫描。传 [GameDirStore.all] 就是「界面上所有目录的所有版本」。 */
    fun scanAll(entries: List<GameDirStore.Entry>): List<Directory> {
        val out = ArrayList<Directory>()
        for (entry in entries) {
            for (r in GameDir.resolve(entry.root)) {
                out.add(
                    Directory(
                        sourceName = entry.name,
                        instanceName = r.instanceName,
                        rootPath = entry.path,
                        gameDir = r.gameDir,
                        sharedRoot = r.sharedRoot,
                        type = r.type,
                        versions = if (r.gameDir.isDirectory) scan(r.gameDir) else emptyList()
                    )
                )
            }
        }
        return out
    }

    /** 目录不存在、没有 `versions/`、一个版本都没有 → 返回空表。 */
    fun scan(gameDir: File): List<Version> {
        val dirs = File(gameDir, "versions").listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<Version>(dirs.size)
        for (dir in dirs) {
            val id = dir.name
            val json = File(dir, "$id.json")
            if (!json.isFile) continue          // 没有 json 的文件夹不是版本

            val root = runCatching { MiniJson.parse(json.readText()) as? Map<*, *> }.getOrNull()
            out.add(
                Version(
                    id = id,
                    dir = dir,
                    loader = loaderOf(root),
                    mcVersion = mcVersionOf(root, id),
                    incomplete = InstallMarker.isIncomplete(dir)
                )
            )
        }
        return out.sortedWith(BY_NEWEST)
    }

    /**
     * 同目录里有哪些版本的 json 写着 `inheritsFrom: <id>`。
     *
     * 删版本前必须先问一次：被继承的那份是原版材料（json + client.jar），删掉之后所有子版本都起不来，
     * 而界面上完全看不出它们之间有关系。只认 json 里写死的那条 —— 文件夹名和 mcVersion 都是猜的，
     * 拿来拦删除会拦错。
     */
    fun dependentsOf(versionDir: File, id: String): List<String> {
        val versions = versionDir.parentFile ?: return emptyList()
        val dirs = versions.listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<String>()
        for (dir in dirs) {
            if (dir.name == id) continue
            val json = File(dir, "${dir.name}.json")
            if (!json.isFile) continue
            val root = runCatching { MiniJson.parse(json.readText(Charsets.UTF_8)) as? Map<*, *> }
                .getOrNull()
            if ((root?.get("inheritsFrom") as? String) == id) out.add(dir.name)
        }
        return out.sorted()
    }

    /** json 读不动返回 [Loader.UNKNOWN]；一个加载器坐标都没有就是原版。 */
    private fun loaderOf(root: Map<*, *>?): Loader {
        val libs = (root?.get("libraries") as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?: return Loader.UNKNOWN

        var found: Loader? = null
        for (lib in libs) {
            val name = lib["name"] as? String ?: continue
            val parts = name.split(':')
            val group = parts.getOrNull(0) ?: continue
            val artifact = parts.getOrNull(1).orEmpty()

            val hit = when {
                group == "net.neoforged" -> Loader.NEOFORGE
                group == "net.minecraftforge" -> Loader.FORGE
                group == "net.fabricmc" && artifact.startsWith("fabric-loader") -> Loader.FABRIC
                group == "org.quiltmc" -> Loader.QUILT
                group == "com.mumfrey" -> Loader.LITELOADER
                group == "optifine" || artifact.contains("OptiFine", ignoreCase = true) -> Loader.OPTIFINE
                else -> null
            } ?: continue

            // ordinal 小的优先级高（枚举按优先级声明）
            if (found == null || hit.ordinal < found.ordinal) found = hit
        }
        return found ?: Loader.VANILLA
    }

    /**
     * 按可靠程度依次试：`inheritsFrom` → `clientVersion`（PCL 复制或重命名版本时，原版版本号
     * 只剩这个字段还记着）→ `jar` → 文件夹名里那段版本号 → 文件夹名本身。
     *
     * **绝不能直接拿 `id` 当版本号**：它多半是用户自己起的文件夹名。
     */
    /**
     * 单独问一个版本的原版版本号（社区内容按「游戏版本」筛选时要用）。
     * 读不到 json 就返回 null —— 调用方宁可退回「全部版本」，也不要拿加载器版本号去筛。
     */
    fun mcVersionOf(gameDir: File, versionId: String): String? {
        val json = File(File(gameDir, "versions"), "$versionId/$versionId.json")
        if (!json.isFile) return null
        val root = runCatching { MiniJson.parse(json.readText(Charsets.UTF_8)) as? Map<*, *> }.getOrNull()
        return mcVersionOf(root, versionId)
    }

    private fun mcVersionOf(root: Map<*, *>?, id: String): String {
        val inherits = root?.get("inheritsFrom") as? String
        if (!inherits.isNullOrBlank()) return inherits

        val client = root?.get("clientVersion") as? String
        if (!client.isNullOrBlank()) return client

        val jar = root?.get("jar") as? String
        if (!jar.isNullOrBlank() && jar != id) return jar

        return guessMc(id)
    }

    /**
     * 从文件夹名里挑版本号。名字里常不止一个像版本号的段 ——
     * `fabric-loader-0.16.14-1.21.1` 里那个 `0.16.14` 是加载器自己的版本 ——
     * 所以先找 `1.` 开头的，没有才取第一个 `x.y(.z)` 形态的段。
     */
    private fun guessMc(id: String): String {
        val shaped = id.split('-', '_', ' ').filter { it.matches(LIKE_VERSION) }
        return shaped.firstOrNull { it.startsWith("1.") } ?: shaped.firstOrNull() ?: id
    }

    /** `1.20.1` / `0.16.14` / 年份制的 `26.2` 都算；快照名（`24w45a`）不算。 */
    private val LIKE_VERSION = Regex("""\d+(\.\d+)+""")

    /** 游戏版本新的在前，同版本按加载器优先级，最后按文件夹名。 */
    private val BY_NEWEST = Comparator<Version> { a, b ->
        val byVersion = compareMc(b.mcVersion, a.mcVersion)
        if (byVersion != 0) byVersion
        else if (a.loader.ordinal != b.loader.ordinal) a.loader.ordinal - b.loader.ordinal
        else a.id.compareTo(b.id)
    }

    private fun compareMc(a: String, b: String): Int = McVersion.compare(a, b)
}
