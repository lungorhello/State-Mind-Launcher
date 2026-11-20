package org.example.statemind.core.loader

/**
 * 版本号比较。
 *
 * 加载器清单是按字符串给的，`47.2.9` 直接比字符串会排到 `47.2.10` 后面 ——
 * 界面默认选中第一个，排错就等于默认选了个旧版本。数字段按数值比，字母段按字典比。
 */
internal object VersionOrder {

    fun newestFirst(list: List<LoaderVersion>): List<LoaderVersion> =
        list.sortedWith(Comparator { a, b -> compare(b.id, a.id) })

    fun compare(a: String, b: String): Int {
        val left = tokens(a)
        val right = tokens(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val x = left.getOrNull(i) ?: return -1
            val y = right.getOrNull(i) ?: return 1
            val result = compareToken(x, y)
            if (result != 0) return result
        }
        return 0
    }

    private fun tokens(text: String): List<String> =
        Regex("[0-9]+|[^0-9]+").findAll(text).map { it.value }.toList()

    private fun compareToken(a: String, b: String): Int {
        val numeric = a.all { it.isDigit() } && b.all { it.isDigit() }
        if (!numeric) return a.compareTo(b, ignoreCase = true)
        // 去掉前导零再比长度，`04` 与 `4` 才算同一个数
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
    }
}
