package org.example.statemind.ui.page.download

/** 界面上那四个加载器名与 Modrinth 的 `loaders` 值之间的对照。 */
internal object ModLoaders {

    val PAIRS = listOf(
        "Fabric" to "fabric",
        "Quilt" to "quilt",
        "Forge" to "forge",
        "NeoForge" to "neoforge"
    )

    fun valueOf(label: String?): String? = PAIRS.firstOrNull { it.first == label }?.second

    /** Modrinth 里的加载器值翻回界面上的名字；不认识的照原样。 */
    fun labelOf(value: String): String = PAIRS.firstOrNull { it.second == value }?.first ?: value

    fun labelsOf(values: List<String>): String = values.joinToString(" / ") { labelOf(it) }
}
