package org.example.statemind.ui.page.download

import java.awt.Desktop
import java.io.File
import java.net.URI
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Polygon
import javafx.scene.shape.StrokeLineCap
import javafx.stage.DirectoryChooser
import org.example.statemind.core.McVersion
import org.example.statemind.core.Prefs
import org.example.statemind.core.ResourceTarget
import org.example.statemind.core.fileNameOf
import org.example.statemind.core.mod.InstallLog
import org.example.statemind.core.mod.InstallTargets
import org.example.statemind.core.mod.McModNames
import org.example.statemind.core.mod.ModCache
import org.example.statemind.core.mod.ModInstaller
import org.example.statemind.core.mod.ModKind
import org.example.statemind.core.mod.ModNames
import org.example.statemind.core.mod.Modrinth
import org.example.statemind.ui.NavIcons
import org.example.statemind.ui.Typo

/**
 * 社区内容详情页：项目的介绍、可用版本，以及「装哪一个」。
 *
 * 整页一个滚动条：介绍长了、版本多了都能一路读下去，不给介绍单独开一条横向滚动。
 * 头部照 PCL 的排版：图标 + 标题 + 描述 + 提示信息挤成一列压在左上角，
 * 操作按钮另起一行排在下面。返回除了左上角那颗叉号，还有一枚文字「返回」，
 * 免得有人眼尖不到叉在哪。
 *
 * 版本列表一次取全（`/project/{id}/version`），筛选在本地做 —— 换游戏版本
 * 不该再等一轮网络。列表页会预先取回前几个项目的版本，点进来多数时候是现成的。
 */
