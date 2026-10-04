package org.example.statemind.ui

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.value.ObservableValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop
import java.util.concurrent.Callable

/**
 * 启动器内置弹窗。刻意**不用**系统的 `Alert` / `TextInputDialog`：那些是操作系统画的方框，
 * 样式改不动、和启动器主题两张皮。
 *
 * 三种语气（[Kind]）结构完全一样，卡面统一素白，只靠顶端 4px 色带 + 标题字色区分。
 *
 * **回调式**，不用 `showAndWait()`（JavaFX 里嵌套事件循环不安全）：
 * `Dialogs.confirm("删除账号", "确定要删除「fox」吗？", kind = Dialogs.Kind.WARN) { doRemove() }`
 *
 * 使用前必须由 App.kt 调一次 [install] 登记遮罩层，否则弹窗无处可画（控制台会报一行）。
 */
object Dialogs {

    enum class Kind { INFO, WARN, ERROR }

    /** 全窗口遮罩层，由 App.kt 注入。 */
    private var layer: StackPane? = null

    /** App.kt 把它铺在根布局最上层；建完场景前调一次即可。 */
    fun install(overlay: StackPane) {
        overlay.style = "-fx-background-color: rgba(20, 16, 32, 0.38);"
        overlay.isVisible = false
        layer = overlay
    }

    fun info(title: String, body: String) = notify(Kind.INFO, title, body)

    fun warn(title: String, body: String) = notify(Kind.WARN, title, body)

    fun error(title: String, body: String) = notify(Kind.ERROR, title, body)

    /**
     * 确认框，点确定才走 [onConfirm]。
     *
     * [extra] 挂附加控件（如「不再提醒」勾选框），在 [onConfirm] 里自己读状态。
     * [confirmDisabledWhen] 传布尔绑定把「确定」按住：表单没填对就禁用，绝不「关掉弹窗再弹提示」丢输入。
     */
    fun confirm(
        title: String,
        body: String,
        kind: Kind = Kind.WARN,
        confirmText: String = "确定",
        cancelText: String? = "取消",
        extra: Node? = null,
        contentHeight: Double = CONTENT_HEIGHT,
        confirmDisabledWhen: ObservableValue<Boolean>? = null,
        onConfirm: () -> Unit
    ) {
        present(
            kind, title, body, extra, confirmText, cancelText,
            confirmDisabledWhen, contentHeight, onConfirm
        )
    }

    /** 单行文本输入。文本全空时「确定」禁用。 */
    fun prompt(
        title: String,
        body: String,
        placeholder: String = "",
        confirmText: String = "确定",
        onConfirm: (String) -> Unit
    ) {
        val field = TextField().apply {
            promptText = placeholder
            maxWidth = CONTENT_WIDTH
            style = FIELD_STYLE
        }
        present(
            Kind.INFO, title, body, field, confirmText, "取消",
            Bindings.createBooleanBinding(Callable { field.text.isBlank() }, field.textProperty())
        ) { onConfirm(field.text.trim()) }
    }

    private fun notify(kind: Kind, title: String, body: String) =
        present(kind, title, body, null, "知道了", null, null) {}

    private fun present(
        kind: Kind,
        title: String,
        body: String,
        content: Node?,
        confirmText: String,
        cancelText: String?,
        confirmDisabledWhen: ObservableValue<Boolean>?,
        contentHeight: Double = CONTENT_HEIGHT,
        onConfirm: () -> Unit
    ) {
        val host = layer
        if (host == null) {
            // App.kt 里应调用 Dialogs.install(...)，否则没地方画
            System.err.println("[Dialogs] 遮罩层未注册，弹窗无法显示：「$title」")
            return
        }

        val tone = toneOf(kind)

        // 固定高度：正文长短不影响卡片高度，按钮永远停在同一位置。
        // 挂多行表单（第三方登录那几个输入框）时由调用方传大一点的值。
        val area = VBox(12.0).apply {
            prefHeight = contentHeight
            minHeight = contentHeight
            maxHeight = contentHeight
        }

        if (title.isNotBlank()) {
            area.children += Label(title).apply {
                isWrapText = true
                maxWidth = CONTENT_WIDTH
                style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: ${tone.title};"
            }
        }
        bodyNode(body)?.let { node ->
            area.children += node
            // 只有滚动容器吃剩余高度：普通 Label 被 VBox 拉满后文字会垂直居中。
            if (node is ScrollPane) VBox.setVgrow(node, Priority.ALWAYS)
        }
        content?.let { area.children += it }

        val buttons = HBox(10.0).apply { alignment = Pos.CENTER_RIGHT }
        cancelText?.let { text ->
            buttons.children += Button(text).apply {
                style = BTN_CANCEL
                setOnAction { close() }
            }
        }
        val ok = Button(confirmText).apply {
            style = BTN_BASE +
                    " -fx-background-color: ${tone.accent}; -fx-text-fill: white; -fx-font-weight: bold;"
            setOnAction {
                close()
                onConfirm()
            }
        }
        confirmDisabledWhen?.let { ok.disableProperty().bind(it) }
        buttons.children += ok

        // padding 放在内层，好让顶部色带贴到卡片最上沿。
        val inner = VBox(18.0).apply {
            padding = Insets(20.0, 22.0, 20.0, 22.0)
        }
        inner.children.setAll(area, buttons)

        val card = VBox().apply {
            prefWidth = CARD_WIDTH
            maxWidth = CARD_WIDTH
            // maxHeight 必须显式限制，否则外层 StackPane 会把它拉伸填满整窗。
            maxHeight = Region.USE_PREF_SIZE
            // 不画描边：描边会把顶部色带往下挤、上方留一道浅缝；边界靠遮罩对比 + 投影表现。
            style = "-fx-effect: dropshadow(gaussian, rgba(24, 20, 32, 0.22), 26, 0.16, 0, 9);"
        }
        card.children.setAll(inner)
        paintCard(card, tone.accent)

        host.children.setAll(card)
        host.isVisible = true
        // 让输入框（或确定按钮）先拿到焦点，键盘操作不用点一下
        Platform.runLater { (content ?: ok).requestFocus() }
    }

