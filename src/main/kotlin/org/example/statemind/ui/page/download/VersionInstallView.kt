package org.example.statemind.ui.page.download

import java.io.File
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.loader.LoaderChoice
import org.example.statemind.core.loader.LoaderKind
import org.example.statemind.core.loader.LoaderSource
import org.example.statemind.core.loader.LoaderVersion
import org.example.statemind.core.mod.FabricApi
import org.example.statemind.core.mod.Modrinth
import org.example.statemind.ui.NavIcons
import org.example.statemind.ui.Typo

/**
 * 选好游戏版本之后那一页：装不装加载器、装哪个、这个版本叫什么名字。
 *
 * 加载器一行一个直接摆出来。**原版就是一行都不选**，不再单独占一行写「原版」——
 * 之前把它当成一种可选加载器，进来只看得见一行「模组加载器 原版」，既没说有什么可选，
 * 也让人以为原版是个需要挑的东西。
 *
 * 选中一行后展开的是**这张卡自己**：版本下拉长在行下面，卡片变高，页面上不多出别的行。
 * Fabric / Quilt 选完版本再往下接一块 Fabric API，跟版本下拉同宽同左边界。
 * 其余几行留在原地，标「与 X 不兼容」—— 一个实例只能挂一个加载器。
 *
 * 这个游戏版本根本没有某个加载器的安装包时（老版本、快照），那一行直接不出现，
 * 而不是留一条写着「无」的空行。
 *
 * 顶上的输入框就是这个版本的文件夹名：默认按「游戏版本-加载器 加载器版本」填好，可以改。
 *
 * 左下角必须标出下载源 —— BMCLAPI 的条款要求下载界面说明来源。
 */