internal class ModDetailView(
    private val onBack: () -> Unit,
    private val gameDir: () -> File
) : VBox(10.0) {

    private var kind: ModKind = ModKind.MOD
    private var hit: Modrinth.Hit? = null
    private var versions: List<Modrinth.Version> = emptyList()

    /** 每次换项目 +1：晚回来的旧结果直接丢掉。 */
    private var generation = 0

    /** 每次重排版本列表 +1：上一轮还没铺完的分批渲染据此停下。 */
    private var renderGeneration = 0

    /** 展开着的分组。默认空 = 全部收起，跟 PCL 的版本列表一致。 */
    private val expandedGroups = HashSet<String>()

    /** 这个项目的中文名。内置表命中就是它，否则等 MC 百科查回来再填。 */
    private var zhName: String? = null

    /** 点「也有数据包版本」时要切到哪一类。 */
    private var switchKind: ModKind? = null

    /** 项目详情里的更新时间（`2026-11-12` 这种）；搜索结果不给，得单独问一次。 */
    private var projectUpdated: String? = null

    /** 项目支持哪些加载器。同上，来自项目详情。 */
    private var projectLoaders: List<String> = emptyList()

    /** 项目支持哪些游戏版本。同上，来自项目详情。 */
    private var projectGameVersions: List<String> = emptyList()

    /** 提示条被关掉 / 已经点过切换 —— 同一次打开里不再冒出来。 */
    private var switchDismissed = false

    private var preferredVersion: String? = null

    /**
     * 同一个项目常常既是模组又是数据包（Sodium 这类纯客户端模组除外）。
     * 单独一条卡片摆出来，点文字切过去，点叉关掉。
     */
    private val switchText = Label().apply {
        style = "-fx-font-size: 12px; -fx-text-fill: ${DownloadStyles.ACCENT_TEXT}; -fx-cursor: hand;"
        tooltip = Tooltip("切换到另一个形态的版本列表")
        setOnMouseClicked { openOtherKind() }
    }

    /** 关掉这条提示。下次重新打开这个项目还会再出现一次。 */
    private val switchClose: Button = glyphButton("关闭提醒") {
        switchDismissed = true
        paintSwitchBar()
    }

    private val switchBar: HBox = HBox(
        10.0,
        switchText,
        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
        switchClose
    ).apply {
        alignment = Pos.CENTER_LEFT
        padding = Insets(6.0, 8.0, 6.0, 12.0)
        style = "-fx-background-color: ${DownloadStyles.ACCENT_BG};" +
                "-fx-background-radius: 8; -fx-border-radius: 8;"
        isVisible = false
        isManaged = false
    }

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
        // 一行放不下就省略，不折到第二行 —— 折下去会把简介挤走
        isWrapText = false
        textOverrun = OverrunStyle.ELLIPSIS
        maxWidth = 420.0
        minWidth = 0.0
    }
    /** 作者名。PCL 那行没有，但这是项目归属，丢了就得回去列表页找。 */
    private val authorLabel = Label().apply { style = Typo.NOTE }

    /**
     * 简介。**只占一行**，放不下就省略号 —— PCL 就是这样，
     * 折成两行会把下面那排元信息顶下去，页面反而变长。
     *
     * [maxWidth] 必须是有限值：Label 只有在宽度真的不够时才画省略号，
     * 给成「无限宽」的话它会按文字全宽要空间，永远不会截断。
     * [minWidth] 给 0：默认的最小宽度是文字全宽，不放开的话它会反过来把标题挤出卡片。
     */
    private val desc = Label().apply {
        style = Typo.DIM
        isWrapText = false
        textOverrun = OverrunStyle.ELLIPSIS
        maxWidth = 620.0
        minWidth = 0.0
    }

    /** 分类小标签（PCL 在简介前面挂的那一串）。 */
    private val catTag = Label().apply {
        style = "-fx-font-size: 12px; -fx-text-fill: ${DownloadStyles.ACCENT_TEXT};" +
                "-fx-background-color: ${DownloadStyles.ACCENT_BG};" +
                "-fx-background-radius: 4; -fx-padding: 1 6 1 6;"
        isVisible = false
        isManaged = false
    }

    /** 那一排元信息：支持版本 / 下载量 / 最近更新 / 平台，每项前面一个小图标。 */
    private val metaRow = HBox(14.0)

    private val openButton = Button("转到 Modrinth").apply {
        style = BTN_GHOST
        setOnAction { openInBrowser() }
    }
    private val copyButton = Button("复制名称").apply {
        style = BTN_GHOST
        tooltip = Tooltip("复制项目的英文原名")
        setOnAction { copyTitle() }
    }

    /** 文字「返回」：给不认图标的人留一条路。跟叉号按钮做的是同一件事。 */
    private val backButton = Button("返回").apply {
        style = BTN_GHOST
        setOnAction { onBack() }
    }

    /**
     * 左上角那颗叉号。
     *
     * PCL 的详情页没有返回按钮（它是弹窗），我们有 —— 摆在标题左边会多出一条窄槽，
     * 挤在图标和文字之间又别扭。这里把整块内容往右挪一点，留出一条窄槽放它。
     */
    private val crossButton: Button = Button().apply {
        val icon = NavIcons.cross(10.0, Color.web(DownloadStyles.TEXT_DIM))
        graphic = icon.node
        contentDisplay = ContentDisplay.GRAPHIC_ONLY
        tooltip = Tooltip("返回")
        prefWidth = 16.0
        prefHeight = 16.0
        minWidth = 16.0
        minHeight = 16.0
        maxWidth = 16.0
        maxHeight = 16.0
        style = ICON_BTN
        setOnMouseEntered {
            style = ICON_BTN_HOVER
            icon.color = Color.web(DownloadStyles.TEXT)
        }
        setOnMouseExited {
            style = ICON_BTN
            icon.color = Color.web(DownloadStyles.TEXT_DIM)
        }
        setOnAction { onBack() }
    }

    /** 当前选中的版本系列；null = 全部。 */
    private var series: String? = null

    /**
     * 「全部 / 26.3 / 1.21 …」这一排横按钮。
     *
     * 用按钮而不是下拉：一个项目支持的版本系列就那么十几个，摊开来看得见全部，
     * 也不用先点开再选。窄窗口会自动换行。
     */
    private val chipsBox = FlowPane().apply {
        hgap = 6.0
        vgap = 6.0
        alignment = Pos.CENTER_LEFT
    }

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

    /** 版本清单还没到时的提示。盖在列表上，不参与排版。 */
    private val busyBox = VBox(
        10.0,
        ProgressIndicator().apply {
            prefWidth = 24.0
            prefHeight = 24.0
            maxWidth = 24.0
            maxHeight = 24.0
            style = "-fx-progress-color: ${DownloadStyles.ACCENT};"
        },
        Label("正在读取版本…").apply { style = Typo.NOTE }
    ).apply {
        alignment = Pos.CENTER
        isVisible = false
        isMouseTransparent = true
    }

    init {
        openButton.style = BTN_GHOST
        copyButton.style = BTN_GHOST
        backButton.style = BTN_GHOST

        // 左边留一条窄槽给叉号：图标和文字整体往右挪一点，不为它单开一行。
        // PCL 的详情页是弹窗、没有返回，我们有 —— 就塞在这条缝里。
        val slot = VBox(0.0, crossButton).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(2.0, 0.0, 0.0, 0.0)
        }

        // 标题一行：标题 + 作者。PCL 只有标题，作者跟在后面，不另起一行。
        val titleLine = HBox(8.0, title, authorLabel).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        title.minWidth = 0.0
        title.textOverrun = OverrunStyle.ELLIPSIS
        // 简介一行：分类小标签 + 简介（放不下省略号）。
        val descLine = HBox(8.0, catTag, desc).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
        }
        HBox.setHgrow(desc, Priority.ALWAYS)

        // 图标 + 三行字：标题 / 简介 / 元信息。挤成一列，像 PCL 那样整块压在左上角。
        val info = VBox(4.0, titleLine, descLine, metaRow).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        val identity = HBox(12.0, iconBox, info).apply {
            alignment = Pos.TOP_LEFT
            minWidth = 0.0
        }
        HBox.setHgrow(identity, Priority.ALWAYS)

        // 按钮排在卡片内部（PCL 就是跟在信息下面一排）。
        val actionRow = HBox(8.0, backButton, openButton, copyButton).apply {
            alignment = Pos.CENTER_LEFT
        }

        val headerCard = VBox(12.0, HBox(4.0, slot, identity), actionRow).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(14.0)
            style = DownloadStyles.CARD
        }

        val filterCard = VBox(8.0, chipsBox).apply {
            padding = Insets(10.0, 12.0, 10.0, 12.0)
            style = DownloadStyles.CARD
        }

        scroll.apply {
            contentProperty().set(
                VBox(12.0, headerCard, filterCard, switchBar, note, rows)
            )
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = "-fx-background-color: transparent; -fx-background: transparent;" +
                    "-fx-border-color: transparent; -fx-padding: 0;"
        }

        val body = StackPane(scroll, busyBox).apply { VBox.setVgrow(this, Priority.ALWAYS) }
        children.setAll(body)
    }

    // ---------- 对外 ----------

    /**
     * [switched] 为 true 表示这是从「也有模组/数据包版本」那条提示点过来的 ——
     * 那一次不再把同一条提示弹回来。
     */
    fun show(kind: ModKind, hit: Modrinth.Hit, preferred: String?, switched: Boolean = false) {
        this.kind = kind
        this.hit = hit
        this.preferredVersion = preferred
        this.switchDismissed = switched
        generation++
        renderGeneration++
        val mine = generation

        // 有中文译名就「译名 | 原名」，跟 PCL 一个样子；没有就只显示原名。
        // 内置表先顶一下，表里没有的项目再去 MC 百科问一次，回来补进标题
        val builtin = ModNames.zhOf(hit.slug, hit.title)
        zhName = builtin.takeIf { it != hit.title }
        applyTitle()
        if (zhName == null) lookupZh(hit, mine)
        authorLabel.text = hit.author
        desc.text = hit.description
        iconBox.children.setAll(ModIcons.view(hit.iconUrl, 44.0))
        paintCategory(hit)
        paintMeta(hit)

        versions = emptyList()
        rows.children.clear()
        expandedGroups.clear()
        note.text = ""
        scroll.vvalue = 0.0
        paintSwitchBar()
        skeletonChips()
        setBusy(true)

        // 搜索结果里没有加载器和更新时间，单独问一次项目详情。
        // 问不到也不碍事 —— 元信息那排少了这两项照样看。
        projectUpdated = null
        projectLoaders = emptyList()
        projectGameVersions = emptyList()
        Thread {
            val detail = runCatching { Modrinth.project(hit.id) }.getOrNull()
            Platform.runLater {
                if (mine != generation || detail == null) return@runLater
                projectUpdated = detail.updated
                projectLoaders = detail.loaders
                projectGameVersions = detail.gameVersions
                paintMeta(hit)
            }
        }.apply { isDaemon = true; name = "modrinth-project" }.start()

        // 列表页已经提前取过，多数时候这里是现成的，点进来不用等
        val ready = ModCache.versions(hit.id)
        if (ready != null) {
            setBusy(false)
            applyVersions(ready)
            return
        }

        Thread {
            val outcome = runCatching { ModCache.load(hit.id) }
            Platform.runLater {
                if (mine != generation) return@runLater
                setBusy(false)
                outcome
                    .onSuccess { applyVersions(it) }
                    .onFailure { note.text = "读取失败：${it.message ?: it}" }
            }
        }.apply { isDaemon = true; name = "modrinth-detail" }.start()
    }

    private fun applyVersions(list: List<Modrinth.Version>) {
        versions = list
        fillChips(list, preferredVersion)
        renderVersions()
    }

    private fun setBusy(on: Boolean) {
        busyBox.isVisible = on
    }

    // ---------- 筛选 ----------

    /**
     * 版本系列按钮还没算出来时先摆几个空壳，卡片高度不会先瘪下去再突然长高。
     * 宽度挑几个常见数字，看着就是「这里马上有东西」。
     */
    private fun skeletonChips() {
        chipsBox.children.setAll(
            *(SKELETON_WIDTHS.map { width ->
                Region().apply {
                    prefWidth = width
                    prefHeight = CHIP_HEIGHT
                    minHeight = CHIP_HEIGHT
                    maxHeight = CHIP_HEIGHT
                    style = "-fx-background-color: #f1f1f4; -fx-background-radius: 8;"
                }
            }).toTypedArray()
        )
    }

    private fun fillChips(list: List<Modrinth.Version>, preferred: String?) {
        val seriesList = list.asSequence()
            .flatMap { it.gameVersions.asSequence() }
            .filter { RELEASE_ID.matches(it) }
            .map { seriesOf(it) }
            .distinct()
            .sortedWith { a, b -> McVersion.compare(b, a) }
            .toList()

        // 首页 / 刚装好的那个版本如果在这个项目里，默认就筛它；否则停在「全部」
        series = preferred?.let { p -> seriesList.firstOrNull { it == seriesOf(p) } }

        chipsBox.children.setAll(chip("全部", null))
        seriesList.forEach { chipsBox.children += chip(it, it) }
        outlineChips()
    }

    private fun chip(label: String, value: String?): Button = Button(label).apply {
        style = CHIP_OFF
        minWidth = Region.USE_PREF_SIZE
        setOnAction {
            series = value
            outlineChips()
            renderVersions()
        }
    }

    private fun outlineChips() {
        chipsBox.children.filterIsInstance<Button>().forEach { button ->
            val on = if (button.text == "全部") series == null else button.text == series
            button.style = if (on) CHIP_ON else CHIP_OFF
        }
    }

    /**
     * 这个文件属不属于当前这一类内容。
     *
     * Modrinth 用 `loaders` 兼表内容形态：数据包写 `datapack`，资源包写 `minecraft`，
     * 光影写 iris / optifine / canvas。不按这个分，模组页里会混进同一个项目的数据包文件。
     */
    private fun belongsTo(kind: ModKind, version: Modrinth.Version): Boolean {
        if (version.loaders.isEmpty()) return true
        val allowed = when (kind) {
            ModKind.MOD, ModKind.MODPACK -> MOD_LOADERS
            ModKind.DATAPACK -> DATAPACK_LOADERS
            ModKind.RESOURCE_PACK -> RESOURCE_LOADERS
            ModKind.SHADER -> SHADER_LOADERS
        }
        return version.loaders.any { it in allowed }
    }

    /** 同一条提示的显隐只在这里算。 */
    private fun paintSwitchBar() {
        val other = otherKind()
        val has = !switchDismissed && other != null && versions.any { belongsTo(other, it) }
        switchKind = other
        switchBar.isVisible = has
        switchBar.isManaged = has
        if (has) {
            switchText.text =
                if (other == ModKind.DATAPACK) "这个项目也有数据包版本，点此查看" else "这个项目也有模组版本，点此查看"
        }
    }

    private fun otherKind(): ModKind? = when (kind) {
        ModKind.MOD -> ModKind.DATAPACK
        ModKind.DATAPACK -> ModKind.MOD
        else -> null
    }

    /** 点提示条：这条立刻消失，再换到另一类内容。 */
    private fun openOtherKind() {
        val target = switchKind ?: return
        val project = hit ?: return
        switchDismissed = true
        paintSwitchBar()
        show(target, project, preferredVersion, switched = true)
    }

    // ---------- 版本列表 ----------

    private fun renderVersions() {
        paintSwitchBar()
        if (versions.isEmpty()) return
        val chosen = series
        val filtered = versions.filter { v ->
            // 模组页别把同一个项目的数据包文件也列出来，反之亦然
            belongsTo(kind, v) &&
                    (chosen == null || v.gameVersions.any { RELEASE_ID.matches(it) && seriesOf(it) == chosen })
        }

        rows.children.clear()
        note.text = if (filtered.isEmpty()) "这个版本系列下没有文件，换一个看看。" else ""

        // 一个项目动辄上千个版本，全量建节点会卡住界面；分批往事件队列里塞，
        // 每批之间留一次重绘的机会，滚动条照样能一路滑到底。
        renderGeneration++
        appendGroups(filtered)
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

        val download = Button("下载").apply {
            style = BTN_PRIMARY
            isDisable = file == null
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

    /** 一个只有叉的小按钮，给提示条用。 */
    private fun glyphButton(tip: String, action: () -> Unit): Button {
        val size = 12.0
        val group = Group(
            Line(0.0, 0.0, size, size),
            Line(size, 0.0, 0.0, size)
        ).apply {
            children.forEach { (it as Line).apply { stroke = Color.web(DownloadStyles.ACCENT_TEXT); strokeWidth = 1.4; strokeLineCap = StrokeLineCap.ROUND } }
        }
        return Button("", group).apply {
            tooltip = Tooltip(tip)
            prefWidth = 22.0
            prefHeight = 22.0
            minWidth = 22.0
            minHeight = 22.0
            style = "-fx-background-color: transparent; -fx-background-radius: 6; -fx-padding: 0; -fx-cursor: hand;"
            setOnMouseEntered { style = "-fx-background-color: #ffffff88; -fx-background-radius: 6; -fx-padding: 0; -fx-cursor: hand;" }
            setOnMouseExited { style = "-fx-background-color: transparent; -fx-background-radius: 6; -fx-padding: 0; -fx-cursor: hand;" }
            setOnAction { action() }
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
            return InstallTargets.dirFor(kind, gameDir())
        }
        val chooser = DirectoryChooser().apply {
            title = "选择下载位置"
            // 起点先落到「首页选中那个版本」的 mods / shaderpacks / resourcepacks，
            // 版本隔离之后文件就该进那里；上次手选过的目录只在它不存在时兜底
            initialDirectory = InstallTargets.dirFor(kind, gameDir())
                .takeIf { it.isDirectory }
                ?: File(Prefs.resourceDir.trim()).takeIf { it.isDirectory }
                ?: gameDir()
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

    /**
     * 分类小标签。
     *
     * Modrinth 的分类里混着加载器名（fabric / neoforge）和项目类型，
     * 这些不是分类 —— 只留真正的功能分类（性能优化、装饰…）。
     */
    private fun paintCategory(hit: Modrinth.Hit) {
        val tag = hit.categories.firstOrNull { ModCategories.isFunction(it) }
        catTag.text = tag?.let { ModCategories.zh(it) }.orEmpty()
        catTag.isVisible = tag != null
        catTag.isManaged = tag != null
    }

    /**
     * 元信息那一排：支持版本 / 下载量 / 最近更新 / 平台。
     *
     * 跟 PCL 一样一排摆开，每项前面一个小图标。
     * 加载器和更新日期要项目详情才给全（搜索结果里没有），所以先摆已有的，
     * 详情回来再补 —— 补的过程界面上不闪。
     */
    private fun paintMeta(hit: Modrinth.Hit) {
        val items = ArrayList<javafx.scene.Node>()

        val support = supportText(hit)
        if (support.isNotBlank()) {
            items += metaItem(
                NavIcons.packageIcon(13.0, Color.web(DownloadStyles.TEXT_DIM)),
                support
            )
        }
        items += metaItem(
            NavIcons.download(13.0, Color.web(DownloadStyles.TEXT_DIM)),
            downloadsText(hit.downloads)
        )
        projectUpdated?.let {
            items += metaItem(
                NavIcons.clock(13.0, Color.web(DownloadStyles.TEXT_DIM)),
                relativeDate(it)
            )
        }
        items += metaItem(
            NavIcons.globe(13.0, Color.web(DownloadStyles.TEXT_DIM)),
            "Modrinth"
        )

        metaRow.children.setAll(*items.toTypedArray())
    }

    /**
     * 「支持版本」那一项：加载器 + 最低游戏版本，跟 PCL 的写法一致。
     *
     * PCL 写「Fabric / NeoForge 1.16+」—— 加载器全列出来，后面跟一个
     * 「最低支持的正式版 +」，表示往上都支持。
     */
    private fun supportText(hit: Modrinth.Hit): String {
        val loaders = if (projectLoaders.isNotEmpty()) projectLoaders else hit.categories.filter { it in LOADER_SLUGS }
        val loaderText = ModLoaders.labelsOf(loaders)
        val allVersions = if (projectGameVersions.isNotEmpty()) projectGameVersions else hit.gameVersions
        val releases = allVersions.filter { RELEASE_ID.matches(it) }
            .sortedWith { a, b -> McVersion.compare(a, b) }
        val min = releases.firstOrNull()
        return when {
            loaderText.isBlank() && min == null -> ""
            min == null -> loaderText
            loaderText.isBlank() -> "$min+"
            else -> "$loaderText $min+"
        }
    }

    /** 元信息里的一项：一个小图标 + 一行字。 */
    private fun metaItem(icon: NavIcons.Icon, text: String): HBox = HBox(
        5.0,
        icon.node,
        Label(text).apply { style = Typo.NOTE }
    ).apply {
        alignment = Pos.CENTER_LEFT
    }

    /**
     * 「8 天前」这种写法。
     *
     * Modrinth 的 updated 只给到日期，比不出来具体多久 —— 说「8 天前」是按天算的。
     * 超过一年就直接写日期，写「一年前」没意思。
     */
    private fun relativeDate(date: String): String {
        val parsed = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return date
        val days = java.time.Duration.between(parsed, java.time.LocalDate.now()).toDays()
        return when {
            days <= 0L -> "今天更新"
            days < 30L -> "$days 天前更新"
            days < 365L -> "${days / 30} 个月前更新"
            else -> date
        }
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

        /** 分组标题一次铺多少个。 */
        const val GROUP_BATCH = 30

        /** 空壳按钮的宽度与高度，跟真的 chip 一样高。 */
        val SKELETON_WIDTHS = listOf(46.0, 58.0, 52.0, 62.0, 48.0)
        const val CHIP_HEIGHT = 24.0

        /**
         * Modrinth 只给游戏版本号、没有「正式 / 快照」标记，只能看形状：
         * 纯数字点分的（1.21.1、26.3）是正式版，带字母或后缀的（26w14a、26.4-snapshot-10）不是。
         */
        private val RELEASE_ID = Regex("^\\d+(\\.\\d+)*$")

        private fun isPreview(mc: String): Boolean = !RELEASE_ID.matches(mc)

        /** 1.21.11 → 1.21，26.1.2 → 26.1；本来就是两段的原样返回。 */
        private fun seriesOf(mc: String): String {
            val parts = mc.split('.')
            return if (parts.size <= 2) mc else parts.take(2).joinToString(".")
        }

        /** 同一批游戏版本下，加载器的先后。 */
        private val LOADER_ORDER = listOf("Fabric", "Quilt", "Forge", "NeoForge")

        /** Modrinth 分类维度里的加载器值 —— 这些是加载器名，不是功能分类。 */
        private val LOADER_SLUGS = setOf("fabric", "quilt", "forge", "neoforge")

        /** Modrinth 的 `loaders` 值按内容形态分组，用来把同一个项目里的模组文件与数据包文件分开。 */
        private val MOD_LOADERS = setOf("fabric", "quilt", "forge", "neoforge")
        private val DATAPACK_LOADERS = setOf("datapack")
        private val RESOURCE_LOADERS = setOf("minecraft")
        private val SHADER_LOADERS = setOf("iris", "optifine", "canvas")

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

        /** 只有图标的按钮（叉号）：默认透明，悬停才给个底。 */
        const val ICON_BTN =
            "-fx-background-color: transparent; -fx-background-radius: 6;" +
                    "-fx-padding: 0; -fx-cursor: hand;"

        const val ICON_BTN_HOVER =
            "-fx-background-color: #f0f0f4; -fx-background-radius: 6;" +
                    "-fx-padding: 0; -fx-cursor: hand;"

        const val BTN_GHOST =
            "-fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: #52525b; -fx-font-size: 14px; -fx-padding: 9 18 9 18;" +
                    "-fx-cursor: hand;"

        /** 版本系列按钮。选中的用强调色，未选中的跟普通按钮一个样子。 */
        const val CHIP_OFF =
            "-fx-background-color: #ffffff; -fx-border-color: #d9d9de; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: ${DownloadStyles.TEXT_DIM}; -fx-font-size: 12px;" +
                    "-fx-padding: 3 11 3 11; -fx-cursor: hand;"

        const val CHIP_ON =
            "-fx-background-color: ${DownloadStyles.ACCENT_BG};" +
                    "-fx-border-color: ${DownloadStyles.ACCENT_LINE}; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: ${DownloadStyles.ACCENT_TEXT}; -fx-font-size: 12px;" +
                    "-fx-font-weight: bold; -fx-padding: 3 11 3 11; -fx-cursor: hand;"

        const val BTN_PRIMARY =
            "-fx-background-color: ${DownloadStyles.ACCENT}; -fx-background-radius: 8;" +
                    "-fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 5 16 5 16;" +
                    "-fx-cursor: hand;"
    }
}
