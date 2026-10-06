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
 * 两种形态：普通（主窗口左侧，84 宽，图标在上文字在下）、[compact]（页面内部二级导航，只有文字，150 宽）。
 * 宽度都是定值，进二级页面也不收窄。
 *
 * 选中状态由 [select] 同步、不自己维护，这样用代码主动跳页时标签也会跟着亮。
 *
 * 注意参数顺序：[compact] 放在 [onSelect] 前面，调用方才能写尾随 lambda —— `NavBar(pages) { open(it) }`。
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
        // 宽度是定值：StackPane / HBox 会按 maxWidth 把它撑满
        if (!compact) maxWidth = prefWidth

        pages.filter { it.showInNav }.forEach { page ->
            // 二级导航已经很窄，再加图标就挤了
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
                    // ToggleGroup 里的按钮再点一次会被取消选中，导航会「全都不亮」，这里拦掉
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
        /** 按钮吃满「84 − 左右各 8 的内边距」= 68。 */
        const val NORMAL_WIDTH = 84.0
        const val COMPACT_WIDTH = 150.0
        const val ICON_SIZE = 20.0
        const val BUTTON_HEIGHT = 54.0
        const val COMPACT_HEIGHT = 32.0

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
         * 选中态不加粗 —— 加粗会改文字宽度，整列位置跟着跳；11px 中文加粗后笔画还会糊在一起。
         *
         * 焦点色抹成透明 —— 主题给聚焦控件画的底色跟选中几乎同色，场景一建好首个按钮就拿到焦点，
         * 看着像两个标签同时亮着。
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