    private fun close() {
        val host = layer ?: return
        host.children.clear()
        host.isVisible = false
    }

    /**
     * 给卡面铺「素白 + 顶端 [TOP_BAR_HEIGHT] px 语气色带」。
     *
     * **色带必须画进卡自己的背景里**：另起一个 4px 节点叠上去时，JavaFX 会把它的圆角按边长
     * 比例压成扁椭圆（12×4），弧线在第 4 行就顶到卡的右沿，而白卡的 12×12 圆弧那里还内缩着 ——
     * 色带右上角会「鼓」出白卡轮廓约 6.6px（实测）。单层背景只有一个形状，天然被卡自己的弧裁掉。
     *
     * 停靠点用**比例**而不是 px：JavaFX 的 CSS 渐变不认 px 停靠点（写了会被忽略、整张卡变成渐变）。
     */
    private fun paintCard(card: Region, accent: String) {
        val band = Color.web(accent)
        fun repaint(h: Double) {
            val f = if (h <= 0.0) 0.0 else (TOP_BAR_HEIGHT / h).coerceIn(0.0, 1.0)
            card.background = Background(
                BackgroundFill(
                    LinearGradient(
                        0.0, 0.0, 0.0, 1.0, true, CycleMethod.NO_CYCLE,
                        Stop(0.0, band), Stop(f, band),
                        Stop(f, Color.WHITE), Stop(1.0, Color.WHITE)
                    ),
                    CornerRadii(12.0), Insets.EMPTY
                )
            )
        }
        repaint(card.height)
        card.heightProperty().addListener { _, _, h -> repaint(h.toDouble()) }
    }

    /** 正文文本；超过 [SCROLL_THRESHOLD] 个字符套一层滚动，不撑高卡片、不顶按钮。 */
    private fun bodyNode(text: String): Node? {
        if (text.isBlank()) return null
        val label = Label(text).apply {
            isWrapText = true
            maxWidth = CONTENT_WIDTH
            style = BODY_STYLE
        }
        if (text.length <= SCROLL_THRESHOLD) return label
        return ScrollPane(label).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = "-fx-background-color: transparent; -fx-background: transparent;" +
                    "-fx-border-color: transparent; -fx-padding: 0;"
        }
    }

    /** 一种语气的两档颜色：标题字、顶部色带与主按钮。 */
    private data class Tone(val title: String, val accent: String)

    private fun toneOf(kind: Kind): Tone = when (kind) {
        Kind.INFO -> Tone("#4c1d95", "#7c3aed")
        Kind.WARN -> Tone("#7a4b06", "#d97706")
        Kind.ERROR -> Tone("#8f1d1d", "#dc2626")
    }

    private const val CARD_WIDTH = 420.0
    private const val CONTENT_WIDTH = 376.0

    /** 标题 + 正文 + 附加控件那块的高度，写死让按钮永远停在同一个位置。 */
    private const val CONTENT_HEIGHT = 132.0

    private const val TOP_BAR_HEIGHT = 4.0

    /** 超过这么多字符就改成滚动：内容区减掉标题大约只放得下 4 行（中文每行约 28 字）。 */
    private const val SCROLL_THRESHOLD = 100

    private const val BODY_STYLE = "-fx-font-size: 13px; -fx-text-fill: #3f3f46;"

    private const val BTN_BASE =
        "-fx-background-radius: 8; -fx-font-size: 13px; -fx-padding: 8 18 8 18; -fx-cursor: hand;"

    private const val BTN_CANCEL = BTN_BASE +
            " -fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
            "-fx-border-radius: 8; -fx-text-fill: #3f3f46;"

    private const val FIELD_STYLE =
        "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: #cdb6f2;" +
                "-fx-background-color: #ffffff; -fx-border-width: 1; -fx-font-size: 13px;" +
                "-fx-padding: 8 10 8 10;"
}
