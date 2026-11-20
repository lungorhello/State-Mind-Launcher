package org.example.statemind.core.loader

/** 安装页上能选的加载器。顺序就是界面里的顺序。 */
enum class LoaderKind(val label: String, val supported: Boolean) {

    VANILLA("原版", true),
    FABRIC("Fabric", true),
    QUILT("Quilt", true),

    /** 官方安装器那套：下载后要跑一段 processor（见 core/install/ForgeInstall）。 */
    FORGE("Forge", true),
    NEOFORGE("NeoForge", true),

    /** 只到 1.12.2，走 [LiteLoaderSource] 自己拼版本 json，不用官方安装器。 */
    LITELOADER("LiteLoader", true),

    /** 自带补丁器，改客户端 jar 后走 launchwrapper（见 core/install/OptiFineInstall）。 */
    OPTIFINE("OptiFine", true)
}
