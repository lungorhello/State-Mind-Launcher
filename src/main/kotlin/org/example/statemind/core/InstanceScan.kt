package org.example.statemind.core

import java.io.File

/**
 * 扫一个游戏目录里的「实例」：`versions/` 下每个带同名 json 的子目录算一个。
 *
 * 只读、不写盘。「设置 · 实例」页把结果列成卡片，卡片第二行的描述（如「Fabric · 1.21.1」）
 * 就是这里从版本 json 里认出来的：
 *  - **模组加载器**靠 `libraries` 里的 maven group 认 —— 各家的坐标是固定的
 *    （`net.fabricmc:fabric-loader` / `net.minecraftforge:forge` / `net.neoforged:neoforge` …），
 *    比看文件夹名可靠：文件夹名是用户自己起的，什么写法都有；
 *  - **游戏版本**按 `inheritsFrom` → `clientVersion` → `jar` → 文件夹名 的顺序认，
 *    详见 [mcVersionOf]。**版本文件夹名不能当版本号** —— 它多半是用户自己起的名字。
 */
object InstanceScan {

    /** 模组加载器。声明顺序 = 同时命中多个时的优先级（Forge + OptiFine 要报 Forge）。 */
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

    /** 一个版本（= `versions/` 下的一个子目录）。 */
    data class Version(
        /** versions 下的文件夹名，也就是启动时用的版本 id。 */
        val id: String,
        val dir: File,
        val loader: Loader,
        /** 它对应的 Minecraft 版本，如 `1.21.1`。 */
        val mcVersion: String
    ) {
        /** 卡片第二行：模组加载器 / 原版 + 版本号。 */
        val description: String get() = "${loader.label} · $mcVersion"
    }

    /**
     * 扫一个游戏目录。目录不存在、没有 `versions/`、或者里面一个版本都没有 → 返回空表。
     * 排序：游戏版本新的在前，同版本按加载器优先级，最后按文件夹名。
     */
    fun scan(gameDir: File): List<Version> {
        val dirs = File(gameDir, "versions").listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<Version>(dirs.size)
        for (dir in dirs) {
            val id = dir.name
            val json = File(dir, "$id.json")
            if (!json.isFile) continue          // 没有 json 的文件夹不是版本，跳过

            val root = runCatching { MiniJson.parse(json.readText()) as? Map<*, *> }.getOrNull()
            out.add(
                Version(
                    id = id,
                    dir = dir,
                    loader = loaderOf(root),
                    mcVersion = mcVersionOf(root, id)
                )
            )
        }
        return out.sortedWith(BY_NEWEST)
    }

    // ---------- 认加载器 ----------

    /**
     * 从 `libraries` 里认模组加载器。认不出（json 读不动）返回 [Loader.UNKNOWN]，
     * 一个加载器坐标都没有就是原版。
     */
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

    // ---------- 认游戏版本 ----------

    /**
     * 认这个版本对应的 Minecraft 版本号。按可靠程度依次往下试：
     *
     *  1. `inheritsFrom` —— Forge / Fabric 的子版本 json 靠它指向原版，最权威；
     *  2. `clientVersion` —— PCL 复制或重命名版本时会把原版内容整份写进新 json，
     *     `id` 换成了用户起的名字（`mtrformango`），只有这个字段还记得原版版本号；
     *  3. `jar` —— 有些加载器在这里写原版版本 id（等于自己 id 时没意义，跳过）；
     *  4. 文件夹名里那一段版本号（`1.20.1-forge-47.2.0` → `1.20.1`）；
     *  5. 实在没有就退回文件夹名 —— 显示得难看，也好过空着。
     *
     * **绝不能直接拿 `id` 当版本号**：它多半是文件夹名（用户自己起的），那不是版本号。
     */
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
     * 最后一道兜底：从文件夹名里挑游戏版本号。
     *
     * 名字里常常不止一个像版本号的东西 —— `fabric-loader-0.16.14-1.21.1` 里的 `0.16.14`
     * 是加载器自己的版本。挑法：先找 `1.` 开头的（Minecraft 1.x 的写法），没有就取第一个
     * `x.y(.z)` 形态的段；都不像就原样返回文件夹名。
     */
    private fun guessMc(id: String): String {
        val shaped = id.split('-', '_', ' ').filter { it.matches(LIKE_VERSION) }
        return shaped.firstOrNull { it.startsWith("1.") } ?: shaped.firstOrNull() ?: id
    }

    /** `1.20.1` / `0.16.14` / 年份制的 `26.2` 都算；快照名（`24w45a`）不算。 */
    private val LIKE_VERSION = Regex("""\d+(\.\d+)+""")

    // ---------- 排序 ----------

    /** 游戏版本新的在前；同版本按加载器优先级（原版在前，其次 NeoForge / Forge / Fabric…）。 */
    private val BY_NEWEST = Comparator<Version> { a, b ->
        val byVersion = compareMc(b.mcVersion, a.mcVersion)
        if (byVersion != 0) byVersion
        else if (a.loader.ordinal != b.loader.ordinal) a.loader.ordinal - b.loader.ordinal
        else a.id.compareTo(b.id)
    }

    /** 按点分段比大小：`1.21.1` > `1.20.10` > `1.9`；段里不是数字的（快照名）按字符串比。 */
    private fun compareMc(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i).orEmpty()
            val y = pb.getOrNull(i).orEmpty()
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }
}
