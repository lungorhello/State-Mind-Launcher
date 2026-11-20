package org.example.statemind.core.mod

import java.util.concurrent.ConcurrentHashMap

/**
 * 项目版本清单的内存缓存。
 *
 * 列表页一铺完就顺手把前几个项目的版本取回来 —— 点进详情页时多半是现成的，
 * 不用对着「正在读取版本…」干等。取失败的不缓存，下次点开还能重试。
 *
 * Modrinth 的版本清单按项目 id 走，同一个项目在任何 tab 下都是同一份，
 * 所以不区分内容类型。条目上限只是防止长时间开着越攒越多。
 */
object ModCache {

    private const val LIMIT = 60

    private val versions = ConcurrentHashMap<String, List<Modrinth.Version>>()

    fun versions(projectId: String): List<Modrinth.Version>? = versions[projectId]

    /** 取版本清单；已经有就直接给。会阻塞，只能在后台线程调。 */
    fun load(projectId: String): List<Modrinth.Version> {
        versions[projectId]?.let { return it }
        val list = Modrinth.versions(projectId)
        if (versions.size >= LIMIT) versions.clear()
        versions[projectId] = list
        return list
    }

    /** 已经有就不发请求，成功才留；列表页预取用这个。 */
    fun prefetch(projectId: String) {
        if (versions.containsKey(projectId)) return
        runCatching { load(projectId) }
    }
}
