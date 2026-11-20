package org.example.statemind.core.loader

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.example.statemind.core.GameDir
import org.example.statemind.core.HttpText
import org.example.statemind.core.MiniJson
import org.example.statemind.core.Prefs
import org.example.statemind.core.auth.Http

/**
 * 加载器版本从各自的服务取。Fabric 与 Quilt 提供「一份现成的 profile json」，
 * 里面只有加载器自己的库和 mainClass，其余靠 `inheritsFrom` 挂在原版版本上 ——
 * 这两个把 json 落到 `versions/<id>/`、再把库下下来就能启动。
 *
 * Forge / NeoForge / OptiFine / LiteLoader 各有各的清单与安装方式，分派出去。
 */
object LoaderSource {

    /**
     * 每个 (加载器, 游戏版本) 只问一次服务器。
     *
     * 空表也缓存：接口回 200 却没有内容，就是这个版本确实没有安装包（老版本、快照、愚人节版本
     * 都常见）。不缓存的话每点一次 chip 都要再等一轮，正是「加载慢、反复加载」的来源。
     */
    private val cache = ConcurrentHashMap<String, List<LoaderVersion>>()

    /**
     * 上一次的清单落一份在磁盘上，隔几个小时再用。
     *
     * 安装页一进去就要问六个加载器；全都重来一遍，在网络不好的时候比选版本本身还慢。
     * 空结果不落盘 —— 新构建发布后得能被发现。
     */
    private val diskDir: File by lazy { File(GameDir.appRoot(), "cache/loaders") }

    private const val DISK_TTL = 6 * 60 * 60 * 1000L

    fun cached(kind: LoaderKind, mcVersion: String): List<LoaderVersion>? = cache[keyOf(kind, mcVersion)]

    fun versions(kind: LoaderKind, mcVersion: String): List<LoaderVersion> {
        cache[keyOf(kind, mcVersion)]?.let { return it }
        diskGet(kind, mcVersion)?.let {
            cache[keyOf(kind, mcVersion)] = it
            return it
        }

        val list = fetch(kind, mcVersion)
        cache[keyOf(kind, mcVersion)] = list
        if (list.isNotEmpty()) diskPut(kind, mcVersion, list)
        return list
    }

    private fun fetch(kind: LoaderKind, mcVersion: String): List<LoaderVersion> = when (kind) {
        LoaderKind.FABRIC -> fabricQuilt(kind, mcVersion)
        LoaderKind.QUILT -> fabricQuilt(kind, mcVersion)
        LoaderKind.FORGE, LoaderKind.NEOFORGE -> ForgeSource.versions(kind, mcVersion)
        LoaderKind.OPTIFINE -> OptiFineSource.versions(mcVersion)
        LoaderKind.LITELOADER -> LiteLoaderSource.versions(mcVersion)
        LoaderKind.VANILLA -> emptyList()
    }

