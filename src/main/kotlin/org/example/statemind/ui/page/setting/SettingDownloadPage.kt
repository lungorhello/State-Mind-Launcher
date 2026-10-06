package org.example.statemind.ui.page.setting

import javafx.animation.PauseTransition
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Duration
import javafx.util.StringConverter
import kotlin.math.roundToInt
import org.example.statemind.core.DownloadOption
import org.example.statemind.core.FileSource
import org.example.statemind.core.Prefs
import org.example.statemind.core.VersionListSource
import org.example.statemind.ui.Page
import org.example.statemind.ui.Typo
import org.example.statemind.ui.helpMark

/**
 * 设置 · 下载 —— 文件从哪儿取、怎么取。
 *
 * 本页不自己存状态：控件就是 [Prefs] 里那份设置的投影，改一下就落盘，下次进来直接读回来。
 * 滑条在拖动过程中只更新右边的数字，停手 300 ms 才写文件 —— 不然拖一次要写几十遍磁盘。
 */
class SettingDownloadPage : Page {

    override val id = "setting.download"
    override val title = "下载"

    override fun build(): Node {
        val heading = Label("下载").apply { style = Typo.HEADING }

        val card = VBox(
            12.0,
            row("文件下载源", null, sourceBox(FileSource.entries, Prefs.fileSource) { Prefs.fileSource = it }),
            divider(),
            row(
                "版本列表源",
                null,
                sourceBox(VersionListSource.entries, Prefs.versionListSource) { Prefs.versionListSource = it }
            ),
            divider(),
            row(
                "下载线程数",
                helpMark("同时下载几个文件。0 为无限制，由启动器按网络情况自行决定。"),
                sliderRow(0, Prefs.THREADS_MAX, 1, Prefs.downloadThreads, ::threadsText) { Prefs.downloadThreads = it }
            ),
            divider(),
            row(
                "下载速度限制",
                helpMark("限制下载占用的带宽。0 为不限制。"),
                sliderRow(0, Prefs.SPEED_MAX_KBPS, 128, Prefs.downloadSpeedKbps, ::speedText) {
                    Prefs.downloadSpeedKbps = it
                }
            )
        ).apply {
            padding = Insets(14.0)
            style = """
                -fx-background-color: #ffffff;
                -fx-border-color: #d9d9de;
                -fx-border-width: 1;
                -fx-border-radius: 10;
                -fx-background-radius: 10;
            """
        }

        return VBox(14.0, heading, card).apply { padding = Insets(20.0) }
    }

    private fun row(title: String, tip: Node?, control: Node): HBox {
        val label = Label(title).apply {
            style = Typo.LABEL
            minWidth = LABEL_WIDTH
        }
        val head: Node = if (tip == null) label else HBox(6.0, label, tip).apply { alignment = Pos.CENTER_LEFT }
        return HBox(12.0, head, control).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(control, Priority.ALWAYS)
        }
    }

    /**
     * 一条「滑条 + 数值」。最左边是 [min]，按 [format] 显示成「无限制」或数字。
     * [onValue] 只在停手之后调一次。
     */
    private fun sliderRow(
        min: Int,
        max: Int,
        step: Int,
        initial: Int,
        format: (Int) -> String,
        onValue: (Int) -> Unit
    ): HBox {
        val slider = Slider(min.toDouble(), max.toDouble(), initial.toDouble()).apply {
            maxWidth = Double.MAX_VALUE
            isShowTickMarks = false
            isShowTickLabels = false
        }
        val readout = Label(format(initial)).apply {
            style = Typo.DIM
            minWidth = READOUT_WIDTH
            alignment = Pos.CENTER_RIGHT
        }

        var latest = initial
        val commit = PauseTransition(Duration.millis(300.0)).apply {
            setOnFinished { onValue(latest) }
        }
        slider.valueProperty().addListener { _, _, now ->
            latest = ((now.toDouble() / step).roundToInt() * step).coerceIn(min, max)
            readout.text = format(latest)
            commit.playFromStart()
        }

        return HBox(12.0, slider, readout).apply { alignment = Pos.CENTER_LEFT }
    }

    /** 下拉里显示 [DownloadOption.label]，不是枚举名。 */
    private fun <T : DownloadOption> sourceBox(values: List<T>, selected: T, onPick: (T) -> Unit): ComboBox<T> =
        ComboBox<T>().apply {
            items.setAll(values)
            converter = object : StringConverter<T>() {
                override fun toString(value: T?): String = value?.label.orEmpty()
                override fun fromString(text: String?): T? = values.firstOrNull { it.label == text }
            }
            value = selected
            maxWidth = Double.MAX_VALUE
            valueProperty().addListener { _, _, now -> if (now != null) onPick(now) }
        }

    private fun divider(): Region = Region().apply {
        minHeight = 1.0
        prefHeight = 1.0
        maxHeight = 1.0
        style = "-fx-background-color: #ececf0;"
    }

    private companion object {

        const val LABEL_WIDTH = 96.0
        const val READOUT_WIDTH = 70.0
    }
}

private fun threadsText(value: Int): String = if (value == 0) "无限制" else value.toString()

private fun speedText(value: Int): String = when {
    value == 0 -> "无限制"
    value < 1024 -> "$value KB/s"
    else -> "%.1f MB/s".format(value / 1024.0)
}
