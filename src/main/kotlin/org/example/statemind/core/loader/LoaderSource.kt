package org.example.statemind.core.loader

import java.io.IOException
import org.example.statemind.core.MiniJson
import org.example.statemind.core.auth.Http

/**
 * 加载器版本从各自的 meta 服务取：Fabric 与 Quilt 都提供「一份现成的 profile json」，
 * 里面只有加载器自己的库和 mainClass，其余靠 `inheritsFrom` 挂在原版版本上。
 *
 * 所以这两个不需要跑安装器 —— 把 json 落到 `versions/<id>/`、再把库下下来就能启动。
 * Forge / NeoForge 没有这种 profile（要打补丁），走不了这条路，暂不接。
 */
object LoaderSource {

    fun versions(kind: LoaderKind, mcVersion: String): List<String> {
        val url = when (kind) {
            LoaderKind.FABRIC -> "https://meta.fabricmc.net/v2/versions/loader/$mcVersion"
            LoaderKind.QUILT -> "https://meta.quiltmc.org/v3/versions/loader/$mcVersion"
            else -> throw IOException("${kind.label} 暂不支持")
        }
        val body = getText(url)
        val list = MiniJson.parse(body) as? List<*> ?: return emptyList()
        val parsed = list.mapNotNull { item ->
            val loader = (item as? Map<*, *>)?.get("loader") as? Map<*, *>
            val version = (loader?.get("version") as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            version to (loader?.get("stable") as? Boolean)
        }.distinctBy { it.first }

        // 两家都按「新的在前」返回，但 Quilt 把 beta 排在正式版前面 —— 直接取第一个会选中 beta。
        // 稳定 > 普通 > 预发布；同一档里保持服务端给的顺序（sortedBy 是稳定排序）。
        return parsed.sortedByDescending { (version, stable) ->
            val pre = listOf("-beta", "-rc", "-alpha").any { version.contains(it) }
            when {
                stable == true -> 2
                pre -> 0
                else -> 1
            }
        }.map { it.first }
    }

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

    private fun getText(url: String): String {
        val response = Http.get(url, 20)
        if (response.status !in 200..299) throw IOException("HTTP ${response.status}")
        return response.body
    }
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
