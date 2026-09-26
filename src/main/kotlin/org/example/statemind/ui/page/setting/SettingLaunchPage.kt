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
import javafx.scene.layout.VBox
import org.example.statemind.core.BackendUtil.JavaInfo
import org.example.statemind.core.JavaStore
import org.example.statemind.ui.Page
import org.example.statemind.ui.helpMark

/**
 * 设置 · 启动 —— Java 虚拟机。
 *
 * Java 从首页搬到这里，并且从「每次启动临时选」变成**全局设置**：
 *  - 一个开关（Atlantafx 的 [ToggleSwitch]，就是主题自带那个胶囊开关）控制「自动选择 Java 版本」；
 *  - 下面是手动指定的那份 Java（原来首页那个下拉），自动选择开着时整体禁用、不生效。
 *
 * 开关**不要**自己再加 `setOnMouseClicked`：Atlantafx 的皮肤自己处理鼠标事件并调用 `fire()`，
 * 再挂一个点击回调会变成双触发（点一下等于没点）。
 *
 * 真正的挑选逻辑在 [JavaStore]：它管扫描、管落盘、管按游戏版本匹配，本页只负责显示和转发点击。
 * 将来「版本设置」里会有「跟随全局设置 / 自动选择 / 自己选择」三档，这里就是「全局」那一份。
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

    /** 手动指定的 Java。 */
    private val combo = ComboBox<JavaInfo>()

    /** PageHost 是懒加载 + 缓存，正常只会 build 一次；这里再兜一道，免得重复订阅。 */
    private var wired = false

    override fun build(): Node {
        // ── 小标题 ────────────────────────────────────────────────────────
        val heading = Label("Java虚拟机").apply {
            style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1f1f22;"
        }

        // ── 自动选择：一行「标题 + ? + 开关」 ──────────────────────────────
        val autoTitle = Label("自动选择 Java 版本").apply {
            style = "-fx-font-size: 14px; -fx-text-fill: #1f1f22;"
        }
        toggle.selectedProperty().addListener { _, _, now -> JavaStore.auto = now }
        val autoRow = HBox(
            10.0,
            autoTitle,
            helpMark("开启后按游戏版本自动选择合适的 Java；关闭后使用手动指定的 Java。"),
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            toggle
        ).apply { alignment = Pos.CENTER_LEFT }

        // ── 手动指定 ──────────────────────────────────────────────────────
        val javaTitle = Label("Java 版本").apply {
            style = "-fx-font-size: 14px; -fx-text-fill: #1f1f22;"
        }
        val javaNote = Label("在自动选择关闭后全局生效").apply {
            style = "-fx-font-size: 12px; -fx-text-fill: #9a9aa0;"
        }
        val javaHeader = HBox(9.0, javaTitle, javaNote).apply { alignment = Pos.BASELINE_LEFT }

        combo.apply {
            maxWidth = Double.MAX_VALUE
            isDisable = true
            // 下拉里的一项被选中 → 记进全局设置（自动选择开着时下面的监听里会拦掉）
            valueProperty().addListener { _, _, now ->
                if (!JavaStore.auto && now != null) JavaStore.manualPath = now.path
            }
        }

        // ── 组装卡片 ──────────────────────────────────────────────────────
        val divider = Region().apply {
            minHeight = 1.0
            prefHeight = 1.0
            maxHeight = 1.0
            style = "-fx-background-color: #ececf0;"
        }

        val card = VBox(12.0, autoRow, divider, javaHeader, combo).apply {
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
            JavaStore.onChange { sync() }     // 扫描完成 / 开关切换都会回到这里刷界面
        }
        sync()
        return root
    }

    /** 按当前状态刷一遍界面。幂等，可以随便调。 */
    private fun sync() {
        val auto = JavaStore.auto
        // 只在真的不一致时才赋值 —— 否则会反过来再触发一遍开关的监听
        if (toggle.isSelected != auto) toggle.isSelected = auto

        // 内容真的变了才 setAll —— 直接重建会把选中项冲掉，白白触发一轮变更事件
        val javas = JavaStore.javas
        if (combo.items.toList() != javas) combo.items.setAll(javas)

        combo.isDisable = auto || javas.isEmpty()
        combo.promptText = when {
            !JavaStore.scanned -> "扫描中…"
            javas.isEmpty() -> "未检测到本机 Java"
            auto -> "自动选择中"
            else -> "请选择 Java"
        }
        if (auto) {
            // 自动模式下下拉不显示任何选中项：显示谁都是误导，实际用的是启动时按版本算出来的那份
            combo.selectionModel.clearSelection()
        } else {
            val want = JavaStore.manual ?: javas.firstOrNull()
            if (combo.value != want) combo.value = want
        }
    }

    private companion object {
        /**
         * 启动器主题紫（淡）—— 界面上的强调色统一从这里取。
         * 用「-color-accent-emphasis」这个名字覆盖主题自带的强调色变量，只影响挂上它的那棵子树。
         */
        const val ACCENT_SOFT = "#a78bfa"
    }
}
