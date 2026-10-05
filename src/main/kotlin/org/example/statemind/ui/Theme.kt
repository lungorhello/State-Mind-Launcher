package org.example.statemind.ui

/**
 * 全局配色。强调色**只有这一个来源**。
 *
 * Atlantafx 的主题把强调色定义在 `.root` 上（PrimerLight 默认是蓝 `#0969da`），
 * 输入框的聚焦边框、主按钮底色、开关、各种选中态都引用那一族变量。要换掉它，
 * 只能覆盖**同一族**变量，并且挂在一棵能罩住所有控件的父节点上：
 * 挂在单个控件上只有那个控件变色，别的还是蓝的 —— 「两个下拉颜色不一样」就是这么来的。
 *
 * `-color-accent-emphasis` 是「强调」那一档（聚焦边框、主按钮底），
 * `-color-accent-fg/-muted/-subtle` 分别对应强调文字、半透明强调底、浅强调底。
 * JavaFX 的 looked-up color 会被子节点继承，所以挂在场景根上，整棵树都跟着变。
 */
object Theme {

    /** 主色，界面里所有「我们自己的」强调都用它。 */
    const val ACCENT = "#7c3aed"

    /** 强调色上的文字：比主色再深一档，压在淡紫底上才读得清。 */
    const val ACCENT_TEXT = "#6d28d9"

    /** 选中项底色。 */
    const val ACCENT_BG = "#efe6fd"

    /** 主色描边（淡）：卡片、竖条这类只用它勾边。 */
    const val ACCENT_LINE = "#cdb6f2"

    /** 主色淡档，用在面积大的块上比主色柔。 */
    const val ACCENT_SOFT = "#a78bfa"

    /**
     * 覆盖整族强调色。挂到场景根上即可全局生效，不必给每个控件单独写样式。
     * 色阶 0→9 由浅到深，与 PrimerLight 里蓝色的排布一一对应。
     */
    val accentStyle: String = listOf(
        "-color-accent-0" to "#f5f0ff",
        "-color-accent-1" to "#ede4fd",
        "-color-accent-2" to "#ddd0fb",
        "-color-accent-3" to "#c9b3f8",
        "-color-accent-4" to ACCENT_SOFT,
        "-color-accent-5" to ACCENT,
        "-color-accent-6" to ACCENT_TEXT,
        "-color-accent-7" to "#5b21b6",
        "-color-accent-8" to "#4c1d95",
        "-color-accent-9" to "#3b1578",
        "-color-accent-emphasis" to ACCENT,
        "-color-accent-fg" to ACCENT_TEXT,
        "-color-accent-muted" to "rgba(124, 58, 237, 0.4)",
        "-color-accent-subtle" to ACCENT_BG
    ).joinToString(" ") { (name, value) -> "$name: $value;" }
}
