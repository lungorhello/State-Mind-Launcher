package org.example.statemind.ui.page.download

import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.scene.paint.Stop
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.Text
import javafx.scene.text.TextBoundsType
import org.example.statemind.core.RemoteVersion
import org.example.statemind.core.VersionEntry

/**
 * 横条吃满剩余宽度，竖条恒定 [DownloadStyles.BAR_WIDTH]；
 * 窗口变窄的压力全落在横条上，横条靠降字号消化。
 */
internal class VersionHero(private val onPick: (VersionEntry) -> Unit) : HBox(GAP) {

    private val releaseCard = HeroCard("最新正式版")
    private val snapshotCard = HeroCard("最新预览版")

    private var byId: Map<String, VersionEntry> = emptyMap()

    init {
        alignment = Pos.CENTER_LEFT

        val cards = VBox(CARD_GAP, releaseCard.root, snapshotCard.root).apply {
            minWidth = 0.0
        }
        HBox.setHgrow(cards, Priority.ALWAYS)

        val bars = HBox(BAR_GAP).apply {
            alignment = Pos.TOP_LEFT
            children.setAll(COMMON_VERSIONS.map { bar(it) })
        }

        children.setAll(cards, bars)
        setLoading()
    }

    // ---------- 数据 ----------

    fun setLoading() {
        releaseCard.loading()
        snapshotCard.loading()
    }

    fun setFailure() {
        releaseCard.fail()
        snapshotCard.fail()
    }

    fun setResult(result: RemoteVersion.Result) {
        byId = result.versions.associateBy { it.id }
        releaseCard.current = result.latestRelease
        snapshotCard.current = result.latestSnapshot
    }

    // ---------- 竖条 ----------

