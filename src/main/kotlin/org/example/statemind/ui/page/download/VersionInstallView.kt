package org.example.statemind.ui.page.download

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.example.statemind.core.VersionEntry

/**
 * 选好版本之后的那一页。0.3 正文只有版本号，将来在这里选加载器。
 *
 * 左下角必须标出下载源 —— BMCLAPI 的条款要求下载界面说明来源。
 */
internal class VersionInstallView(
    private val onBack: () -> Unit,
    private val onStart: () -> Unit
) : BorderPane() {

    private val versionLabel = Label()
    private val sourceNote = Label()

    init {
        padding = Insets(DownloadStyles.PAGE_PADDING)

        top = HBox(
            Button("返回").apply {
                style = BACK_STYLE
                setOnAction { onBack() }
            }
        ).apply { alignment = Pos.CENTER_LEFT }

        center = VBox(8.0, versionLabel).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(18.0, 0.0, 0.0, 0.0)
        }
        versionLabel.style =
            "-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: ${DownloadStyles.TEXT};"

        sourceNote.style = "-fx-font-size: 12px; -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
        bottom = HBox(
            10.0, sourceNote,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            Button("开始下载").apply {
                style = BTN_PRIMARY
                setOnAction { onStart() }
            }
        ).apply { alignment = Pos.CENTER_LEFT }
    }

    fun show(entry: VersionEntry) {
        versionLabel.text = entry.id
    }

    fun setSource(label: String) {
        sourceNote.text = "下载源：$label"
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
    }
}
