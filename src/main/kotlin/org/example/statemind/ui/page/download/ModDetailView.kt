package org.example.statemind.ui.page.download

import java.awt.Desktop
import java.io.File
import java.net.URI
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import org.example.statemind.core.McVersion
import org.example.statemind.core.Prefs
import org.example.statemind.core.RecentInstall
import org.example.statemind.core.ResourceTarget
import org.example.statemind.core.fileNameOf
import org.example.statemind.core.mod.InstallLog
import org.example.statemind.core.mod.InstallTargets
import org.example.statemind.core.mod.ModInstaller
import org.example.statemind.core.mod.ModKind
import org.example.statemind.core.mod.Modrinth
import org.example.statemind.ui.Typo

/**
 * 社区内容详情页：项目的介绍、可用版本，以及「装哪一个」。
 *
 * 整页一个滚动条：介绍长了、版本多了都能一路读下去，不给介绍单独开一条横向滚动。
 * 按钮放在文字下面 —— 挤在右边时，长标题会把按钮压成省略号。
 *
 * 版本列表一次取全（`/project/{id}/version`），筛选在本地做 —— 换游戏版本 / 换加载器
 * 不该再等一轮网络。装到哪由 [InstallTargets] 与「设置 · 下载」决定。
 */
internal class ModDetailView(
    private val onBack: () -> Unit,
    private val gameDir: () -> File
) : VBox(10.0) {

    private var kind: ModKind = ModKind.MOD
    private var hit: Modrinth.Hit? = null
    private var versions: List<Modrinth.Version> = emptyList()
    private var worlds: List<File> = emptyList()

    /** 每次换项目 +1：晚回来的旧结果直接丢掉。 */
    private var generation = 0

    private val iconBox = StackPane()
    private val title = Label().apply {
        style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: ${DownloadStyles.TEXT};"
        isWrapText = true
        maxWidth = Double.MAX_VALUE
    }
    private val meta = Label().apply { style = Typo.NOTE }
    private val desc = Label().apply {
        style = Typo.DIM
        isWrapText = true
        maxWidth = Double.MAX_VALUE
    }

    private val openButton = Button("打开 Modrinth 页").apply {
        style = BTN_GHOST
        setOnAction { openInBrowser() }
    }
    private val copyButton = Button("复制名称").apply {
        style = BTN_GHOST
        setOnAction { copyTitle() }
    }

    private val versionBox = ComboBox<String>()
    private val loaderBox = ComboBox<String>()
    private val worldBox = ComboBox<String>()

    private val note = Label().apply {
        style = Typo.NOTE
        isWrapText = true
        maxWidth = Double.MAX_VALUE
        isVisible = false
        isManaged = false
        textProperty().addListener { _, _, now ->
            isVisible = now.isNotBlank()
            isManaged = now.isNotBlank()
        }
    }

    private val rows = VBox()
    private val scroll = ScrollPane()

    /** 存档那一列：只在装数据包时出现。声明在 init 之前 —— 属性按书写顺序初始化。 */
    private val worldCol: VBox = column("安装到存档", worldBox, 180.0)

    /** 加载器那一列：只有模组按加载器筛，别的整列收起。 */
    private val loaderCol: VBox = column("加载器", loaderBox, 160.0)

    init {
        val back = Button("返回").apply {
            style = BTN_GHOST
            setOnAction { onBack() }
        }
        val backRow = HBox(back).apply { alignment = Pos.CENTER_LEFT }

        val info = VBox(4.0, title, meta, desc).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)
        // 按钮落在说明文字下面，不再跟标题抢横向空间
        val actions = HBox(8.0, openButton, copyButton).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 0.0, 0.0, 0.0)
        }
        info.children += actions

        val headerCard = HBox(14.0, iconBox, info).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(14.0)
            style = DownloadStyles.CARD
        }

        versionBox.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 8
            valueProperty().addListener { _, _, _ -> renderVersions() }
        }
        loaderBox.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 8
            valueProperty().addListener { _, _, _ -> renderVersions() }
        }
        worldBox.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 8
            valueProperty().addListener { _, _, _ -> renderVersions() }
        }

        val cols = listOf(
            column("游戏版本", versionBox, 200.0),
            loaderCol,
            worldCol
        )
        cols.forEach { HBox.setHgrow(it, Priority.ALWAYS) }
        val filterCard = HBox(12.0, *cols.toTypedArray()).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(12.0)
            style = DownloadStyles.CARD
        }

        scroll.apply {
            contentProperty().set(VBox(12.0, headerCard, filterCard, note, rows))
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = "-fx-background-color: transparent; -fx-background: transparent;" +
                    "-fx-border-color: transparent; -fx-padding: 0;"
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        // 每行自己带卡片边框：第一行圆上角、最后一行圆下角，中间只有左右两条线
        children.setAll(backRow, scroll)
    }

    // ---------- 对外 ----------

    fun show(kind: ModKind, hit: Modrinth.Hit, preferred: String?) {
        this.kind = kind
        this.hit = hit
        generation++
        val mine = generation

        title.text = hit.title
        meta.text = buildList {
            hit.author.takeIf { it.isNotBlank() }?.let(::add)
            add(downloadsText(hit.downloads))
        }.joinToString(" · ")
        desc.text = hit.description
        iconBox.children.setAll(ModIcons.view(hit.iconUrl, 56.0))

        loaderCol.isVisible = kind.filterByLoader
        loaderCol.isManaged = kind.filterByLoader
        worldCol.isVisible = kind.needsWorld
        worldCol.isManaged = kind.needsWorld

        versions = emptyList()
        rows.children.clear()
        note.text = ""
        scroll.vvalue = 0.0
        fillWorlds()
        resetFilters()

        Thread {
            val outcome = runCatching { Modrinth.project(hit.id) to Modrinth.versions(hit.id) }
            Platform.runLater {
                if (mine != generation) return@runLater
                outcome
                    .onSuccess { (p, list) ->
                        versions = list
                        fillFilters(p, preferred)
                        renderVersions()
                    }
                    .onFailure { note.text = "读取失败：${it.message ?: it}" }
            }
        }.apply { isDaemon = true; name = "modrinth-detail" }.start()
    }

    // ---------- 筛选 ----------

    private fun resetFilters() {
        versionBox.items.setAll(ALL)
        versionBox.value = ALL
        loaderBox.items.setAll(ALL_LOADERS)
        loaderBox.value = ALL_LOADERS
    }

    private fun fillWorlds() {
        worlds = InstallTargets.worlds(gameDir())
        worldBox.items.setAll(worlds.map { it.name })
        worldBox.value = worlds.firstOrNull()?.name
    }

    private fun fillFilters(project: Modrinth.Project, preferred: String?) {
        val gameVersions = project.gameVersions.distinct().sortedWith { a, b -> McVersion.compare(b, a) }
        versionBox.items.setAll(listOf(ALL) + gameVersions)
        versionBox.value = when {
            preferred != null && preferred in gameVersions -> preferred
            else -> ALL
        }

        val loaders = project.loaders.filter { ModLoaders.valueOf(ModLoaders.labelOf(it)) != null }
        loaderBox.items.setAll(listOf(ALL_LOADERS) + loaders.map { ModLoaders.labelOf(it) })
        // 刚装好的那个版本用的是什么加载器，就默认筛它 —— 跟游戏版本筛选同一个道理
        val recent = RecentInstall.loaderLabel
        loaderBox.value = if (recent != null && recent in loaderBox.items) recent else ALL_LOADERS
    }

    // ---------- 版本列表 ----------

    private fun renderVersions() {
        if (versions.isEmpty()) return
        val gv = versionBox.value?.takeIf { it != ALL }
        val loader = if (kind.filterByLoader) ModLoaders.valueOf(loaderBox.value) else null

        val filtered = versions.filter { v ->
            (gv == null || gv in v.gameVersions) &&
                    (loader == null || loader in v.loaders)
        }

        rows.children.clear()
        when {
            filtered.isEmpty() -> note.text = "没有符合筛选的版本，换一个游戏版本或加载器试试。"
            kind.needsWorld && worldBox.value.isNullOrBlank() ->
                note.text = if (worlds.isEmpty()) "这个游戏目录里还没有存档，先创建一个世界。" else "请选择要安装到的存档。"

            else -> note.text = ""
        }

        val shown = filtered.take(MAX_ROWS)
        shown.forEachIndexed { index, version -> rows.children += versionRow(version, index, shown.lastIndex) }
        // 版本太多的项目（几百个）全铺出来会让这页很长；筛一下游戏版本就能缩小范围
        if (filtered.size > MAX_ROWS) {
            note.text = "共 ${filtered.size} 个版本，只列出最近 $MAX_ROWS 个 —— 选一个游戏版本可以缩小范围。"
        }
    }

    private fun versionRow(version: Modrinth.Version, index: Int, last: Int): Region {
        val name = Label(version.name.ifBlank { version.number }).apply {
            style = "-fx-font-size: 13px; -fx-text-fill: ${DownloadStyles.TEXT};"
            maxWidth = Double.MAX_VALUE
            isWrapText = false
        }
        val file = version.files.firstOrNull()
        val metaText = buildList {
            if (version.gameVersions.isNotEmpty()) add(version.gameVersions.joinToString(", "))
            if (version.loaders.isNotEmpty()) add(ModLoaders.labelsOf(version.loaders))
            if (version.published.isNotBlank()) add(version.published)
            file?.let { if (it.size > 0) add(bytes(it.size)) }
        }.joinToString(" · ")
        val meta = Label(metaText).apply {
            style = Typo.NOTE
            maxWidth = Double.MAX_VALUE
            isWrapText = false
        }
        val info = VBox(2.0, name, meta).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        val canPick = file != null && !(kind.needsWorld && worldBox.value.isNullOrBlank())
        val download = Button("下载").apply {
            style = BTN_PRIMARY
            isDisable = !canPick
            if (file == null) tooltip = Tooltip("这个版本没有可下载的文件")
            setOnAction { file?.let { install(version, it) } }
        }

        val content = HBox(12.0, info, download).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 16.0, 10.0, 16.0)
        }

        val divider = Region().apply {
            minHeight = 1.0
            prefHeight = 1.0
            maxHeight = 1.0
            isVisible = index > 0
            isManaged = index > 0
            style = "-fx-background-color: ${DownloadStyles.CARD_DIVIDER};"
        }

        val base = if (index == last) DownloadStyles.CARD_BOTTOM else {
            if (index == 0) DownloadStyles.CARD_TOP else DownloadStyles.CARD_MIDDLE
        }
        val hover = base.replace(DownloadStyles.CARD_BG, DownloadStyles.HOVER_BG)

        return VBox(divider, content).apply {
            style = base
            setOnMouseEntered { style = hover }
            setOnMouseExited { style = base }
        }
    }

    // ---------- 安装 ----------

    private fun install(version: Modrinth.Version, file: Modrinth.File) {
        val project = hit ?: return
        if (kind.needsWorld && worldBox.value.isNullOrBlank()) {
            note.text = "请先选择要安装到的存档。"
            return
        }
        val dir = destination() ?: run {
            note.text = "已取消下载。"
            return
        }
        val outName = fileNameOf(Prefs.resourceNaming, project.title, file.fileName)
        val entry = InstallLog.begin(project.title, kind, outName, File(dir, outName), file.size)
        note.text = "正在下载 ${file.fileName}…"

        Thread {
            val outcome = runCatching { ModInstaller.install(dir, file, outName, entry) }
            Platform.runLater {
                outcome
                    .onSuccess { note.text = "已安装 ${it.name}" }
                    .onFailure { note.text = "安装失败：${it.message ?: it}" }
            }
        }.apply { isDaemon = true; name = "mod-install" }.start()
    }

    /**
     * 落点。「每次下载时选择」就当场弹系统的目录选择框 —— 只装一个模组时，
     * 放哪本来就是临时决定；上次选过的目录只用来定弹框的起始位置。
     */
    private fun destination(): File? {
        if (Prefs.resourceTarget != ResourceTarget.CUSTOM) {
            return InstallTargets.dirFor(kind, gameDir(), worldBox.value)
        }
        val chooser = DirectoryChooser().apply {
            title = "选择下载位置"
            initialDirectory = File(Prefs.resourceDir.trim()).takeIf { it.isDirectory } ?: gameDir()
        }
        val picked = chooser.showDialog(scene?.window) ?: return null
        Prefs.resourceDir = picked.absolutePath
        return picked
    }

    // ---------- 小动作 ----------

    private fun openInBrowser() {
        val project = hit ?: return
        val url = "https://modrinth.com/${kind.projectType}/${project.slug}"
        val opened = runCatching {
            if (!Desktop.isDesktopSupported()) return@runCatching false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching false
            desktop.browse(URI(url))
            true
        }.getOrDefault(false)
        if (!opened) note.text = "没能打开浏览器，可以手动访问 $url"
    }

    private fun copyTitle() {
        val project = hit ?: return
        val content = ClipboardContent().apply { putString(project.title) }
        Clipboard.getSystemClipboard().setContent(content)
        note.text = "已复制「${project.title}」"
    }

    // ---------- 渲染工具 ----------

    private fun column(caption: String, control: Region, width: Double): VBox =
        VBox(4.0, Label(caption).apply { style = Typo.NOTE }, control).apply {
            prefWidth = width
            maxWidth = Double.MAX_VALUE
            minWidth = 0.0
        }

    private fun downloadsText(count: Long): String = when {
        count >= 100_000_000 -> "%.1f 亿次下载".format(count / 100_000_000.0)
        count >= 10_000 -> "%.1f 万次下载".format(count / 10_000.0)
        else -> "$count 次下载"
    }

    private fun bytes(value: Long): String = when {
        value < 1024L -> "$value B"
        value < 1024L * 1024 -> "%.1f KB".format(value / 1024.0)
        else -> "%.1f MB".format(value / 1048576.0)
    }

    private companion object {

        const val ALL = "全部版本"
        const val ALL_LOADERS = "全部加载器"

        /** 一页铺太多行会让滚动变卡；社区项目动辄几百个版本。 */
        const val MAX_ROWS = 60

        const val BTN_GHOST =
            "-fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: #52525b; -fx-font-size: 13px; -fx-padding: 6 14 6 14;" +
                    "-fx-cursor: hand;"

        const val BTN_PRIMARY =
            "-fx-background-color: ${DownloadStyles.ACCENT}; -fx-background-radius: 8;" +
                    "-fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 5 16 5 16;" +
                    "-fx-cursor: hand;"
    }
}
