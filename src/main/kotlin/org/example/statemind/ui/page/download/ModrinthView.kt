package org.example.statemind.ui.page.download

import java.io.File
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.OverrunStyle
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.util.StringConverter
import org.example.statemind.core.McVersion
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.VersionGroups
import org.example.statemind.core.mod.McModNames
import org.example.statemind.core.mod.ModCache
import org.example.statemind.core.mod.ModKind
import org.example.statemind.core.mod.Modrinth
import org.example.statemind.ui.Typo

/**
 * 社区内容浏览：按 [ModKind] 搜 Modrinth，点一行进详情页挑版本。
 *
 * 整页放在同一个滚动条里 —— 顶部筛选跟着列表一起上移，列表才能一直滑到自己末尾。
 * 结果到底就自动接着取下一页，不用再点「加载更多」。
 *
 * 搜索在后台线程；界面线程只收结果。每次换类型 / 换筛选条件都会让 [generation] 加一，
 * 晚回来的旧结果按代数丢弃 —— 否则会出现「切到资源包，列表却填进模组」。
 *
 * 同一组条件的搜索结果缓存在内存里：来回切 tab 不该每次都重新问一遍服务器。
 */
internal class ModrinthView(
    private val gameDir: () -> File,
    private val onDetail: (Boolean) -> Unit = {}
) : StackPane() {

    private val versionBox = ComboBox<String>()
    private val loaderBox = ComboBox<String>()
    private val categoryBox = ComboBox<String>()
    private val sortBox = ComboBox<Modrinth.Sort>()

    /** 一行说明：搜索失败、没有结果、版本名对不上。平常不占位。 */
    private val hint = Label().apply {
        style = Typo.NOTE
        isWrapText = true
        isVisible = false
        isManaged = false
        textProperty().addListener { _, _, now ->
            isVisible = now.isNotBlank()
            isManaged = now.isNotBlank()
        }
    }

    private val countLabel = Label().apply { style = Typo.NOTE }

    /** 版本输入框自己的回话（「1.22 没有这一版」），跟搜索结果说明分开，免得刚说就被下一次刷新抹掉。 */
    private val filterHint = Label().apply {
        style = Typo.NOTE
        isWrapText = true
        isVisible = false
        isManaged = false
        textProperty().addListener { _, _, now ->
            isVisible = now.isNotBlank()
            isManaged = now.isNotBlank()
        }
    }

    private val rowsCard = VBox()
    private val scroll = ScrollPane()
    private val browse = StackPane()

    private val busyLabel = Label("搜索中").apply { style = Typo.NOTE }
    private val busy = VBox(
        12.0,
        ProgressIndicator().apply {
            prefWidth = 28.0
            prefHeight = 28.0
            maxWidth = 28.0
            maxHeight = 28.0
            style = "-fx-progress-color: ${DownloadStyles.ACCENT};"
        },
        busyLabel
    ).apply {
        alignment = Pos.CENTER
        isVisible = false
        isMouseTransparent = true
    }

    /** 加载器那一列：资源包 / 光影不按加载器筛，整列收起。 */
    private val loaderCol: VBox = column("加载器", loaderBox, 150.0)

    /** 分类那一列：选项跟着内容类型换（Modrinth 每个类型的分类词表都不一样）。 */
    private val categoryCol: VBox = column("分类", categoryBox, 160.0)

    private val detail = ModDetailView({ showBrowse() }, gameDir)

    private var kind: ModKind = ModKind.MOD
    private var query = ""

    /** 实际发给 Modrinth 的词。中文搜索时它是翻译过来的英文名。 */
    private var term = ""
    private var termFor = ""

    /** 「钠 → Sodium」这类说明，挂在结果数后面。 */
    private var translationNote = ""
    private var translating = false

    /** 版本清单里全部正式版的版本号，新的在前。换内容类型时按下限重滤一遍。 */
    private var releases: List<String> = emptyList()

    /** 全量正式版（不过滤常见版本），只用来把当前选中的冷门版本补回筛选列表。 */
    private var allReleases: List<String> = emptyList()

    private var offset = 0
    private var total = 0
    private var loading = false

    /** 翻页失败过：别再让「滑到底」反复重试同一个地址，等用户换条件再来。 */
    private var exhausted = false
    private var generation = 0
    private var inFlight: String? = null

    /** 预取队列只跑一条：翻页时不该叠出好几条线程同时打接口。 */
    private var prefetching = false

    private val hits = ArrayList<Modrinth.Hit>()
    private val cache = HashMap<String, Modrinth.SearchResult>()

    /** 「最近装好的版本」或首页选中的游戏版本；用户手动改过筛选之后就不再跟它走。 */
    private var preferred: String? = null
    private var followDefault = true
    private var applying = false

    init {
        versionBox.apply {
            style = DownloadStyles.FIELD
            isEditable = true
            maxWidth = Double.MAX_VALUE
            // 弹层别一口气铺一屏：条目一多，下拉会盖住整个列表
            visibleRowCount = 8
            converter = object : StringConverter<String>() {
                override fun toString(value: String?): String = value.orEmpty()
                override fun fromString(text: String?): String? = text?.trim().orEmpty()
            }
            setCellFactory { optionCell { it } }
            buttonCell = optionCell { it }
            valueProperty().addListener { _, _, _ ->
                // 默认跟随只在「程序自己设的值」上生效；用户点选/打字都算他的选择
                if (applying) return@addListener
                followDefault = false
                reload()
            }
            setOnAction { commitTypedVersion() }
            // ComboBox 的 editor 在皮肤建立前是个占位文本框、之后可能被换掉，
            // 所以既挂当前这个，也挂属性变化的通知，换哪一份都跟着。
            watchEditor(editor)
            editorProperty().addListener { _, _, now -> watchEditor(now) }
        }
        loaderBox.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 8
            items.setAll(ModLoaders.PAIRS.map { it.first })
            value = ModLoaders.PAIRS.first().first
            setCellFactory { optionCell { it } }
            buttonCell = optionCell { it }
            valueProperty().addListener { _, _, _ -> if (kind.filterByLoader) reload() }
        }
        categoryBox.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 10
            items.setAll(listOf(ALL_CATEGORIES))
            value = ALL_CATEGORIES
            // 值存 slug，显示用中文名
            setCellFactory { optionCell { label -> if (label == ALL_CATEGORIES) label else ModCategories.zh(label) } }
            buttonCell = optionCell { label -> if (label == ALL_CATEGORIES) label else ModCategories.zh(label) }
            valueProperty().addListener { _, _, _ -> reload() }
        }
        sortBox.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 8
            items.setAll(Modrinth.Sort.entries)
            value = Modrinth.Sort.RELEVANCE
            setCellFactory { optionCell { it.label } }
            buttonCell = optionCell { it.label }
            valueProperty().addListener { _, _, _ -> reload() }
        }

        // 四个筛选并排，窄了换到下一行 —— 硬挤会把下拉里的字压成一截省略号
        val filterCard = FlowPane(12.0, 10.0).apply {
            padding = Insets(12.0)
            style = DownloadStyles.CARD
            children.setAll(
                column("游戏版本", versionBox, 240.0),
                loaderCol,
                categoryCol,
                column("排序", sortBox, 150.0)
            )
        }

        // 行是直接铺在卡片里的：没有 ListView 的虚拟化，整页滚起来才连贯
        val cut = Rectangle().apply { arcWidth = 20.0; arcHeight = 20.0 }
        cut.widthProperty().bind(rowsCard.widthProperty())
        cut.heightProperty().bind(rowsCard.heightProperty())
        rowsCard.clip = cut

        val footer = HBox(10.0, countLabel).apply { alignment = Pos.CENTER_LEFT }

        val content = VBox(10.0, filterCard, filterHint, hint, rowsCard, footer)
        scroll.apply {
            contentProperty().set(content)
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = "-fx-background-color: transparent; -fx-background: transparent;" +
                    "-fx-border-color: transparent; -fx-padding: 0;"
            VBox.setVgrow(this, Priority.ALWAYS)
            // 到底就继续取下一页
            vvalueProperty().addListener { _, _, now -> if (now.toDouble() >= 0.995) load(reset = false) }
        }

        browse.children.setAll(scroll, busy)
        children.setAll(browse, detail)
        detail.isVisible = false
    }

    // ---------- 对外 ----------

    /** 版本下拉的内容来自原版版本清单；只放正式版，且只留常见的那些（见 [McVersion.commonVersions]）。 */
    fun setVersions(versions: List<VersionEntry>) {
        allReleases = versions
            .filter { VersionGroups.groupOf(it) == VersionGroups.Group.RELEASE }
            .map { it.id }
        releases = McVersion.commonVersions(allReleases, preferred)
        rebuildVersionItems()
        applyPreferred()
    }

    /**
     * 「默认跟哪个游戏版本走」。用户动过筛选之后就不再改它 —— 那之后他想看什么由他自己定。
     * 重开启动器时才恢复成「跟着最近装好的 / 首页选中的版本」。
     */
    fun setPreferred(mcVersion: String?) {
        val changed = preferred != mcVersion
        preferred = mcVersion
        if (!followDefault || !changed) return
        // 选中的版本可能不在常见表里（老整合包常用的 1.16.5 之类），补一项再应用
        rebuildVersionItems()
        applyPreferred()
    }

    fun show(kind: ModKind) {
        this.kind = kind
        loaderCol.isVisible = kind.filterByLoader
        loaderCol.isManaged = kind.filterByLoader
        query = ""
        term = ""
        termFor = ""
        translationNote = ""
        hint.text = ""
        filterHint.text = ""
        // 分类词表跟着类型换；换完回到「全部分类」，别把模组的分类带去搜资源包
        categoryBox.value = ALL_CATEGORIES
        categoryBox.items.setAll(listOf(ALL_CATEGORIES) + ModCategories.choices(kind))
        rebuildVersionItems()
        showBrowse()
        reload()
    }

    fun setQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        translationNote = ""
        reload()
    }

    // ---------- 版本筛选 ----------

    /**
     * 只留下这一类内容真有可能存在的版本。`1.0` 到 `1.6` 之间既没有加载器也没有数据包格式，
     * 留在筛选里只会让人点出一片空列表。
     */
    private fun rebuildVersionItems() {
        val usable = releases.filter { McVersion.atLeast(it, kind.minGameVersion) }.toMutableList()
        preferred?.let { if (it in allReleases && it !in usable) usable += it }
        val labels = listOf(ALL_VERSIONS) + usable
        applying = true
        versionBox.items.setAll(labels)
        if (versionBox.value !in labels) versionBox.value = labels.getOrNull(1) ?: ALL_VERSIONS
        versionBox.editor?.text = versionBox.value.orEmpty()
        applying = false
    }

    private fun applyPreferred() {
        val mc = preferred ?: return
        if (mc !in versionBox.items) return
        if (versionBox.value == mc) return
        applying = true
        versionBox.value = mc
        versionBox.editor?.text = mc
        applying = false
        reload()
    }

    /**
     * 输入框里可能是「1.21」「1。21」「 1.21.1 」「1.22」这类东西：
     * 统一标点与空格后先找原样匹配，再按前缀补全成最新的那一版，都对不上就回到全部版本。
     */
    private fun resolveVersion(input: String): String? {
        val known = versionBox.items.filter { it != ALL_VERSIONS }
        val text = input.trim()
            .replace('。', '.')
            .replace('，', '.')
            .replace(',', '.')
            .replace(" ", "")
            .removePrefix("v")
            .removePrefix("V")
        if (text.isEmpty() || text.contains("全部")) return ALL_VERSIONS
        if (text in known) return text
        val sameLine = known.filter { it.startsWith("$text.") }
        return sameLine.maxWithOrNull(Comparator { a, b -> McVersion.compare(a, b) })
    }

    private fun commitTypedVersion() {
        val field = versionBox.editor ?: return
        val raw = field.text.trim()
        // 没打字、或只是从下拉里点了一项：编辑器里的字本来就等于当前值，不用再解析
        if (raw.isEmpty() || raw == versionBox.value) {
            field.text = versionBox.value.orEmpty()
            return
        }
        val resolved = resolveVersion(raw)
        if (resolved == null) {
            applyVersion(ALL_VERSIONS)
            filterHint.text = "没有「$raw」这个版本，已回到全部版本"
            return
        }
        filterHint.text = ""
        if (resolved == versionBox.value) {
            // 补全过的写法要回填，不然框里还留着一半的「1.21」
            field.text = resolved
            return
        }
        applyVersion(resolved)
    }

    /**
     * 输入框换人（皮肤重建）时重新挂一次失焦提交。
     *
     * 顺手把 editor 自带的底与框清掉：可编辑 ComboBox 会给内部文本框再画一层边框，
     * 叠在 ComboBox 自己的圆角框里就像「两个框套在一起」，高度也多出一截。
     */
    private fun watchEditor(field: TextField?) {
        field ?: return
        field.style = "-fx-background-color: transparent; -fx-border-color: transparent;" +
                "-fx-background-insets: 0; -fx-padding: 0 2 0 2;"
        field.focusedProperty().addListener { _, _, focused -> if (!focused) commitTypedVersion() }
    }

    private fun applyVersion(value: String) {
        applying = true
        versionBox.value = value
        versionBox.editor?.text = value
        applying = false
        followDefault = false
        reload()
    }

    private fun gameVersionOf(): String? {
        val value = versionBox.value ?: return null
        return value.takeIf { it != ALL_VERSIONS && it in versionBox.items }
    }

    // ---------- 两屏之间 ----------

    private fun openDetail(hit: Modrinth.Hit) {
        detail.show(kind, hit, gameVersionOf())
        browse.isVisible = false
        detail.isVisible = true
        // 详情页自己带着返回与标题，外层的类型栏与搜索框先让位
        onDetail(true)
    }

    private fun showBrowse() {
        detail.isVisible = false
        browse.isVisible = true
        onDetail(false)
    }

    // ---------- 搜索 ----------

    private fun reload() = load(reset = true)

    /**
     * 中文关键词先换成英文再搜。
     *
     * Modrinth 只认英文标题，PCL 能搜中文靠的是它自带的对照表；这里换成现查 MC 百科。
     * 换不出来（或是纯英文）就照原样搜，至少不会比现在更差。
     */
    private fun resolveTerm(): String? {
        if (!needsTranslation(query)) {
            termFor = query
            term = query
            return term
        }
        if (termFor == query) return term
        if (translating) return null

        translating = true
        setBusy(true)
        busyLabel.text = "正在按中文名查找「$query」"
        val wanted = query
        Thread {
            val english = runCatching { McModNames.englishOf(wanted) }.getOrNull()
            Platform.runLater {
                translating = false
                // 这期间用户又改了词：这次的结果作废，让他自己那一轮去查
                if (wanted != query) {
                    reload()
                    return@runLater
                }
                termFor = wanted
                term = english ?: wanted
                translationNote = if (english != null && english != wanted) "$wanted → $english" else ""
                load(reset = true)
            }
        }.apply { isDaemon = true; name = "mcmod-search" }.start()
        return null
    }

    private fun load(reset: Boolean) {
        if (!reset && (loading || exhausted || offset >= total)) return
        if (reset) {
            offset = 0
            hits.clear()
            total = 0
            exhausted = false
            rowsCard.children.clear()
            countLabel.text = ""
            // 旧结果不能再顶着新条件展示，否则「换筛选后列表没变」看着像没响应
            hint.text = ""
            scroll.vvalue = 0.0
        }

        val searchTerm = resolveTerm() ?: return
        val gameVersion = gameVersionOf()
        val loader = if (kind.filterByLoader) ModLoaders.valueOf(loaderBox.value) else null
        val category = categoryBox.value?.takeIf { it != ALL_CATEGORIES }
        val sort = sortBox.value ?: Modrinth.Sort.RELEVANCE
        val from = offset
        val key = "$kind|$searchTerm|$gameVersion|$loader|$category|$sort|$from"

        // 同一条件下已经有一份在跑：让它回来就是，别再发一份一模一样的
        if (key == inFlight) return
        inFlight = key

        generation++
        val mine = generation
        loading = true
        setBusy(hits.isEmpty())

        val cached = cache[key]
        if (cached != null) {
            loading = false
            inFlight = null
            applyResult(cached)
            return
        }

        Thread {
            val outcome = runCatching {
                Modrinth.search(kind, searchTerm, gameVersion, loader, category, sort, from)
            }
            Platform.runLater {
                if (inFlight == key) inFlight = null
                if (mine != generation) return@runLater
                loading = false
                outcome
                    .onSuccess {
                        // 条件组合会越攒越多，超了整批丢掉重来；单条缓存不值几个字节
                        if (cache.size > 60) cache.clear()
                        cache[key] = it
                        applyResult(it)
                    }
                    .onFailure {
                        setBusy(false)
                        if (hits.isNotEmpty()) exhausted = true
                        hint.text = "搜索失败：${it.message ?: it}"
                    }
            }
        }.apply { isDaemon = true; name = "modrinth-search" }.start()
    }

    private fun applyResult(result: Modrinth.SearchResult) {
        setBusy(false)
        // 同一页重复出现同一个项目时只留一条：翻页时服务器偶尔会抖
        val seen = hits.mapTo(HashSet()) { it.id }
        var added = 0
        for (hit in result.hits) {
            if (!seen.add(hit.id)) continue
            hits += hit
            added++
        }
        total = if (result.hits.isEmpty()) hits.size else result.total
        offset = hits.size

        renderRows()
        hint.text = when {
            hits.isEmpty() && query.isNotBlank() -> "没有找到「$query」"
            hits.isEmpty() -> "没有匹配的内容"
            else -> ""
        }
        countLabel.text = buildString {
            if (total > 0) append("共 $total 个结果")
            if (translationNote.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("按译名搜索 ").append(translationNote)
            }
        }

        // 一屏还没铺满（大屏、或某个筛选下结果很少）就接着取，否则用户没得滑也就没有下一页
        if (added > 0 && hits.size < total) {
            Platform.runLater {
                val viewport = scroll.viewportBounds.height
                if (viewport > 0 && scroll.content.layoutBounds.height <= viewport + 1) load(reset = false)
            }
        }
        // 列表已经出来了，顺手把前几个项目的版本取回来 —— 点进详情页就是现成的
        if (added > 0) prefetchTop()
    }

    private fun setBusy(on: Boolean) {
        busy.isVisible = on
        if (on) busyLabel.text = if (query.isBlank()) "正在加载" else "正在搜索「$query」"
    }

    /**
     * 页面一铺好就在后台预取前几个项目的版本清单。
     *
     * 一条线程串着取、每条之间歇一下：几十个项目的版本一次并发打过去，既容易被限流，
     * 也抢掉用户正在滚动时那一屏的带宽。已经取过的项目直接跳过。
     */
    private fun prefetchTop() {
        if (prefetching) return
        val targets = hits.take(PREFETCH).map { it.id }.filter { ModCache.versions(it) == null }
        if (targets.isEmpty()) return
        prefetching = true
        Thread {
            try {
                for (id in targets) {
                    runCatching { ModCache.prefetch(id) }
                    runCatching { Thread.sleep(120) }
                }
            } finally {
                prefetching = false
            }
        }.apply { isDaemon = true; name = "mod-prefetch" }.start()
    }

    // ---------- 渲染 ----------

    private fun renderRows() {
        val last = hits.lastIndex
        rowsCard.children.setAll(*hits.mapIndexed { index, hit -> rowNode(hit, index, last) }.toTypedArray())
    }

    private fun column(caption: String, control: Region, width: Double): VBox =
        VBox(4.0, Label(caption).apply { style = Typo.NOTE }, control).apply {
            // 宽度写死：里面的下拉框跟着定宽，字才不会被压成省略号；放不下由外面换行
            prefWidth = width
            minWidth = width
            maxWidth = width
        }

    private fun <T> optionCell(render: (T) -> String): ListCell<T> = object : ListCell<T>() {
        override fun updateItem(item: T?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else render(item)
        }
    }

    private fun rowNode(hit: Modrinth.Hit, index: Int, last: Int): Region {
        val title = Label(hit.title).apply {
            style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: ${DownloadStyles.TEXT};"
        }
        val slug = Label(hit.slug).apply { style = Typo.NOTE }
        val titleRow = HBox(8.0, title, slug).apply { alignment = Pos.CENTER_LEFT }

        val desc = Label(hit.description).apply {
            style = Typo.DIM
            maxWidth = Double.MAX_VALUE
            isWrapText = false
            textOverrun = OverrunStyle.ELLIPSIS
        }

        val meta = Label(
            buildList {
                ModCategories.text(hit.categories).takeIf { it.isNotBlank() }?.let(::add)
                add(downloadsText(hit.downloads))
                hit.author.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ")
        ).apply { style = Typo.NOTE }

        val info = VBox(3.0, titleRow, desc, meta).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        val content = HBox(12.0, ModIcons.view(hit.iconUrl, 48.0), info).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 16.0, 10.0, 16.0)
        }

        val base = when {
            index == 0 && index == last -> DownloadStyles.CARD
            index == last -> DownloadStyles.CARD_BOTTOM
            index == 0 -> DownloadStyles.CARD_TOP
            else -> DownloadStyles.CARD_MIDDLE
        }
        val hover = base.replace(DownloadStyles.CARD_BG, DownloadStyles.HOVER_BG)

        return VBox(content, divider(visible = index != last)).apply {
            style = base
            cursor = Cursor.HAND
            setOnMouseEntered { style = hover }
            setOnMouseExited { style = base }
            setOnMouseClicked { openDetail(hit) }
        }
    }

    private fun divider(visible: Boolean): Region = Region().apply {
        minHeight = 1.0
        prefHeight = 1.0
        maxHeight = 1.0
        isVisible = visible
        isManaged = visible
        style = "-fx-background-color: ${DownloadStyles.CARD_DIVIDER};"
    }

    private fun downloadsText(count: Long): String = when {
        count >= 100_000_000 -> "%.1f 亿次下载".format(count / 100_000_000.0)
        count >= 10_000 -> "%.1f 万次下载".format(count / 10_000.0)
        else -> "$count 次下载"
    }

    private companion object {
        const val ALL_VERSIONS = "全部版本"
        const val ALL_CATEGORIES = "全部分类"

        /** 列表出来后预取几个项目的版本 —— 一屏能看见的那几个。 */
        const val PREFETCH = 8

        /** 含中日韩统一表意文字就是中文关键词，需要先换成英文。 */
        fun needsTranslation(text: String): Boolean = text.any { it.code >= 0x2E80 }
    }
}
