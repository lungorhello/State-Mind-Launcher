package org.example.statemind.core.download

import java.io.File
import java.io.IOException
import org.example.statemind.core.FileSource
import org.example.statemind.core.MiniJson
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.VersionJson
import org.example.statemind.core.auth.Http

/** 一个待下载的文件：落到哪儿、按什么顺序试哪些地址、多大、校验值。 */
class DownloadItem(
    val target: File,
    val urls: List<String>,
    val size: Long,
    val sha1: String?
)

/**
 * 下载计划 —— **先把要下什么全算出来，再交给 [DownloadRunner] 执行**。
 *
 * 与启动那条 `LaunchUtil.plan()` 同一个思路：算与执行分开，才能先告诉用户「这次要下多少」，
 * 也才能在真正动手前就把「哪些库没有地址」这类问题抖出来。
 */
class DownloadPlan(
    val versionId: String,
    val files: List<DownloadItem>,
    val totalBytes: Long,
    val assetsDir: File,
    val gameDir: File,
    val assetIndexId: String,
    /** 老版本索引的标志位：资源要额外铺一份到 `assets/virtual/<索引>/`。 */
    val virtualAssets: Boolean,
    /** 老版本索引的标志位：资源要额外铺一份到游戏目录的 `resources/`。 */
    val mapToResources: Boolean,
    /** 没有下载地址、只能跳过的库（第三方 maven 那些）。 */
    val skipped: List<String>
)

object DownloadPlanner {

    private const val RESOURCES = "https://resources.download.minecraft.net"

    /**
     * 会阻塞（要读版本 json 和资源索引）。落点是**版本隔离**目录：游戏本体进 `versions/<id>/`，
     * 共享的 `libraries/` 与 `assets/` 进游戏目录根。
     */
    fun plan(
        entry: VersionEntry,
        root: File,
        source: FileSource,
        onNote: (String) -> Unit = {}
    ): DownloadPlan {
        val versionDir = File(root, "versions/${entry.id}")
        val libDir = File(root, "libraries")
        val assetsDir = File(root, "assets")

        // 同一个目标路径只留一条：库表里偶有重复条目，重复下没意义
        val files = LinkedHashMap<String, DownloadItem>()
        fun add(target: File, url: String?, size: Long, sha1: String?) {
            if (url.isNullOrBlank()) return
            val item = DownloadItem(target, source.order(url), size, sha1?.takeIf { it.isNotBlank() })
            files.putIfAbsent(target.absolutePath, item)
        }

        onNote("读取版本信息…")
        add(
            File(versionDir, "${entry.id}.json"), entry.url, entry.size, entry.sha1,
        )
        val json = MiniJson.parse(fetchText(entry.url, source)) as? Map<*, *>
            ?: throw IOException("版本文件不是 JSON 对象")

        // ① 客户端本体
        val downloads = json["downloads"] as? Map<*, *>
        val client = downloads?.get("client") as? Map<*, *>
        if (client != null) {
            add(
                File(versionDir, "${entry.id}.jar"),
                client["url"] as? String,
                number(client["size"]),
                client["sha1"] as? String
            )
        }

        // ② 依赖库。新格式（1.19+）natives 本身就是一条带分类符的库，走 artifact；
        //    老格式（1.12 及以前）另有一份 natives 包，在 downloads.classifiers 里
        onNote("整理依赖库…")
        val skipped = ArrayList<String>()
        val libs = (json["libraries"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        for (lib in libs) {
            if (!VersionJson.rulesAllow(lib["rules"])) continue
            val dl = lib["downloads"] as? Map<*, *>
            val artifact = dl?.get("artifact") as? Map<*, *>
            if (artifact != null) {
                addArtifact(libDir, artifact, ::add)
            } else if (dl == null) {
                // Fabric 那类只给 maven 坐标、连地址都没有的库：这轮只做原版，如实记下来
                (lib["name"] as? String)?.let { skipped += it }
            }
            val nativeKey = (lib["natives"] as? Map<*, *>)?.get(VersionJson.osName) as? String
            if (nativeKey != null) {
                val classifier = (dl?.get("classifiers") as? Map<*, *>)?.get(nativeKey) as? Map<*, *>
                if (classifier != null) addArtifact(libDir, classifier, ::add)
            }
        }

        // ③ 资源索引与资源对象。资源对象只有拿到索引才知道有哪些，所以这里必须先把它读下来
        onNote("读取资源索引…")
        val index = json["assetIndex"] as? Map<*, *>
        val indexId = (index?.get("id") as? String) ?: (json["assets"] as? String) ?: "legacy"
        var virtual = false
        var mapToResources = false
        if (index != null) {
            val indexUrl = index["url"] as? String
            add(
                File(assetsDir, "indexes/$indexId.json"),
                indexUrl,
                number(index["size"]),
                index["sha1"] as? String
            )
            val body = fetchText(indexUrl, source)
            val parsed = MiniJson.parse(body) as? Map<*, *>
            val objects = parsed?.get("objects") as? Map<*, *>
            virtual = parsed?.get("virtual") == true
            mapToResources = parsed?.get("map_to_resources") == true
            for ((_, value) in objects.orEmpty()) {
                val obj = value as? Map<*, *> ?: continue
                val hash = obj["hash"] as? String ?: continue
                if (hash.length < 2) continue
                val sub = hash.substring(0, 2)
                add(
                    File(assetsDir, "objects/$sub/$hash"),
                    "$RESOURCES/$sub/$hash",
                    number(obj["size"]),
                    hash
                )
            }
        }

        // ④ 日志配置（log4j2 的 xml，约 1 KB，缺了会刷一堆警告）
        val logFile = ((json["logging"] as? Map<*, *>)?.get("client") as? Map<*, *>)
            ?.get("file") as? Map<*, *>
        val logId = logFile?.get("id") as? String
        if (logFile != null && !logId.isNullOrBlank()) {
            add(File(assetsDir, "log_configs/$logId"), logFile["url"] as? String, number(logFile["size"]), logFile["sha1"] as? String)
        }

        val list = files.values.toList()
        return DownloadPlan(
            versionId = entry.id,
            files = list,
            totalBytes = list.sumOf { it.size },
            assetsDir = assetsDir,
            gameDir = versionDir,
            assetIndexId = indexId,
            virtualAssets = virtual,
            mapToResources = mapToResources,
            skipped = skipped
        )
    }

    private fun addArtifact(
        libDir: File,
        artifact: Map<*, *>,
        add: (File, String?, Long, String?) -> Unit
    ) {
        val path = artifact["path"] as? String ?: return
        if (path.isBlank()) return
        add(File(libDir, path), artifact["url"] as? String, number(artifact["size"]), artifact["sha1"] as? String)
    }

    /** 依次试 [source] 排好的地址，拿到第一个成功响应的正文。 */
    private fun fetchText(url: String?, source: FileSource): String {
        if (url.isNullOrBlank()) throw IOException("地址为空")
        var last: Exception? = null
        for (candidate in source.order(url)) {
            val response = runCatching { Http.get(candidate, 30) }
            if (response.isSuccess && response.getOrNull()?.status in 200..299) {
                return response.getOrThrow().body
            }
            last = response.exceptionOrNull() as? Exception
                ?: IOException("HTTP ${response.getOrNull()?.status}")
        }
        throw IOException("读取失败：$url", last)
    }

    private fun number(value: Any?): Long = (value as? Number)?.toLong() ?: 0L
}
