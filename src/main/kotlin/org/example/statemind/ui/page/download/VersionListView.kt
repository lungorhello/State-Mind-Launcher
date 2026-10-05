package org.example.statemind.ui.page.download

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Polygon
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.VersionGroups

/** 一行是**数据**不是控件：折叠、搜索只重算这个序列，一个节点都不碰。 */
internal sealed interface Row {

    /** [collapsed] 决定圆角收在哪：折起来时它自己就是完整一张卡。 */
    data class Header(
        val group: VersionGroups.Group,
        val count: Int,
        val collapsed: Boolean
    ) : Row

    /** [last] = 是不是所属分组的最后一行，决定卡片的下圆角落在哪一行。 */
    data class Item(val entry: VersionEntry, val last: Boolean) : Row
}

/** 用 [ListView] 而不是自己铺节点：它只渲染看得见的那十几行，917 行也不怕。 */
internal class VersionListView(private val onPick: (VersionEntry) -> Unit) : StackPane() {

    private val list = ListView<Row>()

    /** 默认只展开正式版。 */
    private val collapsed = mutableSetOf(
        VersionGroups.Group.SNAPSHOT,
        VersionGroups.Group.OLD,
        VersionGroups.Group.APRIL_FOOLS
    )

    private var query = ""
    private var all: List<VersionEntry> = emptyList()

    private val emptyHint = Label("没有匹配的版本").apply {
        style = "-fx-font-size: 13px; -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
        isVisible = false
    }

    private val failureText = Label().apply {
        style = "-fx-font-size: 13px; -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
        isWrapText = true
        maxWidth = 420.0
    }

    private val failureRetry = Button("重试").apply { style = RETRY_STYLE }

    private val failureBox = VBox(12.0, failureText, failureRetry).apply {
        alignment = Pos.CENTER
        isVisible = false
    }

    init {
        list.apply {
            // 列表本身不该有底色 —— 白在每张卡上，中间是页面的底
            style = "-fx-background-color: transparent; -fx-background-insets: 0;" +
                    "-fx-padding: 0; -fx-border-color: transparent;"
            isFocusTraversable = false
            setCellFactory { RowCell() }
        }
        children.setAll(list, emptyHint, failureBox)
    }

    // ---------- 对外 ----------

    fun setLoading() {
        all = emptyList()
        rebuild()
        failureBox.isVisible = false
        list.isVisible = true
    }

    fun setVersions(versions: List<VersionEntry>) {
        all = versions
        rebuild()
        failureBox.isVisible = false
        list.isVisible = true
    }

    fun setFailure(message: String, onRetry: () -> Unit) {
        failureText.text = message
        failureRetry.setOnAction { onRetry() }
        list.isVisible = false
        failureBox.isVisible = true
    }

    fun setQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rebuild()
    }

    // ---------- 重算行序列 ----------

    /** 搜索时忽略手动折叠，命中的组一律展开 —— 用户要看的是结果。清空搜索后折叠状态原样恢复。 */
    private fun rebuild() {
        val grouped = VersionGroups.classify(all)
        val rows = ArrayList<Row>()

        VersionGroups.ORDER.forEach { group ->
            val entries = grouped[group].orEmpty()
            val matched = if (query.isEmpty()) entries
            else entries.filter { it.id.contains(query, ignoreCase = true) }
            if (matched.isEmpty()) return@forEach

            val folded = query.isEmpty() && group in collapsed
            rows += Row.Header(group, matched.size, folded)
            if (folded) return@forEach

            matched.forEachIndexed { index, entry ->
                rows += Row.Item(entry, last = index == matched.lastIndex)
            }
        }

        list.items.setAll(rows)
        emptyHint.isVisible = rows.isEmpty() && query.isNotEmpty()
    }

    private fun toggle(group: VersionGroups.Group) {
        if (!collapsed.remove(group)) collapsed += group
        rebuild()
    }

    // ---------- 渲染 ----------

    private inner class RowCell : ListCell<Row>() {

        /**
         * 同一个 Row 必须复用同一个节点。
         *
         * ListView 在**选中项变化时会重跑 updateItem**（实测：普通 ListView 也会）。
         * 若在这里重建节点，第一次点击会变成「按下落在旧节点、抬起落在新节点」——
         * JavaFX 只在按下与抬起是同一个节点时才生成 MOUSE_CLICKED，于是那一次点击整个丢失，
         * 表现为「每次操作要点两次」。第二次点击选中项没变化，才不会重建。
         *
         * Row 是不可变数据、渲染完全由它决定，所以按值复用是安全的；
         * 折叠/搜索会生成新的 Row 值，自然走重建分支。
         */
        private var builtFor: Row? = null
        private var built: Region? = null

        init {
            // 抹掉 ListCell 自带的悬停与选中底色：卡片的白是画在 graphic 上的
            style = "-fx-background-color: transparent; -fx-padding: 0;"
        }

        override fun updateItem(item: Row?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) {
                builtFor = null
                built = null
                graphic = null
                return
            }
            if (builtFor != item) {
                builtFor = item
                built = when (item) {
                    is Row.Header -> headerNode(item)
                    is Row.Item -> itemNode(item)
                }
            }
            graphic = built
        }
    }

    private fun headerNode(header: Row.Header): Region {
        val title = Label(header.group.label).apply {
            style = "-fx-font-size: 14px; -fx-font-weight: bold;" +
                    " -fx-text-fill: ${DownloadStyles.TEXT};"
        }
        val count = Label("${header.count}").apply {
            style = "-fx-font-size: 12px; -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
        }
        return HBox(
            8.0, title, count,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            foldMark(header.collapsed)
        ).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(12.0, 16.0, 12.0, 16.0)
            cursor = Cursor.HAND
            style = if (header.collapsed) DownloadStyles.CARD else DownloadStyles.CARD_TOP
            setOnMouseClicked { toggle(header.group) }
        }
    }

    private fun itemNode(row: Row.Item): Region {
        val id = Label(row.entry.id).apply {
            style = "-fx-font-size: 13px; -fx-text-fill: ${DownloadStyles.TEXT};"
            maxWidth = Double.MAX_VALUE
        }
        val time = Label(row.entry.releaseText).apply {
            style = "-fx-font-size: 12px; -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
            maxWidth = Double.MAX_VALUE
        }
        val info = VBox(2.0, id, time).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        val divider = Region().apply {
            minHeight = 1.0
            prefHeight = 1.0
            maxHeight = 1.0
            style = "-fx-background-color: ${DownloadStyles.CARD_DIVIDER};"
        }

        val content = HBox(info).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 16.0, 10.0, 16.0)
        }

        return VBox(divider, content).apply {
            style = if (row.last) DownloadStyles.CARD_BOTTOM else DownloadStyles.CARD_MIDDLE
            cursor = Cursor.HAND
            setOnMouseClicked { onPick(row.entry) }
        }
    }

    /** 用矢量画而不是用「▾」这类字符 —— 那些字形在不同系统上可能缺字。 */
    private fun foldMark(collapsed: Boolean): Polygon {
        val points = if (collapsed) {
            doubleArrayOf(0.0, 0.0, 5.0, 4.5, 0.0, 9.0)
        } else {
            doubleArrayOf(0.0, 0.0, 9.0, 0.0, 4.5, 5.0)
        }
        return Polygon(*points).apply { fill = Color.web(DownloadStyles.TEXT_DIM) }
    }

    private companion object {

        const val RETRY_STYLE =
            "-fx-background-color: ${DownloadStyles.ACCENT}; -fx-background-radius: 8;" +
                    "-fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 18 6 18;" +
                    "-fx-cursor: hand;"
    }
}
