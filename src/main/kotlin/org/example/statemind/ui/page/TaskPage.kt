package org.example.statemind.ui.page

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.example.statemind.core.download.DownloadCenter
import org.example.statemind.core.download.DownloadJob
import org.example.statemind.ui.Page
import org.example.statemind.ui.Typo

/**
 * 下载任务。入口在标题栏右边那个下载图标 —— 它不在左侧主导航里（[showInNav] 为 false），
 * 因为「看任务」是随时可能想瞄一眼的临时动作，不该跟「去哪个功能区」并列。
 *
 * 页面不自己存状态：画面是 [DownloadCenter.jobs] 的投影，变一次刷一次。
 */
class TaskPage : Page {

    override val id = ID
    override val title = "下载任务"

    override val showInNav = false

    private val rows = HashMap<Long, JobRow>()
    private val listBox = VBox(10.0)
    private val empty = Label("暂无下载任务").apply {
        style = "-fx-font-size: 13px; -fx-text-fill: #9a9aa0;"
    }

    private var wired = false
    private var listRoot: VBox? = null

    override fun build(): Node {
        val heading = Label("下载任务").apply { style = Typo.HEADING }

        val clear = Button("清除已完成").apply {
            style = CLEAR_STYLE
            setOnAction { DownloadCenter.clearFinished() }
        }

        val header = HBox(
            10.0,
            heading,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            clear
        ).apply { alignment = Pos.CENTER_LEFT }

        listRoot = VBox(14.0, header, StackPane(empty, listBox).apply { alignment = Pos.TOP_LEFT })
            .apply { padding = Insets(20.0) }

        if (!wired) {
            wired = true
            // 下载线程发出来的通知：回界面线程再动控件
            DownloadCenter.onChange { Platform.runLater { refresh() } }
        }
        refresh()
        return listRoot!!
    }

    override fun onEnter() = refresh()

    private fun refresh() {
        val jobs = DownloadCenter.jobs
        rows.keys.retainAll(jobs.map { it.id }.toSet())
        empty.isVisible = jobs.isEmpty()

        val wanted = jobs.map { rows.getOrPut(it.id) { JobRow(it) } }
        // 控件复用：进度每 250 ms 刷一次，重建节点会一直重置滚动位置
        if (listBox.children.toList() != wanted) listBox.children.setAll(wanted)
        wanted.forEach { it.update() }
    }

    companion object {

        const val ID = "task"

        private const val CLEAR_STYLE =
            "-fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: #52525b; -fx-font-size: 13px; -fx-padding: 5 12 5 12;" +
                    "-fx-cursor: hand;"
    }
}

/** 一个任务一张卡。控件只建一次，刷新只改文字与进度。 */
private class JobRow(private val job: DownloadJob) : VBox(8.0) {

    private val title = Label(job.versionId).apply { style = Typo.SECTION }
    private val state = Label().apply { style = Typo.NOTE }
    private val cancel = Button("取消").apply {
        style = CANCEL_STYLE
        setOnAction {
            job.cancel()
            isDisable = true
            text = "取消中…"
        }
    }

    private val bar = ProgressBar(0.0).apply {
        maxWidth = Double.MAX_VALUE
        prefHeight = 6.0
    }

    private val detail = Label().apply { style = Typo.NOTE }

    init {
        val head = HBox(
            10.0,
            title,
            state,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            cancel
        ).apply { alignment = Pos.CENTER_LEFT }

        children.setAll(head, bar, detail)
        padding = Insets(12.0)
        style = CARD_STYLE
    }

    fun update() {
        bar.progress = job.progress
        state.text = job.note
        state.style = when (job.state) {
            DownloadJob.State.DONE -> "-fx-font-size: 12px; -fx-text-fill: #16a34a;"
            DownloadJob.State.FAILED -> "-fx-font-size: 12px; -fx-text-fill: #dc2626;"
            else -> Typo.NOTE
        }
        cancel.isVisible = !job.finished
        cancel.isManaged = !job.finished

        val counts = "${job.doneFiles} / ${job.totalFiles} 个文件"
        detail.text = when (job.state) {
            DownloadJob.State.DONE,
            DownloadJob.State.CANCELLED,
            DownloadJob.State.FAILED ->
                "${bytes(job.doneBytes)} · $counts" + if (job.message.isBlank()) "" else " · ${job.message}"

            else -> "${bytes(job.doneBytes)} / ${bytes(job.totalBytes)} · $counts" +
                    if (job.bytesPerSecond > 0) " · ${bytes(job.bytesPerSecond)}/s" else ""
        }
    }

    private fun bytes(value: Long): String = when {
        value <= 0L -> "0 B"
        value < 1024L -> "$value B"
        value < 1024L * 1024 -> "%.1f KB".format(value / 1024.0)
        value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / 1048576.0)
        else -> "%.2f GB".format(value / 1073741824.0)
    }

    private companion object {
        const val CARD_STYLE =
            "-fx-background-color: #ffffff; -fx-border-color: #d9d9de; -fx-border-width: 1;" +
                    "-fx-border-radius: 10; -fx-background-radius: 10;"

        const val CANCEL_STYLE =
            "-fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
                    "-fx-border-radius: 6; -fx-background-radius: 6;" +
                    "-fx-text-fill: #52525b; -fx-font-size: 12px; -fx-padding: 3 10 3 10;" +
                    "-fx-cursor: hand;"
    }
}
