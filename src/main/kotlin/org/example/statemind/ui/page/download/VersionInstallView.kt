package org.example.statemind.ui.page.download

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.loader.LoaderChoice
import org.example.statemind.core.loader.LoaderKind
import org.example.statemind.core.loader.LoaderSource
import org.example.statemind.ui.Typo

/**
 * 选好原版版本之后的那一页：挑加载器，然后开始下载。
 *
 * 加载器版本要联网取，所以整页常驻一个状态行 —— 读的时候说在读、读不到说没读到，
 * 别让「开始下载」在什么都还没准备好的时候可点。
 *
 * 左下角必须标出下载源 —— BMCLAPI 的条款要求下载界面说明来源。
 */
internal class VersionInstallView(
    private val onBack: () -> Unit,
    private val onStart: () -> Unit
) : BorderPane() {

    private val versionLabel = Label()
    private val sourceNote = Label()
    private val loaderNote = Label()
    private val loaderVersion = ComboBox<String>()
    private val chips = ToggleGroup()
    private val chipButtons = LinkedHashMap<LoaderKind, ToggleButton>()

    private val startButton = Button("开始下载").apply {
        style = BTN_PRIMARY
        setOnAction { onStart() }
    }

    /** 这一页正在准备下载哪个版本。 */
    var current: VersionEntry? = null
        private set

    /** 选中的加载器；原版为 null。 */
    var choice: LoaderChoice? = null
        private set

    /** 每次换版本 +1：后台线程回来的结果对不上就丢掉，免得把旧版本加载器填进新页面。 */
    private var generation = 0

    init {
        padding = Insets(DownloadStyles.PAGE_PADDING)

        top = HBox(
            Button("返回").apply {
                style = BACK_STYLE
                setOnAction { onBack() }
            }
        ).apply { alignment = Pos.CENTER_LEFT }

        versionLabel.style =
            "-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: ${DownloadStyles.TEXT};"

        val loaderTitle = Label("模组加载器").apply { style = Typo.LABEL }

        val chipRow = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            LoaderKind.entries.forEach { kind -> children += chip(kind) }
        }

        loaderNote.style = Typo.NOTE
        loaderNote.isWrapText = true
        loaderNote.maxWidth = 460.0

        loaderVersion.apply {
            style = DownloadStyles.FIELD
            prefWidth = 220.0
            maxWidth = 220.0
            isVisible = false
            isManaged = false
            valueProperty().addListener { _, _, now -> onLoaderVersion(now) }
        }

        center = VBox(
            10.0,
            versionLabel,
            Region().apply { minHeight = 10.0; prefHeight = 10.0; maxHeight = 10.0 },
            loaderTitle,
            chipRow,
            loaderVersion,
            loaderNote
        ).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(18.0, 0.0, 0.0, 0.0)
        }

        sourceNote.style = "-fx-font-size: 12px; -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
        bottom = HBox(
            10.0, sourceNote,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            startButton
        ).apply { alignment = Pos.CENTER_LEFT }
    }

    fun show(entry: VersionEntry) {
        current = entry
        versionLabel.text = entry.id
        // 上一版可能是 Fabric 版；换版本时把选择拨回原版，否则「选着 Fabric、实际按原版下」
        chipButtons[LoaderKind.VANILLA]?.isSelected = true
        select(LoaderKind.VANILLA)
    }

    fun setSource(label: String) {
        sourceNote.text = "下载源：$label"
    }

    // ---------- 加载器 ----------

    private fun chip(kind: LoaderKind): ToggleButton {
        val button = ToggleButton(kind.label).apply {
            toggleGroup = chips
            userData = kind
            minWidth = Region.USE_PREF_SIZE
            isSelected = kind == LoaderKind.VANILLA
            isDisable = !kind.supported
            style = when {
                !kind.supported -> CHIP_OFF_DISABLED
                kind == LoaderKind.VANILLA -> CHIP_ON
                else -> CHIP_OFF
            }
            if (!kind.supported) tooltip = Tooltip("暂不支持，需要用官方安装器改装，后续版本接上")
            if (kind.supported) {
                // ToggleGroup 再点一次会取消选中，加载器总得有一个
                setOnAction {
                    if (!isSelected) isSelected = true
                    select(kind)
                }
                selectedProperty().addListener { _, _, now -> style = if (now) CHIP_ON else CHIP_OFF }
            }
        }
        chipButtons[kind] = button
        return button
    }

    private fun select(kind: LoaderKind) {
        generation++
        val entry = current
        loaderVersion.items.clear()
        loaderVersion.value = null

        if (kind == LoaderKind.VANILLA || entry == null) {
            choice = null
            loaderNote.text = ""
            showLoaderVersion(false)
            startButton.isDisable = false
            return
        }

        choice = null
        startButton.isDisable = true
        showLoaderVersion(false)
        loaderNote.text = "读取${kind.label}版本…"

        val mine = generation
        val mc = entry.id
        Thread {
            val outcome = runCatching { LoaderSource.versions(kind, mc) }
            Platform.runLater {
                if (mine != generation) return@runLater
                val versions = outcome.getOrDefault(emptyList())
                when {
                    outcome.isFailure -> {
                        loaderNote.text = "读取失败：${outcome.exceptionOrNull()?.message ?: "网络错误"}"
                    }

                    versions.isEmpty() -> {
                        loaderNote.text = "$mc 没有可用的${kind.label}版本"
                    }

                    else -> {
                        loaderNote.text = ""
                        showLoaderVersion(true)
                        loaderVersion.items.setAll(versions)
                        loaderVersion.value = versions.first()
                    }
                }
            }
        }.apply { isDaemon = true; name = "loader-versions" }.start()
    }

    private fun onLoaderVersion(loaderVersionValue: String?) {
        val entry = current ?: return
        val kind = (chips.selectedToggle as? ToggleButton)?.userData as? LoaderKind
            ?: LoaderKind.VANILLA
        if (kind == LoaderKind.VANILLA || loaderVersionValue.isNullOrBlank()) {
            choice = null
            startButton.isDisable = true
            return
        }
        choice = LoaderSource.choice(kind, entry.id, loaderVersionValue)
        startButton.isDisable = false
    }

    /** 隐藏时一并退出布局，否则下拉会空占一行。 */
    private fun showLoaderVersion(visible: Boolean) {
        loaderVersion.isVisible = visible
        loaderVersion.isManaged = visible
    }

    private companion object {

        const val BACK_STYLE =
            "-fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: #52525b; -fx-font-size: 13px; -fx-padding: 6 14 6 14;" +
                    "-fx-cursor: hand;"

        /** 跟弹窗里那个「确定」同一套。 */
        const val BTN_PRIMARY =
            "-fx-background-color: ${DownloadStyles.ACCENT}; -fx-background-radius: 8;" +
                    "-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold;" +
                    "-fx-padding: 8 18 8 18; -fx-cursor: hand;"

        const val CHIP_BASE =
            "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-width: 1;" +
                    "-fx-font-size: 13px; -fx-padding: 6 14 6 14;" +
                    "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;" +
                    "-fx-cursor: hand;"

        const val CHIP_ON = CHIP_BASE +
                "-fx-background-color: ${DownloadStyles.ACCENT_BG};" +
                "-fx-border-color: ${DownloadStyles.ACCENT_LINE};" +
                "-fx-text-fill: ${DownloadStyles.ACCENT_TEXT};"

        const val CHIP_OFF = CHIP_BASE +
                "-fx-background-color: #ffffff; -fx-border-color: #d9d9de;" +
                "-fx-text-fill: #3f3f46;"

        /** 禁用态默认叠半透明，字会淡到看不清；只靠文字色表达「不可用」。 */
        const val CHIP_OFF_DISABLED = CHIP_BASE +
                "-fx-background-color: transparent; -fx-border-color: #e4e4e9;" +
                "-fx-text-fill: #c2c2c9; -fx-opacity: 1;"
    }
}
