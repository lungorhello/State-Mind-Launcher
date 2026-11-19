package org.example.statemind.ui.page.download

import javafx.geometry.Pos
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.stage.Window
import org.example.statemind.core.mod.ModKind

/**
 * 下载页顶部的类型选择 + 搜索框。
 *
 * [onSelect] 的 null 表示「原版游戏」；其余按 [ModKind] 传下去。
 * 每个 tab 都有自己的搜索词：切回原版时不能把「搜索模组」的字当成版本号去过滤。
 */
internal class TopBar(
    private val onSelect: (ModKind?) -> Unit,
    private val onSearch: (ModKind?, String) -> Unit
) : HBox(SEARCH_GAP) {

    private val group = ToggleGroup()
    private val buttons = LinkedHashMap<ModKind?, ToggleButton>()

    /** 离焦要靠场景级事件，搜索框得留个引用。 */
    private val search = TextField()

    private var focusWired = false

    /** 当前 tab；[buttons] 的 key 为 null 就是原版。 */
    private var selected: ModKind? = null

    /** 每个 tab 各自记搜索词，切回来时文本框恢复原样。 */
    private val queries = HashMap<ModKind?, String>()

    init {
        alignment = Pos.CENTER_LEFT

        val options = HBox(OPTION_GAP)
        ITEMS.forEach { item ->
            val button = ToggleButton(item.label).apply {
                toggleGroup = group
                isSelected = item.kind == null
                // 不让 HBox 把文字压成省略号
                minWidth = Region.USE_PREF_SIZE
                style = if (item.kind == null) BTN_ON else BTN_OFF
                selectedProperty().addListener { _, _, now -> style = if (now) BTN_ON else BTN_OFF }
                // ToggleGroup 再点一次会取消选中，而 tab 不该有「全都不亮」的状态
                setOnAction {
                    if (!isSelected) isSelected = true
                    choose(item.kind)
                }
            }
            buttons[item.kind] = button
            options.children += button
        }

        search.apply {
            promptText = PROMPT_OF[null]
            style = DownloadStyles.FIELD
            prefWidth = SEARCH_WIDTH
            minWidth = SEARCH_MIN
            // 吃满选项按钮右边到页面右边那一整块：中间再塞个撑开的空位，就会留下一段死白
            maxWidth = Double.MAX_VALUE
            textProperty().addListener { _, _, now ->
                queries[selected] = now
                onSearch(selected, now)
            }
        }
        HBox.setHgrow(search, Priority.ALWAYS)

        children.setAll(options, search)
        wireFocus()
    }

    private fun choose(kind: ModKind?) {
        if (kind == selected) return
        selected = kind
        search.promptText = PROMPT_OF[kind]
        // 先切视图再填词：视图切过去时会把查询清空，词必须在这之后重新下发一次
        onSelect(kind)
        val text = queries[kind].orEmpty()
        if (search.text != text) search.text = text
        onSearch(kind, text)
    }

    /**
     * 搜索框只在自己被点中时留着光标：点页面别处、切走窗口、按 Esc 都放掉。
     * 点别处必须走场景级过滤器 —— 挂在按钮上的话，按钮不抢焦点就漏掉了。
     */
    private fun wireFocus() {
        sceneProperty().addListener { _, _, scene ->
            if (scene == null || focusWired) return@addListener
            focusWired = true
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED) { e ->
                if (!search.isFocused) return@addEventFilter
                // 按在搜索框自己身上（含它内部那个文本节点）就留着
                val box = search.localToScene(search.boundsInLocal)
                if (box.contains(e.sceneX, e.sceneY)) return@addEventFilter
                requestFocus()
            }
            if (scene.window != null) releaseOnBlur(scene.window)
            else scene.windowProperty().addListener { _, _, window -> releaseOnBlur(window) }
        }
        search.setOnKeyPressed { e -> if (e.code == KeyCode.ESCAPE) requestFocus() }
    }

    private fun releaseOnBlur(window: Window?) {
        window ?: return
        window.focusedProperty().addListener { _, _, focused ->
            if (!focused && search.isFocused) requestFocus()
        }
    }

    private class Item(val label: String, val kind: ModKind?)

    private companion object {

        val ITEMS = listOf(
            Item("原版游戏", null),
            Item("模组", ModKind.MOD),
            Item("资源包", ModKind.RESOURCE_PACK),
            Item("整合包", ModKind.MODPACK),
            Item("数据包", ModKind.DATAPACK),
            Item("光影包", ModKind.SHADER)
        )

        val PROMPT_OF: Map<ModKind?, String> = mapOf(
            null to "搜索版本",
            ModKind.MOD to "搜索模组",
            ModKind.RESOURCE_PACK to "搜索资源包",
            ModKind.MODPACK to "搜索整合包",
            ModKind.DATAPACK to "搜索数据包",
            ModKind.SHADER to "搜索光影包"
        )

        const val FONT_SIZE = 13.0
        const val BUTTON_PAD_H = 12.0
        const val OPTION_GAP = 6.0
        const val SEARCH_GAP = 10.0
        const val SEARCH_WIDTH = 240.0
        const val SEARCH_MIN = 100.0

        const val BTN_BASE =
            "-fx-background-radius: 8; -fx-font-size: ${FONT_SIZE}px;" +
                    "-fx-padding: 6 $BUTTON_PAD_H 6 $BUTTON_PAD_H;" +
                    "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;" +
                    "-fx-cursor: hand;"

        const val BTN_ON = BTN_BASE +
                " -fx-background-color: ${DownloadStyles.ACCENT_BG};" +
                " -fx-text-fill: ${DownloadStyles.ACCENT_TEXT};"

        const val BTN_OFF = BTN_BASE +
                " -fx-background-color: transparent;" +
                " -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
    }
}
