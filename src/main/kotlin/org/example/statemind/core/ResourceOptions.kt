package org.example.statemind.core

/**
 * 「设置 · 下载」：社区资源（模组、资源包、光影）装到哪。
 *
 * [CUSTOM] 不是「记住一个固定目录」，而是**每次下载都弹一次系统的目录选择框** ——
 * 只装一两个模组时，把它塞进哪份游戏目录是临时决定，写死在设置里反而要去改设置才能换。
 * 上次选过的目录记在 [Prefs.resourceDir] 里，只作为下次弹框的起始位置。
 */
enum class ResourceTarget(override val label: String) : DownloadOption {
    CURRENT("当前选中的版本"),
    CUSTOM("每次下载时选择");

    companion object {
        val DEFAULT = CURRENT

        /** 配置文件里写了不认识的名字（手改、或以后删掉某一档）就退回默认。 */
        fun of(name: String?): ResourceTarget = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 「设置 · 下载」：落盘时用什么文件名。五档对应 PCL 的那五种写法，
 * 认文件名习惯不同的用户各取所需；标题里可能有 Windows 不认的字符，[fileNameOf] 会先洗一遍。
 */
enum class ResourceNaming(override val label: String) : DownloadOption {
    ORIGINAL("原文件名"),
    BRACKET("[标题] 原文件名"),
    CORNER("【标题】原文件名"),
    TITLE_FIRST("标题-原文件名"),
    TITLE_LAST("原文件名-标题");

    companion object {
        val DEFAULT = BRACKET

        fun of(name: String?): ResourceNaming = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Windows 文件名里不能出现的字符。斜杠也换掉 —— 它会变成目录层级。 */
private val ILLEGAL_IN_NAME = Regex("""[\\/:*?"<>|]""")

/**
 * 按 [naming] 算出最终文件名。
 *
 * 带标题的几档都会把标题洗一遍再拼；扩展名始终留在最后，
 * 否则 `xxx-机械动力.jar` 变成 `xxx.jar-机械动力` 就双击不开了。
 */
fun fileNameOf(naming: ResourceNaming, title: String, fileName: String): String {
    val cleanTitle = title.replace(ILLEGAL_IN_NAME, "_").trim().ifEmpty { "未命名" }
    val dot = fileName.lastIndexOf('.')
    val stem = if (dot > 0) fileName.substring(0, dot) else fileName
    val ext = if (dot > 0) fileName.substring(dot) else ""
    val titled = when (naming) {
        ResourceNaming.ORIGINAL -> return fileName
        ResourceNaming.BRACKET -> "[$cleanTitle] $fileName"
        ResourceNaming.CORNER -> "【$cleanTitle】$fileName"
        ResourceNaming.TITLE_FIRST -> "$cleanTitle-$stem$ext"
        ResourceNaming.TITLE_LAST -> "$stem-$cleanTitle$ext"
    }
    return titled
}
