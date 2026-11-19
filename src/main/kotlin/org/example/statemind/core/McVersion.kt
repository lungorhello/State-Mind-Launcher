package org.example.statemind.core

/**
 * 版本号的只有一处比较规则。
 *
 * 按点与横线分段，两边都是数字就按数值比，否则按字符串比 —— 按字符串直接比会把
 * `1.9` 排在 `1.21` 后面。列表排序与「这个版本够不够新」都走它，免得两处各写一份。
 */
object McVersion {

    fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-')
        val pb = b.split('.', '-')
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

    fun atLeast(version: String, floor: String): Boolean = compare(version, floor) >= 0

    /**
     * 社区内容筛选里值得列出来的版本：长期在用的节点版本，加上清单里最新的几个正式版。
     *
     * 不写死成「只留表里的」—— 表是死的、游戏还在更新，最新几档跟着清单自动走，
     * 才不会出现「刚装好的新版本，找模组时却选不到」。[keep] 是当前选中的版本，
     * 它就算冷门也得留着，否则筛选会莫名其妙跳回别的版本。
     */
    fun commonVersions(all: List<String>, keep: String? = null): List<String> {
        val sorted = all.sortedWith(Comparator { a, b -> compare(b, a) })
        val newest = sorted.take(NEWEST_KEEP).toSet()
        return sorted.filter { it in COMMON || it in newest || it == keep }
    }

    /** 整合包、模组与教程大多落在这几个版本上。 */
    private val COMMON = setOf(
        "1.7.10", "1.8.9", "1.12.2", "1.18.2", "1.19.2", "1.20.1", "1.20.4",
        "1.21", "1.21.1", "1.21.8", "1.21.11", "26.1", "26.2", "26.3"
    )

    /** 清单里最新的几个正式版永远保留 —— 这一档不需要人维护。 */
    private const val NEWEST_KEEP = 3
}
