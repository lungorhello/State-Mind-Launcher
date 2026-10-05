package org.example.statemind.ui.page.download

internal data class CommonVersion(
    val id: String,

    /** 一个 = 纯色；多个 = 沿版本号方向的渐变。 */
    val colors: List<String>
)

internal val COMMON_VERSIONS: List<CommonVersion> = listOf(
    CommonVersion("1.21.1", listOf("#c98f4d")),
    CommonVersion("1.20.1", listOf("#e088ae")),
    CommonVersion("1.16.5", listOf("#a8463a")),
    CommonVersion("1.12.2", listOf("#4a9b68")),
    CommonVersion("1.7.10", listOf("#7c3aed", "#0ea5e9", "#22c55e", "#f59e0b", "#ef4444"))
)
