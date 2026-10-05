package org.example.statemind.ui.page.setting

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import org.example.statemind.core.DownloadOption
import org.example.statemind.core.FileSource
import org.example.statemind.core.Prefs
import org.example.statemind.core.VersionListSource
import org.example.statemind.ui.Page

/**
 * 设置 · 下载 —— 两处下载源从哪儿取。
 *
 * 本页不自己存状态：控件就是 [Prefs] 里那份设置的投影，选一下就落盘，下次进来直接读回来。
 */
class SettingDownloadPage : Page {

    override val id = "setting.download"
    override val title = "下载"

    override fun build(): Node {
        val heading = Label("下载").apply {
            style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1f1f22;"
        }

        val card = VBox(
            12.0,
            settingRow("文件下载源", sourceBox(FileSource.entries, Prefs.fileSource) { Prefs.fileSource = it }),
            divider(),
            settingRow(
                "版本列表源",
                sourceBox(VersionListSource.entries, Prefs.versionListSource) { Prefs.versionListSource = it }
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

    private fun settingRow(title: String, control: Node): HBox {
        val label = Label(title).apply {
            style = "-fx-font-size: 14px; -fx-text-fill: #1f1f22;"
            minWidth = LABEL_WIDTH
        }
        return HBox(12.0, label, control).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(control, Priority.ALWAYS)
        }
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
    }
}
