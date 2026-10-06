package org.example.statemind.core

interface DownloadSource {

    val label: String
    val manifestUrl: String

    /**
     * 把一个官方地址翻成这个源的等价地址；不认识的 host 返回 null。
     * 「同一个文件在几个源上分别是什么地址」这件事归源自己管，调用方只按顺序试。
     */
    fun map(url: String): String?
}

object OfficialSource : DownloadSource {
    override val label = "官方"
    override val manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    override fun map(url: String): String = url
}

/** 镜像规则见 <https://bmclapidoc.bangbang93.com/>：piston 系只换 host，maven 与 assets 另有前缀。 */
object BmclapiSource : DownloadSource {
    override val label = "BMCLAPI"
    override val manifestUrl = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"

    override fun map(url: String): String? {
        for ((from, to) in RULES) {
            if (url.startsWith(from)) return to + url.substring(from.length)
        }
        return null
    }

    private val RULES = listOf(
        "https://libraries.minecraft.net" to "$BASE/maven",
        "https://resources.download.minecraft.net" to "$BASE/assets",
        "https://launchermeta.mojang.com" to BASE,
        "https://launcher.mojang.com" to BASE,
        "https://piston-meta.mojang.com" to BASE,
        "https://piston-data.mojang.com" to BASE
    )

    private const val BASE = "https://bmclapi2.bangbang93.com"
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

/**
 * 「文件下载源」：拿到一个下载地址后，按这个顺序去试。
 *
 * 顺序在枚举里定死，地址怎么翻由各 [DownloadSource] 自己负责 —— 加一个源不需要动这里。
 */
enum class FileSource(override val label: String) : DownloadOption {

    MIRROR_FIRST("尽量使用镜像源") {
        override fun order(url: String) = fanOut(url, BmclapiSource, OfficialSource)
    },
    OFFICIAL_FIRST("优先使用官方源") {
        override fun order(url: String) = fanOut(url, OfficialSource, BmclapiSource)
    },
    OFFICIAL_ONLY("只使用官方源") {
        override fun order(url: String) = listOf(OfficialSource.map(url))
    },
    MIRROR_ONLY("只使用镜像源") {
        override fun order(url: String) = BmclapiSource.map(url)?.let(::listOf) ?: listOf(url)
    };

    /** 同一个文件依次要试的地址。空不了：翻不出来的源会退到原地址。 */
    abstract fun order(url: String): List<String>

    companion object {
        val DEFAULT = MIRROR_FIRST

        fun of(name: String?): FileSource = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 都翻不出来时退到原地址 —— 第三方 maven（Fabric 之类）镜像并不认识。 */
private fun fanOut(url: String, first: DownloadSource, second: DownloadSource): List<String> =
    listOfNotNull(first.map(url), second.map(url)).distinct().ifEmpty { listOf(url) }
