package org.example.statemind.ui

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ContentDisplay
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

/**
 * 左侧导航栏（竖排）。点一个标签就打开对应页面。
 *
 * 两种形态：
 *  - 普通（默认）：主窗口左侧那列，84 宽。按钮是**近正方形的圆角块** —— 图标在上、文字在下，
 *    图标是矢量的（[NavIcons]，直接在代码里画，不是图片），选中时跟着文字一起变紫；
 *  - [compact]：页面内部的二级导航（如「设置」里的 启动 / 玩家 / 实例），只有文字，150 宽。
 *
 * 两者的选中态是同一套配色：淡紫底 + 紫字。
 *
 * **宽度固定**：进二级页面也不收窄 —— 84 已经够窄，再收图标就没地方放了。
 *
 * 按钮的选中状态由 [select] 同步、不自己维护，这样将来用代码主动跳页时标签也会跟着亮。
 *
 * 注意参数顺序：[compact] 放在 [onSelect] **前面**，这样调用方仍可写尾随 lambda
 * ——`NavBar(pages) { open(it) }`；二级导航写 `NavBar(sections, compact = true) { … }`。
 */
class NavBar(
    pages: List<Page>,
    private val compact: Boolean = false,
    private val onSelect: (String) -> Unit
) : VBox(if (compact) 2.0 else 6.0) {

    private val group = ToggleGroup()
    private val buttons = LinkedHashMap<String, ToggleButton>()

    init {
        padding = Insets(8.0)
        prefWidth = if (compact) COMPACT_WIDTH else NORMAL_WIDTH
        // 宽度是定值：扔进什么容器都不该被拉伸（StackPane / HBox 会按 maxWidth 撑满）
        if (!compact) maxWidth = prefWidth

        pages.forEach { page ->
            // 图标只给主导航用：二级导航已经很窄，再加图标就挤了
            val icon = if (compact) null else NavIcons.byPage(page.id, ICON_SIZE, IDLE_ICON)

            val btn = ToggleButton(page.title).apply {
                toggleGroup = group
                userData = page.id
                maxWidth = Double.MAX_VALUE

                if (compact) {
                    alignment = Pos.CENTER_LEFT
                    prefHeight = COMPACT_HEIGHT
                    style = COMPACT_IDLE
                    selectedProperty().addListener { _, _, now ->
                        style = if (now) COMPACT_ON else COMPACT_IDLE
                    }
                } else {
                    if (icon != null) {
                        graphic = icon.node
                        contentDisplay = ContentDisplay.TOP
                        graphicTextGap = 3.0
                    }
                    alignment = Pos.CENTER
                    prefHeight = BUTTON_HEIGHT
                    // JavaFX 的内联样式写不了 :selected，只能自己在监听里换样式（图标同理）
                    style = NORMAL_IDLE
                    selectedProperty().addListener { _, _, now ->
                        style = if (now) NORMAL_ON else NORMAL_IDLE
                        icon?.shape?.fill = if (now) ON_ICON else IDLE_ICON
                    }
                }

                setOnAction {
                    // ToggleGroup 里的按钮再点一次会被取消选中，导航会"全都不亮"，这里拦掉
                    if (!isSelected) isSelected = true
                    onSelect(page.id)
                }
            }
            buttons[page.id] = btn
            children += btn
        }
    }

    /** 把指定标签显示为选中。只改外观，不触发跳页。 */
    fun select(id: String) {
        buttons[id]?.isSelected = true
    }

    private companion object {
        /** 主导航宽度。按钮吃满「84 − 左右各 8 的内边距」= 68。 */
        const val NORMAL_WIDTH = 84.0

        /** 二级导航宽度。 */
        const val COMPACT_WIDTH = 150.0

        /** 图标边长。 */
        const val ICON_SIZE = 20.0

        /** 主导航按钮高度。近正方形（68 × 54），四个一列刚好占窗口上三分之一。 */
        const val BUTTON_HEIGHT = 54.0

        const val COMPACT_HEIGHT = 32.0

        /** 图标填充色：跟文字同一套 —— 未选中深灰、选中主题紫。 */
        val IDLE_ICON: Color = Color.web("#52525b")
        val ON_ICON: Color = Color.web("#6d28d9")

        const val COMPACT_IDLE =
            "-fx-background-color: transparent; -fx-background-radius: 6;" +
                    "-fx-text-fill: #44444a; -fx-font-size: 13px; -fx-padding: 6 10 6 10;"

        const val COMPACT_ON =
            "-fx-background-color: #efe6fd; -fx-background-radius: 6;" +
                    "-fx-text-fill: #6d28d9; -fx-font-size: 13px;" +
                    "-fx-padding: 6 10 6 10;"

        /**
         * 主导航按钮：平时透明、深灰图标与文字；选中淡紫底 + 紫图标紫字（与二级同一套配色）。
         *
         * 选中态**不加粗** —— 加粗会改文字宽度，图标+文字这一列的位置会跟着轻微跳动，
         * 而且 11px 中文加粗后笔画糊在一起反而更难看。靠底色 + 紫色就够区分了。
         *
         * 焦点色特意抹成透明 —— 主题给聚焦控件画的底色跟「选中」几乎同色，
         * 场景一建好首个按钮就拿到焦点，看着像两个标签同时亮着。
         */
        const val NORMAL_IDLE =
            "-fx-background-color: transparent; -fx-background-radius: 10;" +
                    "-fx-text-fill: #3f3f46; -fx-font-size: 11px; -fx-padding: 6 4 6 4;" +
                    "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;" +
                    "-fx-cursor: hand;"

        const val NORMAL_ON =
            "-fx-background-color: #efe6fd; -fx-background-radius: 10;" +
                    "-fx-text-fill: #6d28d9; -fx-font-size: 11px;" +
                    "-fx-padding: 6 4 6 4;" +
                    "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;" +
                    "-fx-cursor: hand;"
    }
}
