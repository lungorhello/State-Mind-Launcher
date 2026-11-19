package org.example.statemind.core.loader

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.example.statemind.core.HttpText
import org.example.statemind.core.MiniJson
import org.example.statemind.core.Prefs
import org.example.statemind.core.auth.Http

/**
 * 加载器版本从各自的 meta 服务取：Fabric 与 Quilt 都提供「一份现成的 profile json」，
 * 里面只有加载器自己的库和 mainClass，其余靠 `inheritsFrom` 挂在原版版本上。
 *
 * 所以这两个不需要跑安装器 —— 把 json 落到 `versions/<id>/`、再把库下下来就能启动。
 * Forge / NeoForge 没有这种 profile（要打补丁），走不了这条路，暂不接。
 */
object LoaderSource {

    /**
     * 每个 (加载器, 游戏版本) 只问一次服务器。
     *
     * 空表也缓存：接口回 200 却没有内容，就是这个版本确实没有安装包（老版本、快照、愚人节版本
     * 都常见）。不缓存的话每点一次 chip 都要再等一轮，正是「加载慢、反复加载」的来源。
     */
    private val cache = ConcurrentHashMap<String, List<LoaderVersion>>()

    fun cached(kind: LoaderKind, mcVersion: String): List<LoaderVersion>? = cache[keyOf(kind, mcVersion)]

    fun versions(kind: LoaderKind, mcVersion: String): List<LoaderVersion> {
        cache[keyOf(kind, mcVersion)]?.let { return it }
        val url = when (kind) {
            LoaderKind.FABRIC -> "https://meta.fabricmc.net/v2/versions/loader/$mcVersion"
            LoaderKind.QUILT -> "https://meta.quiltmc.org/v3/versions/loader/$mcVersion"
            else -> throw IOException("${kind.label} 暂不支持")
        }
        val body = fetch(url)
        val list = MiniJson.parse(body) as? List<*> ?: return emptyList()
        val parsed = list.mapNotNull { item ->
            val loader = (item as? Map<*, *>)?.get("loader") as? Map<*, *>
                ?: return@mapNotNull null
            val version = (loader["version"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            LoaderVersion(version, loader["stable"] == true)
        }.distinctBy { it.id }

        // 两家都按「新的在前」返回，但 Quilt 把 beta 排在正式版前面 —— 直接取第一个会选中 beta。
        // 稳定 > 普通 > 预发布；同一档里保持服务端给的顺序（sortedBy 是稳定排序）。
        val result = parsed.sortedByDescending {
            when {
                it.stable -> 2
                it.preRelease -> 0
                else -> 1
            }
        }
        cache[keyOf(kind, mcVersion)] = result
        return result
    }

    private fun keyOf(kind: LoaderKind, mcVersion: String): String = "${kind.name}@$mcVersion"

    fun profileUrl(kind: LoaderKind, mcVersion: String, loaderVersion: String): String = when (kind) {
        LoaderKind.FABRIC ->
            "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loaderVersion/profile/json"
        LoaderKind.QUILT ->
            "https://meta.quiltmc.org/v3/versions/loader/$mcVersion/$loaderVersion/profile/json"
        else -> throw IOException("${kind.label} 暂不支持")
    }

    /**
     * 版本 id 同时是 `versions/` 下的文件夹名。两家 profile json 里写的 id 都是这个形状
     * （`fabric-loader-<加载器版本>-<游戏版本>`），先按同一规则算出来，界面不必等联网。
     */
    fun idOf(kind: LoaderKind, mcVersion: String, loaderVersion: String): String = when (kind) {
        LoaderKind.FABRIC -> "fabric-loader-$loaderVersion-$mcVersion"
        LoaderKind.QUILT -> "quilt-loader-$loaderVersion-$mcVersion"
        else -> "${kind.label.lowercase()}-$loaderVersion-$mcVersion"
    }

    fun choice(kind: LoaderKind, mcVersion: String, loaderVersion: String): LoaderChoice =
        LoaderChoice(
            kind = kind,
            mcVersion = mcVersion,
            loaderVersion = loaderVersion,
            id = idOf(kind, mcVersion, loaderVersion),
            profileUrl = profileUrl(kind, mcVersion, loaderVersion)
        )

    /**
     * 按设置里的下载源依次试。官方 meta 在国内经常是**连接超时**（不是 4xx），
     * 所以第一条给短超时，连不上就赶紧换镜像，别让人盯着「读取…」等满 20 秒。
     */
    private fun fetch(url: String): String {
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
    /** profile json 的地址，装的时候原样落盘。 */
    val profileUrl: String
) {
    /** 任务页上那一行标题。 */
    val title: String get() = "${kind.label} $loaderVersion · $mcVersion"
}
