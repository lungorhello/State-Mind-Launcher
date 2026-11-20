package org.example.statemind.ui.page

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.example.statemind.core.download.DownloadCenter
import org.example.statemind.core.download.DownloadJob
import org.example.statemind.core.mod.InstallLog
import org.example.statemind.ui.Dialogs
import org.example.statemind.ui.Page
import org.example.statemind.ui.Typo

/**
 * 下载任务。入口在标题栏右边那个下载图标 —— 它不在左侧主导航里（[showInNav] 为 false），
 * 因为「看任务」是随时可能想瞄一眼的临时动作，不该跟「去哪个功能区」并列。
 *
 * 两类任务混在同一列里：整份游戏版本走 [DownloadCenter]，单个模组 / 资源包 / 光影走 [InstallLog]。
 * 每张卡片左上角有个小标说明这是哪一类 —— 分开两段反而更难扫。
 *
 * 页面不自己存状态：画面是这两份清单的投影，变一次刷一次。
 */
class TaskPage : Page {

    override val id = ID
    override val title = "下载任务"

    override val showInNav = false

    private val rows = HashMap<Long, JobRow>()
    private val assetRows = HashMap<InstallLog.Entry, AssetRow>()

    private val listBox = VBox(10.0)
    private val empty = Label("暂无下载任务").apply {
        style = "-fx-font-size: 13px; -fx-text-fill: #9a9aa0;"
    }

    private var wired = false

