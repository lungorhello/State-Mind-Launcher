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
 * 启动器内置弹窗。
 *
 * 刻意**不用**系统的 `Alert` / `TextInputDialog`：那些是操作系统画的方框，样式改不动、
 * 和启动器主题两张皮。这里改成画在启动器窗口里的一层遮罩 + 一张卡片，
 * 版式固定、颜色归我们管，以后统一美化只改这一个文件。
 *
 * 三种语气结构完全一样，卡面统一素白，只靠**顶端一条 4px 色带 + 标题字色**区分：
 *  - [Kind.INFO]  紫　 —— 普通提示（启动器主色）
 *  - [Kind.WARN]  琥珀 —— 提醒，需要用户确认
 *  - [Kind.ERROR] 红　 —— 出错、操作被拒绝
 *
 * 色带是**画进卡自己背景的一层渐变**（见 [paintCard]），不是另起一个色带节点 —— 后者会让色带的
 * 圆角被压成扁椭圆、比白卡的圆弧更靠外，右上角「鼓」出一块。
 *
 * **尺寸恒定**：卡片高度由写死的 [CONTENT_HEIGHT] 决定 —— 既不随窗口缩放变化、也不随正文
 * 长短变化，按钮永远停在同一个位置。另外 `maxHeight` 必须显式钉死：外层 StackPane 会把
 * 子节点拉伸填满整窗，不钉就会「顶天立地」。
 *
 * **回调式**，替代 `showAndWait()`（JavaFX 里嵌套事件循环不安全，也是之前「…」截断那套的根源）：
 * ```
 * Dialogs.confirm("删除账号", "确定要删除「fox」吗？", kind = Dialogs.Kind.WARN) { doRemove() }
 * ```
 *
 * 注意：必须先由 App.kt 调一次 [install] 把遮罩层登记进来，否则弹窗无处可画（会在控制台报一行提示）。
 */
object Dialogs {

    /** 弹窗语气，决定配色。 */
    enum class Kind { INFO, WARN, ERROR }

    /** 全窗口的遮罩层。由 [install] 从 App.kt 注入。 */
    private var layer: StackPane? = null

    // ---------- 安装 ----------

    /** 登记遮罩层。App.kt 把它铺在根布局最上层，建完场景前调一次即可。 */
    fun install(overlay: StackPane) {
        overlay.style = "-fx-background-color: rgba(20, 16, 32, 0.38);"
        overlay.isVisible = false
        layer = overlay
    }

    // ---------- 对外接口 ----------

    /** 普通提示（淡紫），只有一个「知道了」。 */
    fun info(title: String, body: String) = notify(Kind.INFO, title, body)

    /** 提醒（淡黄），只有一个「知道了」。 */
    fun warn(title: String, body: String) = notify(Kind.WARN, title, body)

    /** 出错（淡红），只有一个「知道了」。 */
    fun error(title: String, body: String) = notify(Kind.ERROR, title, body)

    /**
     * 确认框。用户点确定才走 [onConfirm]；点取消或直接关掉什么都不发生。
     * [extra] 可以挂一个附加控件（比如「不再提醒」勾选框），在 [onConfirm] 里自己读它的状态。
     * [confirmDisabledWhen] 传一个布尔绑定就能把「确定」按钮按住 —— 表单没填对时禁用，
     * 免得用户点下去才发现错（而不是先关掉弹窗、再另弹一个提示框、填的东西全丢）。
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

    /** 单行文本输入（淡紫，和启动器同色）。名字全空时「确定」按钮是禁用的。 */
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

