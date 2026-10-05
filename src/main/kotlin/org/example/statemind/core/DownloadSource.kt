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

/** 取一次清单：[source] 超过 [timeoutSeconds] 秒还不回来就换下一个。 */
data class SourceAttempt(val source: DownloadSource, val timeoutSeconds: Long = 15)

/** 设置页下拉里的一项：有个能直接显示的名字。 */
interface DownloadOption {
    val label: String
}

/**
 * 「版本列表源」的几种取法。
 *
 * 官方源排头时给的是短超时：慢就赶紧换镜像，别让用户对着空白界面干等。
 */
enum class VersionListSource(override val label: String, val attempts: List<SourceAttempt>) : DownloadOption {

    OFFICIAL_FIRST(
        "优先使用官方源，加载缓慢时换用镜像源",
        listOf(SourceAttempt(OfficialSource, 5L), SourceAttempt(BmclapiSource))
    ),
    MIRROR_FIRST(
        "尽量使用镜像源",
        listOf(SourceAttempt(BmclapiSource), SourceAttempt(OfficialSource))
    ),
    OFFICIAL_ONLY("只使用官方源", listOf(SourceAttempt(OfficialSource))),
    MIRROR_ONLY("只使用镜像源", listOf(SourceAttempt(BmclapiSource)));

    companion object {
        val DEFAULT = OFFICIAL_FIRST

        /** 认不出来的名字（手改配置文件写错了）退回默认。 */
        fun of(name: String?): VersionListSource = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 「文件下载源」：文件下载器下一轮才消费它，本页只负责存下来。 */
enum class FileSource(override val label: String) : DownloadOption {

    MIRROR_FIRST("尽量使用镜像源"),
    OFFICIAL_FIRST("优先使用官方源"),
    OFFICIAL_ONLY("只使用官方源"),
    MIRROR_ONLY("只使用镜像源");

    companion object {
        val DEFAULT = MIRROR_FIRST

        fun of(name: String?): FileSource = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