internal class VersionInstallView(
    private val onBack: () -> Unit,
    private val onStart: () -> Unit,
    /** 下载落点：首页选中版本所在的那份游戏目录。改名查重、写 json 都按它算。 */
    private val root: () -> File
) : BorderPane() {

    /**
     * 一行加载器 = 一整张卡片。卡片里面是标题行 + 展开区，
     * 选中之后往下长的是这张卡自己，页面上不会多出别的行。
     */
    private class Row(
        val card: VBox,
        val head: HBox,
        val name: Label,
        val state: Label,
        val expand: VBox,
        val combo: ComboBox<LoaderVersion>
    )

    private val startButton = Button("开始下载").apply {
        style = BTN_PRIMARY
        setOnAction { onStart() }
    }

    private val nameField = TextField()
    private val nameHint = Label()
    private val rowsBox = VBox(8.0)
    private val rows = LinkedHashMap<LoaderKind, Row>()
    private val loaderNote = Label()
    private val sourceNote = Label()

    private val apiCombo = ComboBox<Modrinth.Version>()

    /** Fabric API 那一块：跟版本下拉同宽同左边界，挂在选中那张卡片的展开区里。 */
    private val apiRow = VBox(6.0)

    /** API 清单还在查时的那句话。 */
    private val apiNote = Label("正在读取 Fabric API…").apply { style = Typo.NOTE }

    /** 这个游戏版本下查到的加载器版本表；null = 还没查过。 */
    private val versions = HashMap<LoaderKind, List<LoaderVersion>>()

    /** 清单没读回来的加载器。不留着这个，右边会一直写「获取中……」。 */
    private val failed = HashSet<LoaderKind>()

    /** null = 原版（不装加载器）。 */
    private var selected: LoaderKind? = null

    /** 程序自己往控件里填值时置真：那不是用户的动作。 */
    private var filling = false

    /** 用户改过版本名之后就不再跟着加载器自动改，免得把他刚敲的字冲掉。 */
    private var nameEdited = false

    private var apiReady = false
    private var apiMc: String? = null

    /** Fabric API 列表正在查。查完之前选了 Fabric / Quilt 也不能开始下载。 */
    private var apiLoading = false

    /** 查过但没查到（网络失败 / 这个版本没有配套的 API）。此时不该卡住下载。 */
    private var apiFailed = false

    /** 每次换加载器 +1：晚回来的旧结果丢掉。 */
    private var generation = 0

    /** 加载器可用性的批次号。跟 [generation] 分开：点一行不该让「这版能用哪些加载器」作废。 */
    private var availabilityGen = 0

    private var apiGeneration = 0

    /** 这一页正在准备下载哪个版本。 */
    var current: VersionEntry? = null
        private set

    /** 选中的加载器；原版为 null。 */
    var choice: LoaderChoice? = null
        private set

    /** 下载后的版本名，也就是 `versions/` 下的文件夹名。 */
    val versionName: String get() = nameField.text.trim()

    /**
     * 会一起装上的 Fabric API 版本 id；原版 / 以后接上的 Forge 为 null。
     *
     * Fabric 生态里几乎每个模组都依赖它，所以默认就装，只给「换一个版本」、不给「不装」。
     */
    val fabricApiVersionId: String?
        get() = if (selected == LoaderKind.FABRIC || selected == LoaderKind.QUILT) {
            apiCombo.value?.id
        } else null

    init {
        padding = Insets(DownloadStyles.PAGE_PADDING)

        nameField.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            tooltip = Tooltip("下载后的版本名，也是 versions 下的文件夹名。")
            textProperty().addListener { _, _, _ ->
                if (!filling) {
                    nameEdited = true
                    validate()
                }
            }
        }

        nameHint.apply {
            style = Typo.NOTE + "-fx-text-fill: $ERROR_TEXT;"
            // 固定占位：出现提示时下面的列表不整体往下跳，按钮也不会被顶走
            minHeight = HINT_HEIGHT
            prefHeight = HINT_HEIGHT
            maxHeight = HINT_HEIGHT
            isWrapText = true
        }

        val back = Button().apply {
            graphic = NavIcons.back(16.0, Color.web(DownloadStyles.TEXT)).node
            style = BACK_STYLE
            tooltip = Tooltip("返回版本列表")
            setOnAction { onBack() }
        }

        val topRow = HBox(10.0, back, nameField).apply { alignment = Pos.CENTER_LEFT }
        HBox.setHgrow(nameField, Priority.ALWAYS)

        LOADER_ORDER.forEach { kind ->
            rows[kind] = buildRow(kind)
            rowsBox.children += rows.getValue(kind).card
        }
        buildApiRow()

        loaderNote.style = Typo.NOTE
        loaderNote.isWrapText = true
        loaderNote.maxWidth = 460.0

        center = VBox(
            6.0,
            topRow,
            nameHint,
            Region().apply { minHeight = 8.0; prefHeight = 8.0; maxHeight = 8.0 },
            rowsBox,
            loaderNote
        ).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(18.0, 0.0, 0.0, 0.0)
        }

        sourceNote.style = Typo.NOTE + "-fx-text-fill: ${DownloadStyles.TEXT_DIM};"
        bottom = HBox(
            10.0, sourceNote,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            startButton
        ).apply { alignment = Pos.CENTER_LEFT }

        refreshUi()
    }

    fun show(entry: VersionEntry) {
        current = entry
        versions.clear()
        failed.clear()
        rows.values.forEach {
            it.combo.items.clear()
            it.combo.value = null
        }
        selected = null
        choice = null
        nameEdited = false
        apiReady = false
        apiMc = null
        apiLoading = false
        apiFailed = false
        apiGeneration++
        apiCombo.items.clear()
        apiCombo.value = null
        loaderNote.text = ""
        refreshUi()

        // 老版本、快照、愚人节版本多半只有一个加载器可装：先问清楚，
        // 别让人点进去才发现「没有可用版本」。答案进了 LoaderSource 的缓存。
        availabilityGen++
        checkLoaders(entry.id, availabilityGen)

        // Fabric API 的清单跟加载器清单互不相干，先查起来：
        // 等用户点完加载器再查，白白多等一轮网络。
        loadApi(entry.id)
    }

    fun setSource(label: String) {
        sourceNote.text = "下载源：$label"
    }

    // ---------- 行 ----------

    private fun buildRow(kind: LoaderKind): Row {
        val name = Label(kind.label)
        val state = Label().apply { style = Typo.NOTE }

        val head = HBox(
            10.0, name,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            state
        ).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(11.0, 14.0, 11.0, 14.0)
            minHeight = ROW_HEIGHT
            prefHeight = ROW_HEIGHT
            maxHeight = ROW_HEIGHT
        }

        val combo = ComboBox<LoaderVersion>().apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 8
            // 展开时才带「稳定版 / 测试版」；收起时只留版本号，跟 PCL 一样干净
            setCellFactory {
                object : ListCell<LoaderVersion>() {
                    override fun updateItem(item: LoaderVersion?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else "${item.id}    ${item.label}"
                    }
                }
            }
            buttonCell = object : ListCell<LoaderVersion>() {
                override fun updateItem(item: LoaderVersion?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.id
                }
            }
            valueProperty().addListener { _, _, now -> onLoaderVersion(now, kind) }
        }

        val expand = VBox(6.0, small("加载器版本"), combo).apply {
            padding = Insets(0.0, 14.0, 14.0, 14.0)
            isVisible = false
            isManaged = false
        }

        val card = VBox(head, expand).apply { style = ROW }

        if (kind.supported) {
            head.setOnMouseClicked { select(kind) }
            // 白底行跟灰行只差字体颜色，悬停不亮一下就看不出能点
            head.setOnMouseEntered { if (kind != selected && usable(kind)) card.style = ROW_HOVER }
            head.setOnMouseExited { if (kind != selected) card.style = cardStyle(kind) }
        } else {
            // 灰行也给个说法，不然只会看起来「坏了」
            Tooltip.install(head, Tooltip("暂不支持，需要用官方安装器改装，后续版本接上"))
        }

        return Row(card, head, name, state, expand, combo)
    }

    /**
     * Fabric API 那一块。选中 Fabric / Quilt 且列表到了才挂进展开区，
     * 默认挑这个游戏版本下最新的一个 —— 加载器装好了却没有前置，下一分钟就是一片模组加载失败。
     */
    private fun buildApiRow() {
        apiCombo.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 6
            setCellFactory {
                object : ListCell<Modrinth.Version>() {
                    override fun updateItem(item: Modrinth.Version?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else "${item.number.ifBlank { item.name }}    ${item.name}"
                    }
                }
            }
            buttonCell = object : ListCell<Modrinth.Version>() {
                override fun updateItem(item: Modrinth.Version?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.number.ifBlank { item.name }
                }
            }
        }
        apiRow.children.setAll(small("Fabric API"), apiCombo, apiNote)
        // 跟上面那个下拉之间多留一点，两块才看得出是分开的
        apiRow.padding = Insets(6.0, 0.0, 0.0, 0.0)
    }

    /** 读 API 清单时占一行字，读完就撤。 */
    private fun syncApiNote() {
        val show = apiLoading || apiFailed
        apiNote.isVisible = show
        apiNote.isManaged = show
        apiNote.text = if (apiFailed) "没读到 Fabric API 清单，这次下载不带它" else "正在读取 Fabric API…"
        // 没读到时下拉是空的，留着只会让人以为点不动
        apiCombo.isVisible = apiReady
        apiCombo.isManaged = apiReady
    }

    /** 展开区里的小标题。宽度由展开区统一给，它不占列宽，两个下拉因此左边界一样。 */
    private fun small(text: String): Label = Label(text).apply { style = Typo.DIM }

    /** 这一行现在能不能点：不支持的不行，这个版本下确实没有安装包的也不行。 */
    private fun usable(kind: LoaderKind): Boolean =
        kind.supported && versions[kind]?.isEmpty() != true

    /**
     * 右边那句灰字。已经装了一种加载器就直说「不兼容」——
     * 一个实例只能挂一个加载器，这比留白或者让人自己试要清楚。
     */
    private fun stateText(kind: LoaderKind): String {
        if (selected == kind) return ""
        selected?.let { return "与${it.label}不兼容" }
        return when {
            !kind.supported -> "暂不支持"
            versions[kind] == null -> if (kind in failed) "读取失败" else "获取中……"
            else -> ""
        }
    }

    // ---------- 刷新 ----------

    private fun refreshUi() {
        for ((kind, row) in rows) {
            val active = kind == selected
            val ok = usable(kind)
            // 这一版压根装不了这个加载器（老版本、快照）：整行收起来，不留一条写着「无」的空行
            val gone = versions[kind]?.isEmpty() == true
            row.card.isVisible = !gone
            row.card.isManaged = !gone
            row.card.style = cardStyle(kind)
            row.head.cursor = if (ok) Cursor.HAND else Cursor.DEFAULT
            val nameFill = when {
                active -> DownloadStyles.ACCENT_TEXT
                !ok -> DownloadStyles.TEXT_FAINT
                else -> DownloadStyles.TEXT
            }
            row.name.style = Typo.LABEL + "-fx-text-fill: $nameFill;"
            val stateFill = when {
                active -> DownloadStyles.ACCENT_TEXT
                !ok -> DownloadStyles.TEXT_FAINT
                else -> DownloadStyles.TEXT_DIM
            }
            row.state.text = stateText(kind)
            row.state.style = Typo.NOTE + "-fx-text-fill: $stateFill;"
        }
        syncExpansion()
        syncApiNote()
        syncName()
        validate()
    }

    private fun cardStyle(kind: LoaderKind): String = when {
        kind == selected -> ROW_ON
        !usable(kind) -> ROW_OFF
        else -> ROW
    }

    /**
     * 展开区只属于选中的那一行。API 那块同一时刻只能待在一张卡里 ——
     * 一个节点只能有一个父节点，所以先让别的卡片撒手，再挂上去。
     */
    private fun syncExpansion() {
        // 选了 Fabric / Quilt 就要挂出 API 那一块：查的过程中也挂着，
        // 用户看得见「正在读」而不是这块凭空消失。
        val showApi = (selected == LoaderKind.FABRIC || selected == LoaderKind.QUILT) &&
            (apiReady || apiLoading || apiFailed)
        val host = if (showApi) selected?.let { rows[it] }?.expand else null

        for ((kind, row) in rows) {
            val open = kind == selected
            row.expand.isVisible = open
            row.expand.isManaged = open
            if (row.expand !== host) row.expand.children.remove(apiRow)
        }
        if (host != null && !host.children.contains(apiRow)) host.children.add(apiRow)
    }

    private fun syncName() {
        if (nameEdited) return
        filling = true
        nameField.text = autoName()
        filling = false
    }

    /** 没改过名时按当前选择拼一个：原版是版本号，加载器版本是「版本号-加载器 加载器版本」。 */
    private fun autoName(): String {
        val entry = current ?: return ""
        val kind = selected ?: return freeName(entry.id)
        val version = rows[kind]?.combo?.value?.id ?: return freeName(entry.id)
        return freeName("${entry.id}-${kind.label} $version")
    }

    /** 同名目录已经在了就往后加序号 —— 下载新版本不该覆盖掉已经装好的那份。 */
    private fun freeName(base: String): String {
        if (!File(root(), "versions/$base").exists()) return base
        var index = 2
        while (File(root(), "versions/$base-$index").exists()) index++
        return "$base-$index"
    }

    private fun validate() {
        // 还没选游戏版本时这一页是藏着的，不必报「版本名为空」
        val problem = if (current == null) null else nameProblem()
        nameHint.text = problem.orEmpty()
        // 选了 Fabric / Quilt 就要顺带装 API：清单还没查完就先别让用户点开始，
        // 免得下到一半才发现少了个前置。
        val apiPending = apiLoading && (selected == LoaderKind.FABRIC || selected == LoaderKind.QUILT)
        startButton.isDisable =
            current == null || problem != null || (selected != null && choice == null) || apiPending
    }

    /**
     * 版本名要能直接当文件夹名用。这里挡的是系统层面的问题，
     * 「和已有版本重名」也算 —— 否则 [freeName] 之外的手改会悄悄覆盖别人。
     */
    private fun nameProblem(): String? {
        val name = versionName
        return when {
            name.isEmpty() -> "版本名不能为空。"
            name.length > NAME_MAX -> "版本名过长。"
            name.any { it in ILLEGAL_CHARS } -> "版本名含有文件名不允许的字符。"
            name.endsWith(".") || name.endsWith(" ") -> "版本名不能以点或空格结尾。"
            name.substringBefore('.').uppercase() in RESERVED_NAMES -> "这个名称不能用作版本名。"
            File(root(), "versions/$name").exists() -> "versions 下已有同名版本。"
            else -> null
        }
    }

    // ---------- 选择 ----------

    private fun select(kind: LoaderKind) {
        if (!usable(kind)) return

        // 再点一次选中的那一行 = 不装加载器（原版）
        val next = if (selected == kind) null else kind
        selected = next
        generation++
        choice = null
        loaderNote.text = ""

        val entry = current
        if (next == null || entry == null) {
            refreshUi()
            return
        }
        refreshUi()

        // 这一版之前查过就直接用：来回点不该每次都再等一轮网络
        val cached = versions[next] ?: LoaderSource.cached(next, entry.id)
        if (cached != null) {
            applyVersions(next, cached)
            return
        }

        val mine = generation
        Thread {
            val outcome = runCatching { LoaderSource.versions(next, entry.id) }
            Platform.runLater {
                if (mine != generation) return@runLater
                outcome
                    .onSuccess { applyVersions(next, it) }
                    .onFailure {
                        failed += next
                        loaderNote.text = "读取${next.label}版本失败：${it.message ?: "网络错误"}"
                        refreshUi()
                    }
            }
        }.apply { isDaemon = true; name = "loader-versions" }.start()
    }

    /** 把某一版的加载器版本填进下拉。空表 = 这个游戏版本确实没有对应安装包。 */
    private fun applyVersions(kind: LoaderKind, list: List<LoaderVersion>) {
        versions[kind] = list
        failed -= kind
        if (list.isEmpty()) {
            if (selected == kind) {
                selected = null
                choice = null
                loaderNote.text = "${current?.id.orEmpty()} 没有可用的${kind.label}版本"
            }
            refreshUi()
            return
        }

        val combo = rows[kind]?.combo
        filling = true
        combo?.items?.setAll(list)
        combo?.value = list.first()
        filling = false
        val entry = current
        if (selected == kind && entry != null && combo?.value != null) {
            choice = LoaderSource.choice(kind, entry.id, combo.value.id)
            // 只有 Fabric 系需要前置；LiteLoader 自己的库在它那份 json 里
            if (kind == LoaderKind.FABRIC || kind == LoaderKind.QUILT) loadApi(entry.id)
        }
        refreshUi()
    }

    /** 一次问清这个游戏版本能用哪些加载器，免得用户挨个点。 */
    private fun checkLoaders(mc: String, batch: Int) {
        val targets = LOADER_ORDER.filter { it.supported }
        val known = targets.mapNotNull { kind -> LoaderSource.cached(kind, mc)?.let { kind to it } }.toMap()
        if (known.isNotEmpty()) versions.putAll(known)
        val missing = targets.filter { versions[it] == null }
        if (missing.isEmpty()) {
            refreshUi()
            return
        }
        refreshUi()

        Thread {
            // 几份清单互不相干：串行问，慢网络上要等好几个超时；并行只等最慢的那一个
            val found = java.util.concurrent.ConcurrentHashMap<LoaderKind, List<LoaderVersion>>()
            val broken = java.util.concurrent.ConcurrentHashMap.newKeySet<LoaderKind>()
            val workers = missing.map { kind ->
                Thread {
                    runCatching { LoaderSource.versions(kind, mc) }
                        .onSuccess { found[kind] = it }
                        .onFailure { broken += kind }
                }.apply { isDaemon = true; start() }
            }
            workers.forEach { runCatching { it.join(30_000) } }
            Platform.runLater {
                // 期间换了版本，这批结果就作废
                if (batch != availabilityGen || current?.id != mc) return@runLater
                versions.putAll(found)
                failed += broken
                val waiting = selected
                // 正在等的那一行，结果回来是空的就退回原版
                if (waiting != null && versions[waiting]?.isEmpty() == true) {
                    selected = null
                    choice = null
                }
                refreshUi()
            }
        }.apply { isDaemon = true; name = "loader-check" }.start()
    }

    private fun onLoaderVersion(value: LoaderVersion?, kind: LoaderKind) {
        if (filling || value == null) return
        if (kind != selected) return
        val entry = current ?: return
        choice = LoaderSource.choice(kind, entry.id, value.id)
        refreshUi()
    }

    /** 按游戏版本查一次 API 列表，默认选最新那个。同一个版本查过就不再问。 */
    private fun loadApi(mc: String) {
        if (apiMc == mc && (apiCombo.items.isNotEmpty() || apiLoading || apiFailed)) return
        apiGeneration++
        val mine = apiGeneration
        apiMc = mc
        apiReady = false
        apiLoading = true
        apiFailed = false
        apiCombo.items.clear()
        apiCombo.value = null
        refreshUi()

        Thread {
            val outcome = runCatching { FabricApi.versionsFor(mc) }
            Platform.runLater {
                if (mine != apiGeneration) return@runLater
                val list = outcome.getOrDefault(emptyList())
                apiCombo.items.setAll(list)
                apiCombo.value = list.firstOrNull()
                apiReady = list.isNotEmpty()
                apiFailed = apiReady.not()
                apiLoading = false
                refreshUi()
            }
        }.apply { isDaemon = true; name = "fabric-api-versions" }.start()
    }

    private companion object {

        /** 顺序就是界面里的顺序：主流的放前面，被淘汰的 LiteLoader 放最后。 */
        val LOADER_ORDER = listOf(
            LoaderKind.FABRIC,
            LoaderKind.QUILT,
            LoaderKind.FORGE,
            LoaderKind.NEOFORGE,
            LoaderKind.OPTIFINE,
            LoaderKind.LITELOADER
        )

        const val ROW_HEIGHT = 44.0
        const val HINT_HEIGHT = 18.0
        const val NAME_MAX = 64

        /** 校验提示的红：比正红暗一点，摆在白底上不刺眼。 */
        const val ERROR_TEXT = "#c0392b"

        val ILLEGAL_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

        /** Windows 不允许的文件名，建目录会直接失败。 */
        val RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            for (i in 1..9) {
                add("COM$i")
                add("LPT$i")
            }
        }

        const val BACK_STYLE =
            "-fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 0;" +
                    "-fx-min-width: 36; -fx-pref-width: 36; -fx-max-width: 36;" +
                    "-fx-min-height: 36; -fx-pref-height: 36; -fx-max-height: 36;" +
                    "-fx-cursor: hand;"

        /** 跟弹窗里那个「确定」同一套。 */
        const val BTN_PRIMARY =
            "-fx-background-color: ${DownloadStyles.ACCENT}; -fx-background-radius: 8;" +
                    "-fx-text-fill: white; -fx-font-size: 13px; -fx-font-weight: bold;" +
                    "-fx-padding: 8 18 8 18; -fx-cursor: hand;"

        const val ROW =
            "-fx-background-color: #ffffff; -fx-border-color: #d9d9de; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;"

        const val ROW_ON =
            "-fx-background-color: ${DownloadStyles.ACCENT_BG};" +
                    "-fx-border-color: ${DownloadStyles.ACCENT_LINE}; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;"

        const val ROW_HOVER =
            "-fx-background-color: ${DownloadStyles.HOVER_BG}; -fx-border-color: #c9c9d1;" +
                    "-fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8;"

        const val ROW_OFF =
            "-fx-background-color: #fbfbfc; -fx-border-color: #e8e8ee; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;"
    }
}
