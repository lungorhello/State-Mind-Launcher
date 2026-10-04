package org.example.statemind.ui.page.download

internal data class CommonVersion(
    val id: String,

    /** 自上而下；两色渐变，多色多彩。 */
    val colors: List<String>
)

internal val COMMON_VERSIONS: List<CommonVersion> = listOf(
    CommonVersion("1.21.1", listOf("#8a5a2b", "#c98f4d")),
    CommonVersion("1.20.1", listOf("#b3457a", "#e088ae")),
    CommonVersion("1.16.5", listOf("#6b2a22", "#a8463a")),
    CommonVersion("1.12.2", listOf("#2a6b45", "#4a9b68")),
    CommonVersion("1.7.10", listOf("#7c3aed", "#0ea5e9", "#22c55e", "#f59e0b", "#ef4444"))
)
