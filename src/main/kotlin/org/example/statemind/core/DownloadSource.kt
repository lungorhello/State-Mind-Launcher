package org.example.statemind.core

interface DownloadSource {

    val label: String
    val manifestUrl: String
}

object OfficialSource : DownloadSource {
    override val label = "官方"
    override val manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
}

/** 镜像规则见 <https://bmclapidoc.bangbang93.com/> */
object BmclapiSource : DownloadSource {
    override val label = "BMCLAPI"
    override val manifestUrl = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
}

/** 先 BMCLAPI，失败退官方。 */
val DEFAULT_SOURCES: List<DownloadSource> = listOf(BmclapiSource, OfficialSource)
