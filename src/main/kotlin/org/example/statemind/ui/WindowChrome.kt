package org.example.statemind.ui

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBase
import javafx.scene.control.Tooltip
import javafx.scene.effect.BlurType
import javafx.scene.effect.DropShadow
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle
import javafx.scene.shape.StrokeLineCap
import javafx.stage.Stage

/**
 * 自绘窗口：系统标题栏整个不要了，换成我们自己的那条，并把「拖动」「拖边缩放」「圆角」补回来
 * （`StageStyle.TRANSPARENT` 之后这几件事系统不再代劳）。
 *
 * 圆角是**画**出来的：窗口建成透明的，内容包一层白底圆角矩形、再套一圈投影，
 * 圆角以外的像素直接透出去 —— 观感就是 Win11 那种圆角矩形窗口。
 *
 * 标题栏只留最小化与关闭 —— 启动器是个固定用途的小窗口，最大化没有意义。
 */
object WindowChrome {

    const val TITLE_BAR_HEIGHT = 42.0

    /** 窗口外圈留给投影的空隙。内容区尺寸不受影响，只是整窗大了一圈。 */
    const val SHADOW_MARGIN = 20.0

    /** 圆角半径、投影，与 Win11 常规窗口对齐。 */
    private const val CORNER = 8.0
    private val SHADOW = DropShadow(BlurType.THREE_PASS_BOX, Color.rgb(0, 0, 0, 0.20), 14.0, 0.0, 0.0, 4.0)

    /** 按下的位置落在可见边缘多少像素内算拖边（外面那圈投影也一起吃进来，好抓）。 */
    private const val EDGE = 6.0

    private const val SHELL_STYLE =
        "-fx-background-color: #ffffff; -fx-border-color: #d9d9de; -fx-border-width: 1;" +
                "-fx-border-radius: 8; -fx-background-radius: 8;"

    /**
     * 标题栏 + 下载任务按钮的状态。按钮「开着」时一直亮着强调色底，
     * 所以「按一下进去、再按一下回来」在窗口上看得出当前在不在任务页。
     */
    class TitleBar internal constructor(val root: HBox, private val setActive: (Boolean) -> Unit) {

