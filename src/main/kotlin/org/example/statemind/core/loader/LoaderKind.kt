package org.example.statemind.core.loader

/** 安装页上能选的加载器。顺序就是界面里的顺序。 */
enum class LoaderKind(val label: String, val supported: Boolean) {

    VANILLA("原版", true),
    FABRIC("Fabric", true),
    QUILT("Quilt", true),

    /** 这两个要用官方安装器改文件，还没接 —— 先灰着，别让用户点进一条走不通的路。 */
    NEOFORGE("NeoForge", false),
    FORGE("Forge", false)
}