    private fun bar(item: CommonVersion): Region {
        val caption = verticalText(CAPTION, CAPTION_SIZE, Color.web(DownloadStyles.TEXT), CAPTION_SPACING)

        val label = Text(item.id).apply {
            font = Font.font(Font.getDefault().family, FontWeight.BOLD, ID_SIZE)
            fill = gradientOf(item.colors)
            // 逆时针 = 版本号从条底往上读
            rotate = -90.0
            // 按墨迹包围盒布局：黑字是 14px 多行、版本号是 22px 单行转过 90°，
            // 字号不同则「em 框到墨迹」的空隙不同，用 em 框对齐会让两个顶端差 2px。
            boundsType = TextBoundsType.VISUAL
            isMouseTransparent = true
        }

        // rotate 不改 layoutBounds（按墨迹算也一样），槽位得自己贴合：转过 90° 后
        // 文字的墨迹高度 = 未转时的墨迹宽度、墨迹宽度 = 未转时的墨迹高度，
        // 所以两轴都绑上去，视觉墨迹才正好落在槽位内、顶端与黑字齐平。
        val length = Bindings.createDoubleBinding({ label.layoutBounds.width }, label.layoutBoundsProperty())
        val thickness = Bindings.createDoubleBinding({ label.layoutBounds.height }, label.layoutBoundsProperty())
        val slot = StackPane(label).apply {
            minWidthProperty().bind(thickness)
            prefWidthProperty().bind(thickness)
            maxWidthProperty().bind(thickness)
            minHeightProperty().bind(length)
            prefHeightProperty().bind(length)
            maxHeightProperty().bind(length)
            isMouseTransparent = true
        }

        // 黑字归左上角、版本号归右上角 —— 各自定位，中间的空白不用谁来分配。
        val content = StackPane(caption, slot).apply {
            StackPane.setAlignment(caption, Pos.TOP_LEFT)
            StackPane.setAlignment(slot, Pos.TOP_RIGHT)
            isMouseTransparent = true
        }

        return StackPane(content).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(BAR_TOP_INSET, BAR_RIGHT_INSET, 0.0, BAR_LEFT_INSET)
            minWidth = DownloadStyles.BAR_WIDTH
            prefWidth = DownloadStyles.BAR_WIDTH
            maxWidth = DownloadStyles.BAR_WIDTH
            // 高度交给外侧 HBox：它会把这个节点拉到与左边两张卡等高
            maxHeight = Double.MAX_VALUE
            style = BAR_STYLE
            cursor = Cursor.HAND
            setOnMouseClicked { byId[item.id]?.let(onPick) }
        }
    }

    /** 用 [Text] 而不是 Label —— 只有它能设渐变填充。 */
    private fun verticalText(value: String, size: Double, paint: Paint, spacing: Double): Text =
        Text(value.toCharArray().joinToString("\n")).apply {
            font = Font.font(Font.getDefault().family, FontWeight.BOLD, size)
            fill = paint
            lineSpacing = spacing
            boundsType = TextBoundsType.VISUAL
            isMouseTransparent = true
        }

    private fun gradientOf(colors: List<String>): Paint {
        if (colors.size == 1) return Color.web(colors[0])
        val stops = colors.mapIndexed { index, hex ->
            Stop(index.toDouble() / (colors.size - 1), Color.web(hex))
        }
        // 沿文字自身的方向取色：转过 −90° 之后在屏幕上就是从下往上，颜色才一个字一个字地数过去。
        return LinearGradient(0.0, 0.0, 1.0, 0.0, true, CycleMethod.NO_CYCLE, *stops.toTypedArray())
    }

    // ---------- 横条卡 ----------

    private inner class HeroCard(private val caption: String) {

        var current: VersionEntry? = null
            set(value) {
                field = value
                version.text = value?.id ?: "—"
                if (value != null) note.text = "$caption · 发布于 ${value.releaseText}"
            }

        // 不加 private：外层 VersionHero 要写这两个 Label，而 Kotlin 外部类读不到内部类的私有成员
        val version = Label("—")
        val note = Label()

        private var fontSize = 0.0

        val root: VBox = VBox(2.0, version, note).apply {
            padding = Insets(9.0, 16.0, 9.0, 16.0)
            minWidth = 0.0
            cursor = Cursor.HAND
            style = cardStyle(DownloadStyles.CARD_BG)
            setOnMouseEntered { style = cardStyle(HOVER_BG) }
            setOnMouseExited { style = cardStyle(DownloadStyles.CARD_BG) }
            setOnMouseClicked { current?.let(onPick) }
            version.maxWidth = Double.MAX_VALUE

            note.style = "-fx-font-size: 12px; -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
            note.maxWidth = Double.MAX_VALUE

            widthProperty().addListener { _, _, w -> applyFont(w.toDouble()) }
        }

        init {
            applyFont(0.0)
        }

        fun loading() {
            current = null
            note.text = "获取中…"
        }

        fun fail() {
            current = null
            note.text = "获取失败"
        }

        private fun applyFont(cardWidth: Double) {
            val size = when {
                cardWidth >= 340.0 -> 26.0
                cardWidth >= 240.0 -> 21.0
                else -> 17.0
            }
            if (fontSize == size) return
            fontSize = size
            version.style = "-fx-font-size: ${size}px; -fx-font-weight: bold;" +
                    " -fx-text-fill: ${DownloadStyles.TEXT};"
        }
    }

    private companion object {

        const val GAP = 12.0
        const val CARD_GAP = 8.0
        const val BAR_GAP = 8.0

        const val CAPTION = "常见版本"
        const val CAPTION_SIZE = 12.0
        const val CAPTION_SPACING = 2.0
        const val ID_SIZE = 26.0

        const val BAR_TOP_INSET = 12.0
        const val BAR_LEFT_INSET = 10.0
        const val BAR_RIGHT_INSET = 14.0

        const val BAR_STYLE =
            "-fx-background-color: ${DownloadStyles.CARD_BG};" +
                    " -fx-border-color: ${DownloadStyles.ACCENT_LINE}; -fx-border-width: 1;" +
                    " -fx-border-radius: ${DownloadStyles.CARD_RADIUS};" +
                    " -fx-background-radius: ${DownloadStyles.CARD_RADIUS};"

        const val HOVER_BG = "#faf9ff"

        fun cardStyle(background: String): String =
            "-fx-background-color: $background;" +
                    " -fx-border-color: ${DownloadStyles.CARD_LINE}; -fx-border-width: 1;" +
                    " -fx-border-radius: ${DownloadStyles.CARD_RADIUS};" +
                    " -fx-background-radius: ${DownloadStyles.CARD_RADIUS};" +
                    " -fx-cursor: hand;"
    }
}
