package org.example.statemind.ui.page.download

import java.awt.Desktop
import java.io.File
import java.net.URI
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
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
import javafx.scene.paint.Color
import javafx.scene.shape.Polygon
import javafx.stage.DirectoryChooser
import org.example.statemind.core.McVersion
import org.example.statemind.core.Prefs
import org.example.statemind.core.RecentInstall
import org.example.statemind.core.ResourceTarget
import org.example.statemind.core.fileNameOf
import org.example.statemind.core.mod.InstallLog
import org.example.statemind.core.mod.InstallTargets
import org.example.statemind.core.mod.McModNames
import org.example.statemind.core.mod.ModInstaller
import org.example.statemind.core.mod.ModKind
import org.example.statemind.core.mod.ModNames
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

    /** 每次重排版本列表 +1：上一轮还没铺完的分批渲染据此停下。 */
    private var renderGeneration = 0

    /** 展开着的分组。默认空 = 全部收起，跟 PCL 的版本列表一致。 */
    private val expandedGroups = HashSet<String>()

    /** 这个项目的中文名。内置表命中就是它，否则等 MC 百科查回来再填。 */
    private var zhName: String? = null

    /**
     * 一个「加载器 + 游戏版本」分组。
     *
     * [preview] 指快照这一类非正式发布，标题后面缀「预览版」—— 同一套加载器下 26.3 与
     * 26.3 的快照是两栏，不标出来会以为是重复项。
     */
    private data class GroupRow(
        val key: String,
        val loaderLabel: String,
        val mc: String,
        val preview: Boolean,
        val versions: List<Modrinth.Version>
    )

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
        renderGeneration++
        val mine = generation

        // 有中文译名就「译名 | 原名」，跟 PCL 一个样子；没有就只显示原名。
        // 内置表先顶一下，表里没有的项目再去 MC 百科问一次，回来补进标题
        val builtin = ModNames.zhOf(hit.slug, hit.title)
        zhName = builtin.takeIf { it != hit.title }
        applyTitle()
        if (zhName == null) lookupZh(hit, mine)
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
        expandedGroups.clear()
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

        val shown = filtered
        rows.children.clear()
        // 一个项目动辄上千个版本，全量建节点会卡住界面；分批往事件队列里塞，
        // 每批之间留一次重绘的机会，滚动条照样能一路滑到底。
        renderGeneration++
        // 选了具体游戏版本就只有一个维度，平铺；「全部版本」按加载器 + 游戏版本分组
        if (gv != null) appendBatch(shown, 0, renderGeneration) else appendGroups(shown)
    }

    /**
     * 「全部版本」时按 **加载器 + 游戏版本** 归组，默认全部收起。
     *
     * 一个项目在 26.3 下同时有 Fabric / NeoForge 两套文件，平铺出来就是一长串
     * 「Sodium 0.9.3-beta.5 for NeoForge 26.3」，光看标题分不出这一行属于哪一套，
     * 还得每行再挂一串小字说明版本与加载器。分组之后这些信息都落在标题上。
     */
    private fun appendGroups(all: List<Modrinth.Version>) {
        val bucket = LinkedHashMap<String, MutableList<Modrinth.Version>>()
        for (version in all) {
            // 资源包 / 光影没有加载器这一维，只有游戏版本
            val loaders = version.loaders.ifEmpty { listOf("") }
            for (mc in version.gameVersions) {
                for (raw in loaders) {
                    bucket.getOrPut("$raw|$mc") { ArrayList() } += version
                }
            }
        }
        val groups = bucket.map { (key, list) ->
            val raw = key.substringBefore('|')
            val mc = key.substringAfter('|')
            GroupRow(
                key = key,
                loaderLabel = if (raw.isBlank()) "" else ModLoaders.labelOf(raw),
                mc = mc,
                preview = isPreview(mc),
                versions = list
            )
        }.sortedWith(GROUP_ORDER)

        appendGroupBatch(groups, 0, renderGeneration)
    }

    private fun appendGroupBatch(groups: List<GroupRow>, from: Int, generation: Int) {
        if (generation != renderGeneration) return
        val end = minOf(from + GROUP_BATCH, groups.size)
        for (index in from until end) rows.children += groupBlock(groups[index])
        if (end < groups.size) {
            Platform.runLater { appendGroupBatch(groups, end, generation) }
        }
    }

    private fun groupBlock(group: GroupRow): Region {
        val open = group.key in expandedGroups
        val title = buildString {
            if (group.loaderLabel.isNotBlank()) append(group.loaderLabel).append(' ')
            append(group.mc)
            if (group.preview) append(" 预览版")
        }
        val name = Label(title).apply {
            style = "-fx-font-size: 13px; -fx-text-fill: ${DownloadStyles.TEXT};"
        }
        val count = Label("${group.versions.size}").apply { style = Typo.NOTE }
        val base = if (open) DownloadStyles.CARD_TOP else DownloadStyles.CARD
        val hover = if (open) DownloadStyles.CARD_HOVER_TOP else DownloadStyles.CARD_HOVER
        val head = HBox(
            8.0, name, count,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            foldMark(open)
        ).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(11.0, 16.0, 11.0, 16.0)
            cursor = Cursor.HAND
            style = base
            setOnMouseEntered { style = hover }
            setOnMouseExited { style = base }
            setOnMouseClicked {
                if (!expandedGroups.remove(group.key)) expandedGroups += group.key
                renderVersions()
            }
        }

        val box = VBox(head)
        if (!open) return box
        group.versions.forEachIndexed { index, version ->
            box.children += groupItem(version, index, group.versions.lastIndex)
        }
        return box
    }

    /** 分组里的一行：标题已经写明加载器与游戏版本，这行只留文件本身的信息。 */
    private fun groupItem(version: Modrinth.Version, index: Int, last: Int): Region {
        val name = Label(version.name.ifBlank { version.number }).apply {
            style = "-fx-font-size: 13px; -fx-text-fill: ${DownloadStyles.TEXT};"
            maxWidth = Double.MAX_VALUE
        }
        val file = version.files.firstOrNull()
        val metaText = buildList {
            if (version.published.isNotBlank()) add(version.published)
            file?.let { if (it.size > 0) add(bytes(it.size)) }
        }.joinToString(" · ")
        val info = VBox(2.0, name, Label(metaText).apply { style = Typo.NOTE }).apply { minWidth = 0.0 }
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
            style = "-fx-background-color: ${DownloadStyles.CARD_DIVIDER};"
        }
        val base = if (index == last) DownloadStyles.CARD_BOTTOM else DownloadStyles.CARD_MIDDLE
        val hover = if (index == last) DownloadStyles.CARD_HOVER_BOTTOM else DownloadStyles.CARD_HOVER_MIDDLE
        return VBox(divider, content).apply {
            style = base
            setOnMouseEntered { style = hover }
            setOnMouseExited { style = base }
        }
    }

    /** 用矢量画折叠箭头：字符箭头在部分系统上缺字。 */
    private fun foldMark(open: Boolean): Polygon {
        val points = if (open) {
            doubleArrayOf(0.0, 0.0, 9.0, 0.0, 4.5, 5.0)
        } else {
            doubleArrayOf(0.0, 0.0, 5.0, 4.5, 0.0, 9.0)
        }
        return Polygon(*points).apply { fill = Color.web(DownloadStyles.TEXT_DIM) }
    }

    /**
     * 追加 [from] 之后的一批版本行。换筛选条件时 [renderGeneration] 会加一，
     * 上一轮还没跑完的批次在下一帧自己退出。
     */
    private fun appendBatch(all: List<Modrinth.Version>, from: Int, generation: Int) {
        if (generation != renderGeneration) return
        val end = minOf(from + RENDER_BATCH, all.size)
        for (index in from until end) {
            rows.children += versionRow(all[index], index, all.lastIndex)
        }
        if (end < all.size) {
            Platform.runLater { appendBatch(all, end, generation) }
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

    // ---------- 中文名 ----------

    private fun applyTitle() {
        val original = hit?.title ?: return
        title.text = zhName?.let { "$it | $original" } ?: original
    }

    /** 内置表没有的项目去 MC 百科查一次；查不到就继续用英文原名。 */
    private fun lookupZh(project: Modrinth.Hit, mine: Int) {
        Thread {
            val zh = McModNames.lookup(project.title) ?: return@Thread
            Platform.runLater {
                if (mine != generation) return@runLater
                zhName = zh
                applyTitle()
            }
        }.apply { isDaemon = true; name = "mcmod-name" }.start()
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
        // 文件名里那档「中文译名」用的就是这里：接口只给英文标题，译名得自己补
        val installName = zhName ?: ModNames.zhOf(project.slug, project.title)
        val outName = fileNameOf(Prefs.resourceNaming, installName, file.fileName)
        val entry = InstallLog.begin(installName, kind, outName, File(dir, outName), file.size)
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
            // 窗口窄时三列会被均分压缩，给个下限，别把字压没
            minWidth = 110.0
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

        /** 一次往列表里塞多少行。项目动辄上千个版本，全量一次建完会卡住界面。 */
        const val RENDER_BATCH = 80

        /** 分组标题一次铺多少个。 */
        const val GROUP_BATCH = 30

        /**
         * Modrinth 只给游戏版本号、没有「正式 / 快照」标记，只能看形状：
         * 纯数字点分的（1.21.1、26.3）是正式版，带字母或后缀的（26w14a、26.4-snapshot-10）不是。
         */
        private val RELEASE_ID = Regex("^\\d+(\\.\\d+)*$")

        private fun isPreview(mc: String): Boolean = !RELEASE_ID.matches(mc)

        /** 同一批游戏版本下，加载器的先后。 */
        private val LOADER_ORDER = listOf("Fabric", "Quilt", "Forge", "NeoForge")

        private fun loaderRank(label: String): Int = LOADER_ORDER.indexOf(label).let {
            if (it >= 0) it else LOADER_ORDER.size
        }

        /** 游戏版本新的在前；同一版本正式版排在预览版前。 */
        val GROUP_ORDER: Comparator<GroupRow> = Comparator { a, b ->
            val byMc = McVersion.compare(b.mc, a.mc)
            when {
                byMc != 0 -> byMc
                a.preview != b.preview -> if (a.preview) 1 else -1
                else -> loaderRank(a.loaderLabel) - loaderRank(b.loaderLabel)
            }
        }

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
