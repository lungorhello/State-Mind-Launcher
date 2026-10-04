package org.example.statemind.ui.page.download

import javafx.geometry.Pos
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region

/** 搜索框只过滤下方分组（见 [VersionListView]），横条与竖条不跟着变。 */
internal class TopBar(private val onSearch: (String) -> Unit) : HBox(SEARCH_GAP) {

    private val group = ToggleGroup()

    init {
        alignment = Pos.CENTER_LEFT

        val options = HBox(OPTION_GAP)
        ITEMS.forEach { item ->
            options.children += ToggleButton(item.full).apply {
                toggleGroup = group
                isSelected = item.usable
                isDisable = !item.usable
                // 不让 HBox 把文字压成省略号
                minWidth = Region.USE_PREF_SIZE
                style = if (item.usable) BTN_ON else BTN_DISABLED
                if (item.usable) {
                    // ToggleGroup 再点一次会取消选中，而导航项不该有「全都不亮」的状态
                    setOnAction { if (!isSelected) isSelected = true }
                }
            }
        }

        val search = TextField().apply {
            promptText = "搜索版本"
            style = DownloadStyles.FIELD
            prefWidth = SEARCH_WIDTH
            maxWidth = SEARCH_WIDTH
            minWidth = SEARCH_MIN
            textProperty().addListener { _, _, now -> onSearch(now) }
        }

        children.setAll(options, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, search)
    }

    private class Item(val full: String, val usable: Boolean)

    private companion object {

        val ITEMS = listOf(
            Item("原版游戏", usable = true),
            Item("模组", usable = false),
            Item("资源包", usable = false),
            Item("整合包", usable = false),
            Item("数据包", usable = false),
            Item("光影包", usable = false)
        )

        const val FONT_SIZE = 13.0
        const val BUTTON_PAD_H = 12.0
        const val OPTION_GAP = 6.0
        const val SEARCH_GAP = 10.0
        const val SEARCH_WIDTH = 240.0
        const val SEARCH_MIN = 100.0

        /** 选项按钮共有的一段。 */
        const val BTN_BASE =
            "-fx-background-radius: 8; -fx-font-size: ${FONT_SIZE}px;" +
                    "-fx-padding: 6 $BUTTON_PAD_H 6 $BUTTON_PAD_H;" +
                    "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;" +
                    "-fx-cursor: hand;"

        const val BTN_ON = BTN_BASE +
                " -fx-background-color: ${DownloadStyles.ACCENT_BG};" +
                " -fx-text-fill: ${DownloadStyles.ACCENT_TEXT};"

        /** 禁用态默认叠一层半透明，会淡到看不清字面；按回不透明，只靠文字色表达。 */
        const val BTN_DISABLED =
            "-fx-background-radius: 8; -fx-font-size: ${FONT_SIZE}px;" +
                    "-fx-padding: 6 $BUTTON_PAD_H 6 $BUTTON_PAD_H;" +
                    "-fx-background-color: transparent;" +
                    "-fx-text-fill: ${DownloadStyles.TEXT_FAINT};" +
                    "-fx-opacity: 1;"
    }
}
