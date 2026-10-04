package org.example.statemind.core

import org.example.statemind.core.auth.Http
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime

/** 会阻塞，调用方负责扔后台线程。 */
object RemoteVersion {

    data class Result(
        val latestRelease: VersionEntry?,
        val latestSnapshot: VersionEntry?,
        val versions: List<VersionEntry>,

        /** 走缓存时是 [sources] 的第一个。 */
        val source: DownloadSource,
        val fromCache: Boolean
    )

    /** 依次尝试 [sources]，任一成功即返回；都失败退到缓存，两边都没有才抛。 */
    fun load(sources: List<DownloadSource>): Result {
        var failure: Throwable? = null
        for (source in sources) {
            runCatching { fetchFromNetwork(source) }
                .onSuccess { return it }
                .onFailure { failure = it }
        }
        cached(sources.first())?.let { return it }
        val cause = failure
        throw if (cause is Exception) cause else IOException("版本清单获取失败", cause)
    }

    /** 文件不在或内容坏了都返回 null：缓存允许丢，读不出来不该报错。 */
    fun cached(source: DownloadSource): Result? {
        val file = cacheFile
        if (!file.isFile) return null
        return runCatching { parse(file.readText(Charsets.UTF_8), source) }
            .getOrNull()
            ?.copy(fromCache = true)
    }

    private fun fetchFromNetwork(source: DownloadSource): Result {
        val response = Http.get(source.manifestUrl)
        if (response.status !in 200..299) throw IOException("HTTP ${response.status}")
        val parsed = parse(response.body, source)
        writeCache(response.body)
        return parsed
    }

    private fun parse(text: String, source: DownloadSource): Result {
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw IOException("版本清单不是 JSON 对象")
        val versions = (root["versions"] as? List<*>).orEmpty().mapNotNull { entryOf(it) }
        if (versions.isEmpty()) throw IOException("版本清单里没有版本")

        val latest = root["latest"] as? Map<*, *>
        fun latestOf(key: String): VersionEntry? {
            val id = latest?.get(key) as? String ?: return null
            return versions.firstOrNull { it.id == id }
        }
        return Result(latestOf("release"), latestOf("snapshot"), versions, source, fromCache = false)
    }

    private fun entryOf(raw: Any?): VersionEntry? {
        val map = raw as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        return VersionEntry(
            id = id,
            type = VersionEntry.typeOf(map["type"] as? String),
            url = map["url"] as? String ?: "",
            releaseTime = (map["releaseTime"] as? String)
                ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
            sha1 = map["sha1"] as? String ?: "",
            size = (map["size"] as? Number)?.toLong() ?: 0L
        )
    }

    /** 放数据根下，换游戏目录不该让它失效。 */
    private val cacheFile: File get() = File(GameDir.appRoot(), "cache/version_manifest.json")

    private fun writeCache(text: String) {
        runCatching {
            val file = cacheFile
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        }
    }
}
