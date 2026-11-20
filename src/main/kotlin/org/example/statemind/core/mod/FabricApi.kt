package org.example.statemind.core.mod

import org.example.statemind.core.Prefs
import org.example.statemind.core.fileNameOf

/**
 * 装 Fabric / Quilt 版本时顺带把 Fabric API 放进 `mods/`。
 *
 * 它不是一个「可选的模组」，而是 Fabric 生态里几乎所有模组的前置依赖；
 * 装了加载器却没有它，用户下一分钟就会在启动器里看到一片模组加载失败。
 *
 * 现在它是**下载计划里的一段**（见 `DownloadStages.FABRIC_API`），跟版本本体一起下、
 * 一起显示进度 —— 不再是装完之后另开一个任务，免得用户以为版本已经齐了。
 */
object FabricApi {

    /** Modrinth 上这个项目的 slug 就是 `fabric-api`。 */
    private const val PROJECT = "fabric-api"

    /** Quilt 能直接吃 Fabric 的模组，Fabric API 同样可装。 */
    private val LOADERS = setOf("fabric", "quilt")

    /**
     * 整个项目的版本表。拉一次几百 KB，够慢 —— 缓存住，换游戏版本时不必重拉。
     */
    @Volatile
    private var cachedAll: List<Modrinth.Version>? = null

    /** 解析出来的那一个文件：下哪儿、多大、校验值。 */
    class Resolved(
        val url: String,
        val size: Long,
        val sha1: String?,
        /** 落盘文件名（已经按设置里的命名规则洗过）。 */
        val targetName: String
    )

    /**
     * 某个游戏版本能用的 Fabric API 版本，新在前。会阻塞，调用方扔后台线程。
     *
     * 界面要在「选了哪个加载器版本」之后把这张卡片显示出来，所以查询是公开的；
     * 真正安装时再按 id 找一次 —— 中间可能过了几分钟，列表未必还是那份。
     */
    fun versionsFor(mcVersion: String): List<Modrinth.Version> {
        val all = cachedAll ?: Modrinth.versions(PROJECT).also { cachedAll = it }
        return all.filter { version ->
            mcVersion in version.gameVersions && version.loaders.any { it in LOADERS }
        }
    }

    /**
     * 定下要装的那一个 Fabric API 文件。
     *
     * [apiVersionId] 是安装页上选中的那个版本 id；为空时按 [mcVersion] 挑最新的兼容版本。
     * 挑不出来返回 null —— 解析不了就不装这一段，前置缺了不该把整个版本下载判成失败。
     */
    fun resolve(mcVersion: String, apiVersionId: String?): Resolved? {
        val version = if (!apiVersionId.isNullOrBlank()) {
            Modrinth.version(apiVersionId)
        } else {
            null
        } ?: (cachedAll ?: Modrinth.versions(PROJECT).also { cachedAll = it }).firstOrNull { candidate ->
            mcVersion in candidate.gameVersions && candidate.loaders.any { it in LOADERS }
        } ?: return null
        val file = version.files.firstOrNull() ?: return null
        return Resolved(
            url = file.url,
            size = file.size,
            sha1 = file.sha1.takeIf { it.isNotBlank() },
            targetName = fileNameOf(Prefs.resourceNaming, "Fabric API", file.fileName)
        )
    }
}