    override fun build(): Node {
        val heading = Label("下载任务").apply { style = Typo.HEADING }

        val clear = Button("清除已完成").apply {
            style = CLEAR_STYLE
            setOnAction {
                DownloadCenter.clearFinished()
                InstallLog.clearFinished()
            }
        }

        val header = HBox(
            10.0,
            heading,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            clear
        ).apply { alignment = Pos.CENTER_LEFT }

        val content = VBox(
            12.0,
            header,
            StackPane(empty, listBox).apply { alignment = Pos.TOP_LEFT }
        ).apply { padding = Insets(20.0) }

        if (!wired) {
            wired = true
            // 下载线程 / 安装线程发出来的通知：回界面线程再动控件
            DownloadCenter.onChange { Platform.runLater { refresh() } }
            InstallLog.onChange { Platform.runLater { refresh() } }
        }
        refresh()

        return ScrollPane(content).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = "-fx-background-color: transparent; -fx-background: transparent;" +
                    "-fx-border-color: transparent; -fx-padding: 0;"
        }
    }

    override fun onEnter() = refresh()

    private fun refresh() {
        val jobs = DownloadCenter.jobs
        val assets = InstallLog.all
        rows.keys.retainAll(jobs.map { it.id }.toSet())
        assetRows.keys.retainAll(assets.toSet())
        empty.isVisible = jobs.isEmpty() && assets.isEmpty()

        // 版本任务在前、社区资源在后：版本任务通常更久，先看到它
        val wanted = ArrayList<Node>(jobs.size + assets.size)
        jobs.forEach { wanted += rows.getOrPut(it.id) { JobRow(it) } }
        assets.forEach { wanted += assetRows.getOrPut(it) { AssetRow(it) } }
        // 控件复用：进度每 250 ms 刷一次，重建节点会一直重置滚动位置
        if (listBox.children.toList() != wanted) listBox.children.setAll(wanted)

        jobs.forEach { rows[it.id]?.update() }
        assets.forEach { assetRows[it]?.update() }
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

/** 卡片左上角那个小标：一眼分清「这是整套版本」还是「这是某个模组」。 */
private fun tag(text: String, project: Boolean): Label = Label(text).apply {
    style = if (project)
        "-fx-font-size: 11px; -fx-text-fill: #6d28d9; -fx-background-color: #f7f3fe;" +
                "-fx-background-radius: 4; -fx-padding: 1 6 1 6;"
    else
        "-fx-font-size: 11px; -fx-text-fill: #52525b; -fx-background-color: #f2f2f5;" +
                "-fx-background-radius: 4; -fx-padding: 1 6 1 6;"
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

    private val percent = Label().apply {
        style = Typo.NOTE
        minWidth = 40.0
        alignment = Pos.CENTER_RIGHT
    }

    /** 阶段列表：下载原版资源 / 下载 Forge 主文件 / 安装 Forge … */
    private val stageBox = VBox(4.0)

    /** 已建出来的阶段行，按阶段对象认领。阶段会随下载往后加。 */
    private val stageRows = HashMap<DownloadJob.StageState, StageRow>()

    private val detail = Label().apply { style = Typo.NOTE }

    /** 失败时才出现：点开看逐阶段的情况。 */
    private val more = Button("查看详情").apply {
        style = MORE_STYLE
        isVisible = false
        isManaged = false
        setOnAction { showDetail() }
    }

    init {
        val head = HBox(
            8.0,
            tag("游戏版本", project = true),
            title,
            state,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            cancel
        ).apply { alignment = Pos.CENTER_LEFT }

        val barRow = HBox(8.0, bar, percent).apply { alignment = Pos.CENTER_LEFT }
        HBox.setHgrow(bar, Priority.ALWAYS)

        children.setAll(head, barRow, stageBox, detail, more)
        padding = Insets(12.0)
        style = CARD_STYLE
    }

    fun update() {
        val progress = job.progress
        bar.progress = progress
        percent.text = "${(progress * 100).toInt()}%"
        state.text = job.note
        state.style = when (job.state) {
            DownloadJob.State.DONE -> OK_STYLE
            DownloadJob.State.FAILED -> FAIL_STYLE
            else -> Typo.NOTE
        }
        cancel.isVisible = !job.finished
        cancel.isManaged = !job.finished

        // 阶段是随下载往后加的，按 order 排好序再摆出来
        val stages = job.stages.sortedBy { it.order }
        val rows = stages.map { stage ->
            stageRows.getOrPut(stage) { StageRow(stage) }
        }
        rows.forEach { it.update() }
        if (stageBox.children.toList() != rows) stageBox.children.setAll(rows)

        val counts = "${job.doneFiles} / ${job.totalFiles} 个文件"
        detail.text = when (job.state) {
            DownloadJob.State.DONE,
            DownloadJob.State.CANCELLED,
            DownloadJob.State.FAILED ->
                "${bytes(job.doneBytes)} · $counts" + if (job.message.isBlank()) "" else " · ${job.message}"

            else -> "${bytes(job.doneBytes)} / ${bytes(job.totalBytes)} · $counts" +
                    if (job.bytesPerSecond > 0) " · ${bytes(job.bytesPerSecond)}/s" else ""
        }

        val showMore = job.state == DownloadJob.State.FAILED
        more.isVisible = showMore
        more.isManaged = showMore
    }

    /** 失败详情：一条主要原因 + 逐阶段的情况。 */
    private fun showDetail() {
        val stages = job.stages.sortedBy { it.order }
        val list = VBox(6.0).apply {
            children += stages.map { stage ->
                HBox(8.0, Label(stage.title).apply { style = Typo.NOTE; minWidth = 150.0 },
                    Label(stage.error.ifBlank { stage.status }).apply {
                        style = if (stage.status == "失败") FAIL_NOTE else Typo.NOTE
                    })
            }
        }
        val scroll = ScrollPane(list).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = "-fx-background-color: transparent; -fx-background: transparent;" +
                    "-fx-border-color: transparent; -fx-padding: 0;"
        }
        Dialogs.detail(
            "下载未完成",
            job.mainReason.ifBlank { "有文件没能下载下来" },
            scroll
        )
    }

    private companion object {
        const val OK_STYLE = "-fx-font-size: 12px; -fx-text-fill: #16a34a;"
        const val FAIL_STYLE = "-fx-font-size: 12px; -fx-text-fill: #dc2626;"
    }
}

/** 一行阶段：标题 + 状态 + 细进度条 + 百分比。 */
private class StageRow(private val stage: DownloadJob.StageState) : HBox(8.0) {

    private val name = Label(stage.title).apply {
        style = Typo.NOTE
        minWidth = 150.0
    }
    private val status = Label().apply {
        style = Typo.NOTE
        minWidth = 52.0
    }
    private val bar = ProgressBar(0.0).apply {
        prefWidth = 120.0
        maxWidth = 120.0
        prefHeight = 4.0
    }
    private val percent = Label().apply {
        style = Typo.NOTE
        minWidth = 34.0
        alignment = Pos.CENTER_RIGHT
    }