    /** Fabric / Quilt 的 meta 返回 `[{loader: {version, stable}}]`。 */
    private fun fabricQuilt(kind: LoaderKind, mcVersion: String): List<LoaderVersion> {
        val url = when (kind) {
            LoaderKind.FABRIC -> "https://meta.fabricmc.net/v2/versions/loader/$mcVersion"
            else -> "https://meta.quiltmc.org/v3/versions/loader/$mcVersion"
        }
        val parsed = MiniJson.parse(fetchText(url)) as? List<*> ?: return emptyList()
        val list = parsed.mapNotNull { item ->
            val loader = (item as? Map<*, *>)?.get("loader") as? Map<*, *>
                ?: return@mapNotNull null
            val version = (loader["version"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            LoaderVersion(version, loader["stable"] == true)
        }.distinctBy { it.id }

        // 两家都按「新的在前」返回，但 Quilt 把 beta 排在正式版前面 —— 直接取第一个会选中 beta。
        // 稳定 > 普通 > 预发布；同一档里保持服务端给的顺序（sortedBy 是稳定排序）。
        return list.sortedByDescending {
            when {
                it.stable -> 2
                it.preRelease -> 0
                else -> 1
            }
        }
    }

    private fun keyOf(kind: LoaderKind, mcVersion: String): String = "${kind.name}@$mcVersion"

    /** 安装时去哪儿拿「版本长什么样」：Fabric 系是一份 json，其余是安装包 / 自己拼。 */
    fun profileUrl(kind: LoaderKind, mcVersion: String, loaderVersion: String): String = when (kind) {
        LoaderKind.FABRIC ->
            "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loaderVersion/profile/json"
        LoaderKind.QUILT ->
            "https://meta.quiltmc.org/v3/versions/loader/$mcVersion/$loaderVersion/profile/json"
        LoaderKind.FORGE, LoaderKind.NEOFORGE ->
            ForgeSource.installerUrls(kind, mcVersion, loaderVersion).firstOrNull().orEmpty()
        LoaderKind.OPTIFINE -> OptiFineSource.downloadUrl(mcVersion, loaderVersion)
        // 这两家的 json 由各自的 Source 自己拼，这里只占位
        LoaderKind.LITELOADER -> ""
        LoaderKind.VANILLA -> ""
    }

    /**
     * 版本 id 同时是 `versions/` 下的文件夹名。Fabric 系 json 里写的 id 就是这个形状；
     * 其余按官方安装器 / PCL 的叫法拼，界面不必等联网。
     */
    fun idOf(kind: LoaderKind, mcVersion: String, loaderVersion: String): String = when (kind) {
        LoaderKind.FABRIC -> "fabric-loader-$loaderVersion-$mcVersion"
        LoaderKind.QUILT -> "quilt-loader-$loaderVersion-$mcVersion"
        LoaderKind.FORGE -> "$mcVersion-forge-$loaderVersion"
        LoaderKind.NEOFORGE -> "$mcVersion-neoforge-$loaderVersion"
        LoaderKind.OPTIFINE -> "${mcVersion}-OptiFine_$loaderVersion"
        // 跟 PCL 的叫法一致：1.12.2-LiteLoader1.12.2
        LoaderKind.LITELOADER -> "$mcVersion-LiteLoader$loaderVersion"
        LoaderKind.VANILLA -> mcVersion
    }

    fun choice(kind: LoaderKind, mcVersion: String, loaderVersion: String): LoaderChoice =
        LoaderChoice(
            kind = kind,
            mcVersion = mcVersion,
            loaderVersion = loaderVersion,
            id = idOf(kind, mcVersion, loaderVersion),
            profileUrl = profileUrl(kind, mcVersion, loaderVersion)
        )

    // ---------- 磁盘缓存 ----------

    private fun diskFile(kind: LoaderKind, mcVersion: String): File =
        File(diskDir, "${kind.name}-${mcVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private fun diskGet(kind: LoaderKind, mcVersion: String): List<LoaderVersion>? = runCatching {
        val file = diskFile(kind, mcVersion)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > DISK_TTL) return null
        val list = (MiniJson.parse(file.readText(Charsets.UTF_8)) as? List<*>)
            ?.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LoaderVersion(id, map["stable"] == true)
            }
            ?: return null
        list.ifEmpty { null }
    }.getOrNull()

    private fun diskPut(kind: LoaderKind, mcVersion: String, list: List<LoaderVersion>) {
        runCatching {
            diskDir.mkdirs()
            val text = MiniJson.write(list.map { mapOf("id" to it.id, "stable" to it.stable) })
            diskFile(kind, mcVersion).writeText(text, Charsets.UTF_8)
        }
    }

    // ---------- 取文本 ----------

    /**
     * 按设置里的下载源依次试。官方地址在国内经常是**连接超时**（不是 4xx），
     * 所以第一条给短超时，连不上就赶紧换镜像，别让人盯着「读取…」等满 20 秒。
     */
    internal fun fetchText(url: String): String {
        val candidates = Prefs.fileSource.order(url)
        var last: Exception? = null
        candidates.forEachIndexed { index, candidate ->
            val timeout = if (index == 0 && candidates.size > 1) 6L else 20L
            try {
                return getText(candidate, timeout)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("读取失败")
    }

    private fun getText(url: String, timeoutSeconds: Long = 20L): String {
        val response = Http.get(url, timeoutSeconds)
        if (response.status !in 200..299) throw IOException(HttpText.of(response.status))
        return response.body
    }
}

/**
 * 一条加载器版本。`stable` 要跟着回来 —— 界面得标出「稳定版 / 测试版」，
 * 只留版本号的话用户只能靠记数字猜哪个是正式发布的。
 */
data class LoaderVersion(val id: String, val stable: Boolean) {

    /** Quilt 的 meta 不保证给 stable，版本号里的后缀是第二道判据。 */
    val preRelease: Boolean get() = listOf("-beta", "-rc", "-alpha").any { id.contains(it) }

    val label: String get() = if (stable) "稳定版" else "测试版"
}

/** 用户选定的那一个加载器。 */
data class LoaderChoice(
    val kind: LoaderKind,
    val mcVersion: String,
    val loaderVersion: String,
    /** 版本 id / `versions/` 下的文件夹名。 */
    val id: String,
    /** 概况地址：Fabric 系是 profile json，其余是安装包。 */
    val profileUrl: String
) {
    /** 任务页上那一行标题。 */
    val title: String get() = "${kind.label} $loaderVersion · $mcVersion"
}
