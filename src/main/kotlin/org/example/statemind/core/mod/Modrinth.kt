package org.example.statemind.core.mod

import java.io.IOException
import java.net.URLEncoder
import org.example.statemind.core.HttpText
import org.example.statemind.core.MiniJson
import org.example.statemind.core.auth.Http

/**
 * Modrinth 的搜索、项目详情与文件清单。会阻塞，调用方负责扔后台线程。
 *
 * 用它的公开 API，不需要 key；[UA] 必须能认出是谁在请求（Modrinth 的使用条款要求）。
 */
object Modrinth {

    private const val BASE = "https://api.modrinth.com/v2"
    private const val UA = "StateMindLauncher/0.3 (github.com/lungorhello/State-Mind-Launcher)"

    data class Hit(
        val id: String,
        val slug: String,
        val title: String,
        val description: String,
        val author: String,
        val downloads: Long,
        val categories: List<String>,
        val gameVersions: List<String>,
        val iconUrl: String
    )

    /** 项目详情页要的东西，比搜索命中多几个链接字段。 */
    data class Project(
        val id: String,
        val slug: String,
        val title: String,
        val description: String,
        val iconUrl: String,
        val downloads: Long,
        val categories: List<String>,
        val gameVersions: List<String>,
        val loaders: List<String>,
        val sourceUrl: String,
        val updated: String
    )

    data class SearchResult(val hits: List<Hit>, val total: Int)

    /** 一个可下载的文件。[fileName] 已经能直接当落盘文件名用。 */
    data class File(
        val projectId: String,
        val versionName: String,
        val fileName: String,
        val url: String,
        val size: Long,
        val sha1: String,
        val gameVersions: List<String>,
        val loaders: List<String>
    )

    /** 一个发布版本，可能挂多个文件（源码包、dev jar），[files] 里已经只留主文件。 */
    data class Version(
        val id: String,
        val name: String,
        val number: String,
        val published: String,
        val downloads: Long,
        val gameVersions: List<String>,
        val loaders: List<String>,
        val files: List<File>
    )

    enum class Sort(val label: String, val index: String) {
        RELEVANCE("推荐", "relevance"),
        DOWNLOADS("下载量", "downloads"),
        UPDATED("最近更新", "updated")
    }

    fun search(
        kind: ModKind,
        query: String,
        gameVersion: String?,
        loader: String?,
        sort: Sort,
        offset: Int,
        limit: Int = 20
    ): SearchResult {
        // 没有搜索词时「相关度」没有意义，按下载量给一份热门列表
        val index = if (query.isBlank() && sort == Sort.RELEVANCE) Sort.DOWNLOADS.index else sort.index
        val url = StringBuilder("$BASE/search?limit=$limit&offset=$offset&index=$index")
        if (query.isNotBlank()) url.append("&query=").append(encode(query))
        facetsOf(kind, gameVersion, loader)?.let { url.append("&facets=").append(encode(it)) }

        val root = getJson(url.toString()) as? Map<*, *> ?: throw IOException("搜索结果格式不对")
        val hits = (root["hits"] as? List<*>).orEmpty().mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val id = map["project_id"] as? String ?: return@mapNotNull null
            Hit(
                id = id,
                slug = map["slug"] as? String ?: id,
                title = map["title"] as? String ?: id,
                description = map["description"] as? String ?: "",
                author = map["author"] as? String ?: "",
                downloads = (map["downloads"] as? Number)?.toLong() ?: 0L,
                categories = strings(map["display_categories"] ?: map["categories"]),
                gameVersions = strings(map["versions"]),
                iconUrl = map["icon_url"] as? String ?: ""
            )
        }
        return SearchResult(hits, (root["total_hits"] as? Number)?.toInt() ?: hits.size)
    }

    fun project(projectId: String): Project {
        val map = getJson("$BASE/project/$projectId") as? Map<*, *>
            ?: throw IOException("项目详情格式不对")
        return Project(
            id = map["id"] as? String ?: projectId,
            slug = map["slug"] as? String ?: projectId,
            title = map["title"] as? String ?: projectId,
            description = map["description"] as? String ?: "",
            iconUrl = map["icon_url"] as? String ?: "",
            downloads = (map["downloads"] as? Number)?.toLong() ?: 0L,
            categories = strings(map["categories"]),
            gameVersions = strings(map["game_versions"]),
            loaders = strings(map["loaders"]),
            sourceUrl = map["source_url"] as? String ?: "",
            updated = (map["updated"] as? String).orEmpty().substringBefore('T')
        )
    }

    /** 该项目的全部发布版本，新的在前。筛选在界面上做 —— 详情页要按版本 / 加载器现场过滤。 */
    fun versions(projectId: String): List<Version> {
        val list = getJson("$BASE/project/$projectId/version") as? List<*>
            ?: throw IOException("文件列表格式不对")
        return list.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val files = (map["files"] as? List<*>).orEmpty()
                .filterIsInstance<Map<*, *>>()
                .let { all -> listOfNotNull(all.firstOrNull { it["primary"] == true } ?: all.firstOrNull()) }
                .mapNotNull { file ->
                    val link = file["url"] as? String ?: return@mapNotNull null
                    val name = file["filename"] as? String ?: return@mapNotNull null
                    File(
                        projectId = projectId,
                        versionName = map["version_number"] as? String ?: map["name"] as? String ?: name,
                        fileName = name,
                        url = link,
                        size = (file["size"] as? Number)?.toLong() ?: 0L,
                        sha1 = (file["hashes"] as? Map<*, *>)?.get("sha1") as? String ?: "",
                        gameVersions = strings(map["game_versions"]),
                        loaders = strings(map["loaders"])
                    )
                }
            if (files.isEmpty()) return@mapNotNull null
            Version(
                id = map["id"] as? String ?: return@mapNotNull null,
                name = map["name"] as? String ?: "",
                number = map["version_number"] as? String ?: "",
                published = (map["date_published"] as? String).orEmpty().substringBefore('T'),
                downloads = (map["downloads"] as? Number)?.toLong() ?: 0L,
                gameVersions = strings(map["game_versions"]),
                loaders = strings(map["loaders"]),
                files = files
            )
        }
    }

    private fun facetsOf(kind: ModKind, gameVersion: String?, loader: String?): String? {
        val groups = ArrayList<List<String>>()
        groups += listOf("project_type:${kind.projectType}")
        if (!gameVersion.isNullOrBlank()) groups += listOf("versions:$gameVersion")
        if (kind.filterByLoader && !loader.isNullOrBlank()) groups += listOf("categories:$loader")
        return groups.joinToString(",", "[", "]") { group ->
            group.joinToString(",", "[", "]") { "\"$it\"" }
        }
    }

    private fun getJson(url: String): Any? {
        val response = Http.get(url, mapOf("User-Agent" to UA), 20L)
        if (response.status !in 200..299) {
            throw IOException("Modrinth ${HttpText.of(response.status)}")
        }
        return MiniJson.parse(response.body)
    }

    private fun strings(value: Any?): List<String> =
        (value as? List<*>).orEmpty().filterIsInstance<String>()

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