    init {
        alignment = Pos.CENTER_LEFT
        HBox.setHgrow(name, Priority.ALWAYS)
        children.setAll(name, status, bar, percent)
    }

    fun update() {
        val progress = stage.progress
        bar.progress = progress
        percent.text = "${(progress * 100).toInt()}%"
        status.text = when {
            stage.status == "完成" -> ""
            stage.status == "失败" -> "失败"
            stage.stepTotal > 0 -> "${stage.stepDone}/${stage.stepTotal}"
            else -> stage.status
        }
        status.style = if (stage.status == "失败") {
            "-fx-font-size: 12px; -fx-text-fill: #dc2626;"
        } else {
            Typo.NOTE
        }
    }
}

/**
 * 一个社区资源安装记录。模组之类只有「一个文件下完就完了」，所以只需要一根进度条和落点。
 */
private class AssetRow(private val entry: InstallLog.Entry) : VBox(6.0) {

    private val title = Label(entry.title).apply { style = Typo.SECTION }
    private val state = Label().apply { style = Typo.NOTE }
    private val bar = ProgressBar(0.0).apply {
        maxWidth = Double.MAX_VALUE
        prefHeight = 6.0
    }
    private val percent = Label().apply {
        style = Typo.NOTE
        minWidth = 40.0
        alignment = Pos.CENTER_RIGHT
    }
    private val detail = Label().apply {
        style = Typo.NOTE
        isWrapText = true
    }

    init {
        val head = HBox(
            8.0,
            tag(entry.kind.label, project = false),
            title,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            state
        ).apply { alignment = Pos.CENTER_LEFT }

        val barRow = HBox(8.0, bar, percent).apply { alignment = Pos.CENTER_LEFT }
        HBox.setHgrow(bar, Priority.ALWAYS)

        children.setAll(head, barRow, detail)
        padding = Insets(12.0)
        style = CARD_STYLE
    }

    fun update() {
        bar.progress = entry.progress
        percent.text = "${(entry.progress * 100).toInt()}%"
        state.text = when (entry.state) {
            InstallLog.State.RUNNING -> "正在安装"
            InstallLog.State.DONE -> "已完成"
            InstallLog.State.FAILED -> "失败"
        }
        state.style = when (entry.state) {
            InstallLog.State.DONE -> "-fx-font-size: 12px; -fx-text-fill: #16a34a;"
            InstallLog.State.FAILED -> "-fx-font-size: 12px; -fx-text-fill: #dc2626;"
            else -> Typo.NOTE
        }
        detail.text = buildString {
            append(entry.fileName).append(" → ").append(entry.target.parentFile?.absolutePath.orEmpty())
            if (entry.state == InstallLog.State.FAILED && entry.message.isNotBlank()) {
                append(" · ").append(entry.message)
            }
        }
    }
}

private const val CARD_STYLE =
    "-fx-background-color: #ffffff; -fx-border-color: #d9d9de; -fx-border-width: 1;" +
            "-fx-border-radius: 10; -fx-background-radius: 10;"

private const val CANCEL_STYLE =
    "-fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
            "-fx-border-radius: 6; -fx-background-radius: 6;" +
            "-fx-text-fill: #52525b; -fx-font-size: 12px; -fx-padding: 3 10 3 10;" +
            "-fx-cursor: hand;"

private const val MORE_STYLE =
    "-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0;" +
            "-fx-text-fill: #6d28d9; -fx-font-size: 12px; -fx-padding: 0; -fx-cursor: hand;"

private const val FAIL_NOTE = "-fx-font-size: 12px; -fx-text-fill: #dc2626;"

private fun bytes(value: Long): String = when {
    value <= 0L -> "0 B"
    value < 1024L -> "$value B"
    value < 1024L * 1024 -> "%.1f KB".format(value / 1024.0)
    value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / 1048576.0)
    else -> "%.2f GB".format(value / 1073741824.0)
}
