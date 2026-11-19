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
}