        var tasksActive: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                setActive(value)
            }
    }

    /**
     * 把内容（[shell]）包成圆角外壳，返回**场景根节点**。
     *
     * [shell] 会被裁成圆角矩形 —— 不裁的话标题栏那层白底、弹窗的半透明遮罩会盖到圆角外面，
     * 四角看着还是方的；投影挂在再外面一层，这样它跟着圆角外形走，又不会被裁剪切掉。
     */
    fun roundedShell(shell: StackPane): StackPane {
        shell.style = SHELL_STYLE
        shell.clip = Rectangle().apply {
            arcWidth = CORNER * 2
            arcHeight = CORNER * 2
            widthProperty().bind(shell.widthProperty())
            heightProperty().bind(shell.heightProperty())
        }
        return StackPane(StackPane(shell).apply { effect = SHADOW }).apply {
            padding = Insets(SHADOW_MARGIN)
        }
    }

    /**
     * 标题栏。左边是 logo，右边依次是下载任务、最小化、关闭。
     *
     * [onToggleTasks] 点一下进任务页、再点一下回原来那页，开合状态由调用方通过
     * [TitleBar.tasksActive] 回填 —— 本对象不自己记，页面切换只有一处真相。
     */
    fun titleBar(stage: Stage, onToggleTasks: () -> Unit): TitleBar {
        val icon = NavIcons.byPage("download", 17.0, IDLE_GLYPH)
        val box = icon?.node ?: StackPane()

        var hovered = false
        var active = false
        val tasks = Button("", box)

        fun paint() {
            tasks.style = buttonStyle(hovered || active, if (active) ACTIVE_BG else HOVER_BG)
            icon?.shape?.fill = if (hovered || active) ACCENT_TEXT else IDLE_GLYPH
        }

        tasks.apply {
            tooltip = Tooltip("下载任务")
            prefWidth = BUTTON_WIDTH
            prefHeight = BUTTON_HEIGHT
            minWidth = BUTTON_WIDTH
            minHeight = BUTTON_HEIGHT
            setOnAction { onToggleTasks() }
            setOnMouseEntered { hovered = true; paint() }
            setOnMouseExited { hovered = false; paint() }
        }
        paint()

        val bar = HBox(
            4.0,
            logoView(),
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            tasks,
            glyphButton(listOf(line(0.0, 0.0, 11.0, 0.0)), "最小化", HOVER_BG, IDLE_GLYPH, IDLE_GLYPH) {
                stage.isIconified = true
            },
            glyphButton(
                listOf(line(0.0, 0.0, 11.0, 11.0), line(11.0, 0.0, 0.0, 11.0)),
                "关闭", CLOSE_BG, IDLE_GLYPH, Color.WHITE
            ) {
                stage.close()
            }
        ).apply {
            alignment = Pos.CENTER_LEFT
            prefHeight = TITLE_BAR_HEIGHT
            minHeight = TITLE_BAR_HEIGHT
            padding = Insets(0.0, 6.0, 0.0, 12.0)
            style = BAR_STYLE
        }
        attachDrag(bar, stage)

        return TitleBar(bar) { now -> active = now; paint() }
    }

    /** 八个方向都能拖：按下的位置落在窗口边缘 [EDGE] 像素内才算拖边，其余交给子节点。 */
    fun attachResize(root: Region, stage: Stage, minWidth: Double, minHeight: Double) {
        // 场景根外面留了投影的空隙，可见窗口的边缘在空隙里面 —— 命中判定要从那里往里算
        val padding = root.padding
        val inset = if (padding == null) 0.0
        else maxOf(padding.top, padding.right, padding.bottom, padding.left)

        fun hit(x: Double, y: Double, width: Double, height: Double): Dir = dirOf(
            left = x <= inset + EDGE,
            right = x >= width - inset - EDGE,
            top = y <= inset + EDGE,
            bottom = y >= height - inset - EDGE
        )

        var dir = Dir.NONE
        var startX = 0.0
        var startY = 0.0
        var startW = 0.0
        var startH = 0.0
        var pressScreenX = 0.0
        var pressScreenY = 0.0

        root.addEventFilter(MouseEvent.MOUSE_MOVED) { event ->
            root.cursor = cursorOf(hit(event.x, event.y, root.width, root.height))
        }
        root.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button != MouseButton.PRIMARY) return@addEventFilter
            val found = hit(event.x, event.y, root.width, root.height)
            if (found == Dir.NONE) return@addEventFilter
            dir = found
            startX = stage.x
            startY = stage.y
            startW = stage.width
            startH = stage.height
            pressScreenX = event.screenX
            pressScreenY = event.screenY
            event.consume()
        }
        root.addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
            if (dir == Dir.NONE) return@addEventFilter
            val dx = event.screenX - pressScreenX
            val dy = event.screenY - pressScreenY
            var x = startX
            var y = startY
            var w = startW
            var h = startH
            if (dir.west) {
                w = startW - dx
                x = startX + dx
            }
            if (dir.east) w = startW + dx
            if (dir.north) {
                h = startH - dy
                y = startY + dy
            }
            if (dir.south) h = startH + dy
            // 顶到最小尺寸后不能再往反方向拖，否则窗口会「跑」到鼠标另一侧
            if (dir.west && startW - dx < minWidth) x = startX + startW - minWidth
            if (dir.north && startH - dy < minHeight) y = startY + startH - minHeight
            w = w.coerceAtLeast(minWidth)
            h = h.coerceAtLeast(minHeight)
            stage.x = x
            stage.y = y
            stage.width = w
            stage.height = h
            event.consume()
        }
        root.addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (dir != Dir.NONE) {
                dir = Dir.NONE
                event.consume()
            }
        }
    }

    // ---------- 内部 ----------

    private fun attachDrag(node: Node, stage: Stage) {
        var offsetX = 0.0
        var offsetY = 0.0
        node.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            // 按在按钮上时那是「点按钮」，不是「拖窗口」
            if (event.target is ButtonBase || event.button != MouseButton.PRIMARY) return@addEventFilter
            offsetX = event.screenX - stage.x
            offsetY = event.screenY - stage.y
        }
        node.addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
            if (event.target is ButtonBase || !event.isPrimaryButtonDown) return@addEventFilter
            stage.x = event.screenX - offsetX
            stage.y = event.screenY - offsetY
        }
    }

    private fun logoView(): Node {
        val image = LOGO ?: return Region()
        return ImageView(image).apply {
            isPreserveRatio = true
            fitHeight = LOGO_HEIGHT
            isSmooth = true
            isMouseTransparent = true
        }
    }

    private fun glyphButton(
        shapes: List<Line>,
        tip: String,
        hoverBackground: String,
        idle: Color,
        hover: Color,
        action: () -> Unit
    ): Button {
        shapes.forEach { it.stroke = idle }
        val box = StackPane(Group(*shapes.toTypedArray())).apply { isMouseTransparent = true }
        return Button("", box).apply {
            tooltip = Tooltip(tip)
            prefWidth = BUTTON_WIDTH
            prefHeight = BUTTON_HEIGHT
            minWidth = BUTTON_WIDTH
            minHeight = BUTTON_HEIGHT
            style = buttonStyle(false, hoverBackground)
            setOnAction { action() }
            setOnMouseEntered {
                style = buttonStyle(true, hoverBackground)
                shapes.forEach { it.stroke = hover }
            }
            setOnMouseExited {
                style = buttonStyle(false, hoverBackground)
                shapes.forEach { it.stroke = idle }
            }
        }
    }

    private fun line(x1: Double, y1: Double, x2: Double, y2: Double): Line = Line(x1, y1, x2, y2).apply {
        strokeWidth = 1.4
        strokeLineCap = StrokeLineCap.ROUND
    }

    private fun buttonStyle(hover: Boolean, hoverBackground: String): String =
        "-fx-background-color: " + (if (hover) hoverBackground else "transparent") + ";" +
                "-fx-background-radius: 6; -fx-padding: 0; -fx-cursor: hand;" +
                "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;"

    private enum class Dir { NONE, N, S, W, E, NE, NW, SE, SW }

    private val Dir.north: Boolean get() = this == Dir.N || this == Dir.NW || this == Dir.NE
    private val Dir.south: Boolean get() = this == Dir.S || this == Dir.SW || this == Dir.SE
    private val Dir.west: Boolean get() = this == Dir.W || this == Dir.NW || this == Dir.SW
    private val Dir.east: Boolean get() = this == Dir.E || this == Dir.NE || this == Dir.SE

    /** 角优先：两条边同时命中时按角处理。 */
    private fun dirOf(left: Boolean, right: Boolean, top: Boolean, bottom: Boolean): Dir = when {
        left && top -> Dir.NW
        right && top -> Dir.NE
        left && bottom -> Dir.SW
        right && bottom -> Dir.SE
        left -> Dir.W
        right -> Dir.E
        top -> Dir.N
        bottom -> Dir.S
        else -> Dir.NONE
    }

    private val LOGO: Image? by lazy {
        runCatching { WindowChrome::class.java.getResourceAsStream("/logo.png")?.use { Image(it) } }.getOrNull()
    }

    private fun cursorOf(dir: Dir): Cursor = when (dir) {
        Dir.W, Dir.E -> Cursor.H_RESIZE
        Dir.N, Dir.S -> Cursor.V_RESIZE
        Dir.NW, Dir.SE -> Cursor.NW_RESIZE
        Dir.NE, Dir.SW -> Cursor.NE_RESIZE
        Dir.NONE -> Cursor.DEFAULT
    }

    private const val LOGO_HEIGHT = 26.0
    private const val BUTTON_WIDTH = 34.0
    private const val BUTTON_HEIGHT = 28.0
    private const val HOVER_BG = "#f1f1f3"
    private const val CLOSE_BG = "#e81123"

    /** 任务按钮「开着」时的底色，与左侧导航选中态同一档。 */
    private const val ACTIVE_BG = Theme.ACCENT_BG

    private val IDLE_GLYPH: Color = Color.web("#52525b")
    private val ACCENT_TEXT: Color = Color.web(Theme.ACCENT_TEXT)

    private const val BAR_STYLE =
        "-fx-background-color: #ffffff; -fx-border-color: transparent transparent #e6e6ea transparent;" +
                "-fx-border-width: 0 0 1 0;"
}
