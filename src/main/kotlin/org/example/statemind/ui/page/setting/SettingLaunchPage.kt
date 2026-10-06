package org.example.statemind.ui.page.setting

import atlantafx.base.controls.ToggleSwitch
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import org.example.statemind.core.BackendUtil.JavaInfo
import org.example.statemind.core.JavaStore
import org.example.statemind.core.LaunchSelection
import org.example.statemind.ui.Page
import org.example.statemind.ui.Typo
import org.example.statemind.ui.helpMark

/**
 * 设置 · 启动 —— Java 虚拟机。
 *
 * Java 从首页搬到这里，并且从「每次启动临时选」变成**全局设置**：
 *  - 一个开关（Atlantafx 的 [ToggleSwitch]，就是主题自带那个胶囊开关）控制「自动选择 Java 版本」；
 *  - 下面是手动指定的那份 Java（原来首页那个下拉），自动选择开着时整体不生效。
 *
 * 开关**不要**自己再加 `setOnMouseClicked`：Atlantafx 的皮肤自己处理鼠标事件并调用 `fire()`，
 * 再挂一个点击回调会变成双触发（点一下等于没点）。
 *
 * 真正的挑选逻辑在 [JavaStore]：它管扫描、管落盘、管按游戏版本匹配，本页只负责显示和转发点击。
 * 将来「版本设置」里会有「跟随全局设置 / 自动选择 / 自己选择」三档，这里就是「全局」那一份。
 *
 * ## 为什么不是「一个下拉干到底」
 *
 * `ComboBox` 只在**选中项确实存在于 `items` 里**的时候才稳定地画出文字：
 *  - 选中值不在 `items` 里 → 选择模型会把它清成 `null`（`ListCell` 随之被重置）；
 *  - `value == null` 时走提示文字（`promptText`）那条路 —— 这条路在部分机器上**一个字都不画**。
 *
 * 上面两种情况恰好就是「自动选择开着」「一份 Java 都没扫到」这两个空态，也就是 0.2 起
 * 那个「下拉一片空白」。所以这里不再让下拉硬扛空态：
 *
 *  - 只有**真的能挑一份**（关掉自动选择、且至少扫到一份 Java）时，才让下拉登场，
 *    并且选中值一定取自 `items` 自己，绝不给它一个列表外的对象；
 *  - 其余状态一律换成 [field] —— 一块**只读文字**（`Label`），文字永远不会画不出来。
 *
 * 两者叠在同一个 [slot] 里、且**都参与布局**，只切换 `isVisible`：格子高度始终按最高的
 * 那个算，所以状态切换时版面不会跳一下。
 */
class SettingLaunchPage : Page {

    override val id = "setting.launch"
    override val title = "启动"

    /**
     * 自动选择开关。尺寸与动画仍来自主题的 `.toggle-switch` 规则，
     * 只把强调色（`-color-accent-emphasis`，主题默认是蓝色）换成启动器主题紫（淡）。
     */
    private val toggle = ToggleSwitch().apply {
        style = "-color-accent-emphasis: $ACCENT_SOFT;"
    }

    /** 手动指定的 Java。只在 [sync] 判定「可以挑」的时候才露出来。 */
    private val combo = ComboBox<JavaInfo>()

    /**
     * 空态显示的那块只读文字。样式仿输入框外形，但底色压灰，一眼看得出「现在没得选」。
     * 设成鼠标穿透：它只是一块牌子，点击不该被它拦下。
     */
    private val field = Label().apply {
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
        padding = Insets(0.0, 10.0, 0.0, 10.0)
        alignment = Pos.CENTER_LEFT
        isMouseTransparent = true
        style = """
            -fx-background-color: #f6f6f8;
            -fx-border-color: #dcdce2;
            -fx-border-width: 1;
            -fx-border-radius: 6;
            -fx-background-radius: 6;
            -fx-text-fill: #6b6b73;
            -fx-font-size: 13px;
        """
    }

    /** 下拉与只读文字共用的一格。两个都留在布局里，切换时高度不变。 */
    private val slot = StackPane(combo, field)

    /** PageHost 是懒加载 + 缓存，正常只会 build 一次；这里再兜一道，免得重复订阅。 */
    private var wired = false