    // ---------- 内部 ----------

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
            // 没有遮罩层就没地方画，报一行便于排查（App.kt 里应调用 Dialogs.install(...)）
            System.err.println("[Dialogs] 遮罩层未注册，弹窗无法显示：「$title」")
            return
        }

        val tone = toneOf(kind)

        // 内容区高度由调用方给定（默认 [CONTENT_HEIGHT]）。正文长短不再改变卡片高度，
        // 下方按钮因此永远停在同一个位置；正文长了就在这块固定区域里自己滚，
        // 短了也不会把按钮往上提 —— 组件上下浮动是最伤观感的。
        // 挂多行表单（比如第三方登录的三个输入框）时传个大一点的值。
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
            // 只有滚动容器去吃剩余高度；普通 Label 保持自然高度贴顶排，
            // 否则 VBox 会把它拉满、文字变成垂直居中。
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
            // 卡片高度完全由内部写死的 CONTENT_HEIGHT 决定 —— 与窗口尺寸无关、与正文长短无关。
            // maxHeight 必须显式限制：外层是 StackPane，不限制就会被拉伸填满整窗（「顶天立地」的根因）。
            maxHeight = Region.USE_PREF_SIZE
            // 卡面**不画描边**：描边会把顶部的色带往下挤 1px、并在色带上方留出一道浅色缝。
            // 现在靠遮罩对比 + 投影表现边界，四角弧度与色带完全对齐。
            // 卡面颜色（素白 + 顶端色带）不写在 CSS 里，见 [paintCard]。
            style = "-fx-effect: dropshadow(gaussian, rgba(24, 20, 32, 0.22), 26, 0.16, 0, 9);"
        }
        card.children.setAll(inner)
        paintCard(card, tone.accent)

        host.children.setAll(card)
        host.isVisible = true
        // 让输入框（或确定按钮）拿到焦点，键盘操作不用先点一下
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
     * **色带必须画进卡自己的背景里**，不能另起一个 4px 高的节点叠上去 —— 后者是之前的写法，
     * 也正是「色带右上角鼓出来一块、跟白卡没贴合」的根因：
     * JavaFX 画圆角时会按边长把半径按比例缩掉，4px 高的色带配 radius 12，竖向被压成 12×4 的
     * **扁椭圆**，它的弧线到第 4 行就顶到卡的右沿，而白卡自己的 12×12 圆弧那里还内缩着 3.5px ——
     * 于是紫色探出白卡轮廓最多约 6.6px（实测值，见 `ui_sandbox` 里的几何探针）。
     * 单层背景只有一个形状、一套圆角路径，色带的右上角天然被卡自己的圆弧裁掉，严丝合缝。
     *
     * 停靠点用**比例**而不是 px：JavaFX 的 CSS 渐变不认 px 停靠点（写了会被忽略、整张卡变成渐变），
     * 所以等布局给出真实卡高之后按 `4px / 卡高` 换算，并跟着高度变化重算。
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

    /**
     * 正文文本。超过 [SCROLL_THRESHOLD] 个字符就套一层滚动 —— 内容区高度是写死的，
     * 长文本（游戏崩溃日志之类）在这一块里自己滚，不去撑高卡片、也不去顶按钮。
     */
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

    // ---------- 配色 ----------

    /** 一种语气的两档颜色：标题字 / 顶部色带与主按钮。 */
    private data class Tone(val title: String, val accent: String)

    private fun toneOf(kind: Kind): Tone = when (kind) {
        Kind.INFO -> Tone("#4c1d95", "#7c3aed")
        Kind.WARN -> Tone("#7a4b06", "#d97706")
        Kind.ERROR -> Tone("#8f1d1d", "#dc2626")
    }

    private const val CARD_WIDTH = 420.0
    private const val CONTENT_WIDTH = 376.0

    /**
     * 标题 + 正文 + 附加控件那一块的**固定**高度。
     * 写死是为了让按钮永远停在同一个位置：弹窗高度不随正文长短、也不随窗口大小变化。
     * 正文超出就自己滚（见 [SCROLL_THRESHOLD]），不会把按钮顶下去。
     */
    private const val CONTENT_HEIGHT = 132.0

    /** 卡片顶端那条语气色带的高度。 */
    private const val TOP_BAR_HEIGHT = 4.0

    /**
     * 正文字符数超过这个值就改成滚动。
     * 内容区固定 [CONTENT_HEIGHT] 高、减去标题占的一行，正文大约只剩 4 行（中文每行约 28 字），
     * 所以阈值取 100 比较安全 —— 再长就该滚了，否则会被压到按钮上。
     */
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