    override fun build(): Node {
        // ── 小标题 ────────────────────────────────────────────────────────
        val heading = Label("Java虚拟机").apply { style = Typo.HEADING }

        // ── 自动选择：一行「标题 + ? + 开关」 ──────────────────────────────
        val autoTitle = Label("自动选择 Java 版本").apply { style = Typo.LABEL }
        toggle.selectedProperty().addListener { _, _, now -> JavaStore.auto = now }
        val autoRow = HBox(
            10.0,
            autoTitle,
            helpMark("开启后按游戏版本自动选择合适的 Java；关闭后使用手动指定的 Java。"),
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            toggle
        ).apply { alignment = Pos.CENTER_LEFT }

        // ── 手动指定 ──────────────────────────────────────────────────────
        val javaTitle = Label("Java 版本").apply { style = Typo.LABEL }
        val javaNote = Label("在自动选择关闭后全局生效").apply { style = Typo.NOTE }
        val javaHeader = HBox(9.0, javaTitle, javaNote).apply { alignment = Pos.BASELINE_LEFT }

        combo.apply {
            maxWidth = Double.MAX_VALUE
            converter = object : StringConverter<JavaInfo>() {
                override fun toString(value: JavaInfo?): String = value?.toString().orEmpty()
                override fun fromString(text: String?): JavaInfo? = null
            }
            // 下拉里的一项被选中 → 记进全局设置
            valueProperty().addListener { _, _, now ->
                if (now != null && now.path.isNotBlank()) JavaStore.manualPath = now.path
            }
        }

        // ── 组装卡片 ──────────────────────────────────────────────────────
        val divider = Region().apply {
            minHeight = 1.0
            prefHeight = 1.0
            maxHeight = 1.0
            style = "-fx-background-color: #ececf0;"
        }

        val card = VBox(12.0, autoRow, divider, javaHeader, slot).apply {
            padding = Insets(14.0)
            style = """
                -fx-background-color: #ffffff;
                -fx-border-color: #d9d9de;
                -fx-border-width: 1;
                -fx-border-radius: 10;
                -fx-background-radius: 10;
            """
        }

        val root = VBox(14.0, heading, card).apply { padding = Insets(20.0) }

        if (!wired) {
            wired = true
            JavaStore.onChange { sync() }          // 扫描完成 / 开关切换都会回到这里刷界面
            LaunchSelection.onChange { sync() }    // 首页换版本 → 「将使用 Java x.y.z」跟着变
        }
        sync()
        return root
    }

    /** 首页可能刚换过版本 —— 回来时重算一遍「将使用哪份 Java」。 */
    override fun onEnter() = sync()

    /** 按当前状态刷一遍界面。幂等，可以随便调。 */
    private fun sync() {
        val auto = JavaStore.auto
        // 只在真的不一致时才赋值 —— 否则会反过来再触发一遍开关的监听
        if (toggle.isSelected != auto) toggle.isSelected = auto

        val javas = JavaStore.javas

        // 有没有实例先判：一个版本都没装的时候，报「将使用 Java 25.0.1」是句空话
        field.text = when {
            !JavaStore.scanned -> "扫描中…"
            !JavaStore.hasInstance -> "暂无实例"
            javas.isEmpty() -> "没有可用 Java"
            !auto -> "请选择 Java"
            else -> "将使用 Java ${JavaStore.pickFor(LaunchSelection.current)?.version.orEmpty()}"
        }

        // 只有「关掉自动选择 + 至少有一份 Java」才让下拉登场，其余一律用只读文字
        val selectable = !auto && javas.isNotEmpty()

        // 内容真的变了才 setAll —— 直接重建会把选中项冲掉，白白触发一轮变更事件
        if (combo.items.toList() != javas) combo.items.setAll(javas)

        // 选中值**只能**取自 items 自己：给列表外的对象，选择模型会把它清成 null，文字就没了
        val want = if (selectable) javas.firstOrNull { it.path == JavaStore.manual?.path } ?: javas.first() else null
        if (combo.value != want) combo.value = want

        field.isVisible = !selectable
        combo.isVisible = selectable
    }

    private companion object {
        /**
         * 启动器主题紫（淡）—— 界面上的强调色统一从这里取。
         * 用「-color-accent-emphasis」这个名字覆盖主题自带的强调色变量，只影响挂上它的那棵子树。
         */
        const val ACCENT_SOFT = "#a78bfa"
    }
}
