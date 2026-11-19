package org.example.statemind.ui.page.download

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.loader.LoaderChoice
import org.example.statemind.core.loader.LoaderKind
import org.example.statemind.core.loader.LoaderSource
import org.example.statemind.core.loader.LoaderVersion
import org.example.statemind.core.mod.FabricApi
import org.example.statemind.core.mod.Modrinth
import org.example.statemind.ui.Typo

/**
 * 选好原版版本之后的那一页：挑加载器，然后开始下载。
 *
 * 加载器排成竖排、一行一个，选中的那一行才展开版本下拉 —— 横着一排按钮既放不下
 * 「这个版本有没有它」的信息，窗口一窄还会被压成几个省略号。
 *
 * 每个游戏版本能用的加载器并不一样（1.0 一个都没有、1.14 起才有 Fabric、快照常常只有 Fabric），
 * 所以每行右侧直接写这个版本下的实际情况，没有安装包的行点不动。
 *
 * 左下角必须标出下载源 —— BMCLAPI 的条款要求下载界面说明来源。
 */
internal class VersionInstallView(
    private val onBack: () -> Unit,
    private val onStart: () -> Unit
) : BorderPane() {

    private val versionLabel = Label()
    private val sourceNote = Label()
    private val loaderNote = Label()
    private val rowsBox = VBox(8.0)

    /** 每一行里要随状态改的零件。 */
    private val rowBoxes = HashMap<LoaderKind, VBox>()
    private val nameLabels = HashMap<LoaderKind, Label>()
    private val stateLabels = HashMap<LoaderKind, Label>()
    private val expandBoxes = HashMap<LoaderKind, VBox>()
    private val comboBoxes = HashMap<LoaderKind, ComboBox<LoaderVersion>>()

    /** 这个游戏版本下查到的加载器版本表；null = 还没查过。 */
    private val versions = HashMap<LoaderKind, List<LoaderVersion>>()

    private var selected: LoaderKind = LoaderKind.VANILLA

    /** 程序自己往下拉里填值时置真：那不是用户的选择，别拿它去更新 [choice]。 */
    private var filling = false

    // ---------- Fabric API 那张卡片 ----------

    /** 选中 Fabric / Quilt 且加载器版本确定之后，这张卡片才出现。 */
    private val apiState = Label().apply { style = Typo.NOTE }
    private val apiCombo = ComboBox<Modrinth.Version>()
    private val apiExpand = VBox()
    private val apiRow = VBox()

    /** 卡片是否展开着。默认收起，只写当前选中的 API 版本号。 */
    private var apiOpen = false

    /** 每次换游戏版本 +1：晚回来的旧 API 列表丢掉。 */
    private var apiGeneration = 0

    private val startButton = Button("开始下载").apply {
        style = BTN_PRIMARY
        setOnAction { onStart() }
    }

    /** 这一页正在准备下载哪个版本。 */
    var current: VersionEntry? = null
        private set

    /** 选中的加载器；原版为 null。 */
    var choice: LoaderChoice? = null
        private set

    /**
     * 会一起装上的 Fabric API 版本 id；原版 / 以后接上的 Forge 为 null。
     *
     * Fabric 生态里几乎每个模组都依赖它，所以默认就装，只给「换一个版本」、不给「不装」。
     */
    val fabricApiVersionId: String?
        get() = if (selected == LoaderKind.FABRIC || selected == LoaderKind.QUILT) {
            apiCombo.value?.id
        } else null

    /** 每次换加载器 +1：后台线程回来的结果对不上就丢掉。 */
    private var generation = 0

    /** 加载器可用性的批次号。跟 [generation] 分开：点一行不该让「这版能用哪些加载器」作废。 */
    private var availabilityGen = 0

    init {
        padding = Insets(DownloadStyles.PAGE_PADDING)

        top = HBox(
            Button("返回").apply {
                style = BACK_STYLE
                setOnAction { onBack() }
            }
        ).apply { alignment = Pos.CENTER_LEFT }

        versionLabel.style =
            "-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: ${DownloadStyles.TEXT};"

        val loaderTitle = Label("模组加载器").apply { style = Typo.LABEL }

        LoaderKind.entries.forEach { rowsBox.children += loaderRow(it) }
        buildApiRow()

        loaderNote.style = Typo.NOTE
        loaderNote.isWrapText = true
        loaderNote.maxWidth = 460.0

        center = VBox(
            10.0,
            versionLabel,
            Region().apply { minHeight = 10.0; prefHeight = 10.0; maxHeight = 10.0 },
            loaderTitle,
            rowsBox,
            loaderNote
        ).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(18.0, 0.0, 0.0, 0.0)
        }

        sourceNote.style = "-fx-font-size: 12px; -fx-text-fill: ${DownloadStyles.TEXT_DIM};"
        bottom = HBox(
            10.0, sourceNote,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            startButton
        ).apply { alignment = Pos.CENTER_LEFT }

        refreshStyles()
    }

    fun show(entry: VersionEntry) {
        current = entry
        versionLabel.text = entry.id
        versions.clear()
        comboBoxes.forEach { (_, combo) ->
            combo.items.clear()
            combo.value = null
        }
        selected = LoaderKind.VANILLA
        choice = null
        loaderNote.text = ""
        startButton.isDisable = false
        // API 的列表是按游戏版本查的，换版本就得作废
        apiGeneration++
        apiCombo.items.clear()
        apiCombo.value = null
        apiState.text = ""
        openApi(false)
        apiRow.isVisible = false
        apiRow.isManaged = false
        refreshStyles()

        // 老版本、快照、愚人节版本多半只有一个加载器可装：先问清楚，
        // 别让人点进去才发现「没有可用版本」。答案进了 LoaderSource 的缓存。
        availabilityGen++
        checkLoaders(entry.id, availabilityGen)
    }

    fun setSource(label: String) {
        sourceNote.text = "下载源：$label"
    }

    // ---------- 行 ----------

    /**
     * Fabric API 那张卡片：一行标题 + 当前选中的版本号，点一下才展开版本下拉。
     *
     * 选了 Fabric 就自动挑这个游戏版本能用的最新一批 —— 加载器装好了却没有前置，
     * 用户下一分钟就会看到一片模组加载失败。想换旧版本可以展开下拉自己选。
     */
    private fun buildApiRow() {
        apiCombo.apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 6
            converter = object : StringConverter<Modrinth.Version>() {
                override fun toString(value: Modrinth.Version?): String =
                    value?.let { it.number.ifBlank { it.name } }.orEmpty()

                override fun fromString(text: String?): Modrinth.Version? = null
            }
            valueProperty().addListener { _, _, now ->
                if (now != null) apiState.text = now.number.ifBlank { now.name }
            }
        }
        val name = Label("Fabric API").apply {
            style = "-fx-font-size: 13px; -fx-text-fill: ${DownloadStyles.TEXT};"
        }
        val head = HBox(
            8.0, name,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            apiState
        ).apply {
            alignment = Pos.CENTER_LEFT
            cursor = Cursor.HAND
            setOnMouseClicked { openApi(!apiOpen) }
        }

        apiExpand.children.setAll(apiCombo)
        apiExpand.padding = Insets(6.0, 0.0, 0.0, 0.0)
        apiExpand.isVisible = false
        apiExpand.isManaged = false

        apiRow.children.setAll(head, apiExpand)
        apiRow.style = "-fx-background-color: #ffffff; -fx-border-color: #e4e4e9;" +
                "-fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8;"
        apiRow.padding = Insets(9.0, 10.0, 9.0, 10.0)
        apiRow.isVisible = false
        apiRow.isManaged = false
    }

    private fun openApi(open: Boolean) {
        apiOpen = open
        apiExpand.isVisible = open
        apiExpand.isManaged = open
    }

    /** 按游戏版本查一次 API 列表，默认选最新那个。 */
    private fun loadApi(mc: String) {
        apiGeneration++
        val mine = apiGeneration
        openApi(false)
        apiCombo.items.clear()
        apiCombo.value = null
        apiState.text = "读取中…"
        Thread {
            val list = runCatching { FabricApi.versionsFor(mc) }.getOrDefault(emptyList())
            Platform.runLater {
                if (mine != apiGeneration) return@runLater
                if (list.isEmpty()) {
                    apiState.text = "无"
                    apiRow.isVisible = false
                    apiRow.isManaged = false
                    return@runLater
                }
                apiCombo.items.setAll(list)
                apiCombo.value = list.first()
                refreshStyles()
            }
        }.apply { isDaemon = true; name = "fabric-api-versions" }.start()
    }

    private fun loaderRow(kind: LoaderKind): VBox {
        val name = Label(kind.label).apply {
            style = "-fx-font-size: 14px; -fx-text-fill: ${DownloadStyles.TEXT};"
        }
        val state = Label().apply { style = Typo.NOTE }

        val head = HBox(
            10.0, name,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            state
        ).apply { alignment = Pos.CENTER_LEFT }

        val combo = ComboBox<LoaderVersion>().apply {
            style = DownloadStyles.FIELD
            maxWidth = Double.MAX_VALUE
            visibleRowCount = 8
            // 下拉里连「稳定版 / 测试版」一起显示，跟 PCL 一样能一眼看出哪个是正式发布
            converter = object : StringConverter<LoaderVersion>() {
                override fun toString(value: LoaderVersion?): String =
                    value?.let { "${it.id}    ${it.label}" }.orEmpty()

                override fun fromString(text: String?): LoaderVersion? = null
            }
            valueProperty().addListener { _, _, now -> onLoaderVersion(now, kind) }
        }

        val expand = VBox(combo).apply {
            isVisible = false
            isManaged = false
            padding = Insets(8.0, 0.0, 0.0, 0.0)
        }

        val row = VBox(head, expand).apply {
            style = ROW
            padding = Insets(11.0, 14.0, 11.0, 14.0)
        }

        if (kind.supported) {
            head.setOnMouseClicked { select(kind) }
        } else {
            // 灰行也给个说法，不然只会看起来「坏了」
            Tooltip.install(row, Tooltip("暂不支持，需要用官方安装器改装，后续版本接上"))
        }

        rowBoxes[kind] = row
        nameLabels[kind] = name
        stateLabels[kind] = state
        expandBoxes[kind] = expand
        comboBoxes[kind] = combo
        return row
    }

    /** 这一行现在能不能点：不支持的不行，这个版本下确实没有安装包的也不行。 */
    private fun usable(kind: LoaderKind): Boolean = when {
        !kind.supported -> false
        kind == LoaderKind.VANILLA -> true
        else -> versions[kind]?.isNotEmpty() != false
    }

    private fun stateText(kind: LoaderKind): String = when {
        !kind.supported -> "暂不支持"
        kind == LoaderKind.VANILLA -> if (selected == kind) "使用中" else ""
        selected == kind -> "使用中"
        versions[kind] == null -> "读取中…"
        versions[kind]!!.isEmpty() -> "无"
        else -> "${versions[kind]!!.size} 个版本"
    }

    private fun refreshStyles() {
        for ((kind, row) in rowBoxes) {
            val on = kind == selected
            val ok = usable(kind)
            val fill = when {
                on -> DownloadStyles.ACCENT_TEXT
                !ok -> DownloadStyles.TEXT_FAINT
                else -> DownloadStyles.TEXT
            }
            row.style = when {
                on -> ROW_ON
                !ok -> ROW_OFF
                else -> ROW
            }
            row.cursor = if (ok) Cursor.HAND else Cursor.DEFAULT
            nameLabels[kind]?.style = "-fx-font-size: 14px; -fx-text-fill: $fill;"
            stateLabels[kind]?.apply {
                text = stateText(kind)
                style = Typo.NOTE + "-fx-text-fill: " +
                        (if (on) DownloadStyles.ACCENT_TEXT else DownloadStyles.TEXT_DIM) + ";"
            }
            val expand = expandBoxes[kind]
            val open = on && kind != LoaderKind.VANILLA && versions[kind]?.isNotEmpty() == true
            expand?.isVisible = open
            expand?.isManaged = open
            // Fabric 系那一行展开时，版本下拉下面多挂一张 Fabric API 卡片 ——
            // PCL 也是把它放在这一栏里，而不是单独摆在整页底下。切到原版就跟着一起收起。
            if (open && (kind == LoaderKind.FABRIC || kind == LoaderKind.QUILT)) {
                expand?.children?.setAll(comboBoxes[kind], apiRow)
            }
        }
        // 只在这一行选中、且 API 列表已经到了的时候露出来
        val showApi = (selected == LoaderKind.FABRIC || selected == LoaderKind.QUILT) &&
                apiCombo.items.isNotEmpty()
        apiRow.isVisible = showApi
        apiRow.isManaged = showApi
    }

    // ---------- 选择 ----------

    private fun select(kind: LoaderKind) {
        if (!usable(kind)) return

        selected = kind
        generation++
        choice = null
        val entry = current

        if (kind == LoaderKind.VANILLA || entry == null) {
            loaderNote.text = ""
            startButton.isDisable = false
            refreshStyles()
            return
        }

        startButton.isDisable = true
        refreshStyles()

        val mc = entry.id
        // 这一版之前查过就直接用：来回点不该每次都再等一轮网络
        val cached = versions[kind] ?: LoaderSource.cached(kind, mc)
        if (cached != null) {
            applyVersions(kind, cached)
            return
        }

        loaderNote.text = "读取${kind.label}版本…"
        val mine = generation
        Thread {
            val outcome = runCatching { LoaderSource.versions(kind, mc) }
            Platform.runLater {
                if (mine != generation) return@runLater
                outcome
                    .onSuccess { applyVersions(kind, it) }
                    .onFailure {
                        loaderNote.text = "读取失败：${it.message ?: "网络错误"}"
                        refreshStyles()
                    }
            }
        }.apply { isDaemon = true; name = "loader-versions" }.start()
    }

    /** 把某一版的加载器版本填进下拉。空表 = 这个游戏版本确实没有对应安装包。 */
    private fun applyVersions(kind: LoaderKind, list: List<LoaderVersion>) {
        versions[kind] = list
        if (list.isEmpty()) {
            loaderNote.text = "${current?.id.orEmpty()} 没有可用的${kind.label}版本"
            if (selected == kind) {
                selected = LoaderKind.VANILLA
                choice = null
                startButton.isDisable = false
            }
            refreshStyles()
            return
        }

        loaderNote.text = ""
        val combo = comboBoxes[kind]
        filling = true
        combo?.items?.setAll(list)
        combo?.value = list.first()
        filling = false
        val entry = current
        if (selected == kind && entry != null && combo?.value != null) {
            choice = LoaderSource.choice(kind, entry.id, combo.value.id)
            startButton.isDisable = false
        }
        if (selected == kind && entry != null) loadApi(entry.id)
        refreshStyles()
    }

    /** 一次问清这个游戏版本能用哪些加载器，免得用户挨个点。 */
    private fun checkLoaders(mc: String, batch: Int) {
        val targets = LoaderKind.entries.filter { it.supported && it != LoaderKind.VANILLA }
        val known = targets.mapNotNull { kind -> LoaderSource.cached(kind, mc)?.let { kind to it } }.toMap()
        if (known.isNotEmpty()) {
            versions.putAll(known)
            refreshStyles()
        }
        val missing = targets.filter { versions[it] == null }
        if (missing.isEmpty()) return

        Thread {
            val found = HashMap<LoaderKind, List<LoaderVersion>>()
            for (kind in missing) {
                runCatching { LoaderSource.versions(kind, mc) }.onSuccess { found[kind] = it }
            }
            Platform.runLater {
                // 期间换了版本，这批结果就作废
                if (batch != availabilityGen || current?.id != mc) return@runLater
                versions.putAll(found)
                refreshStyles()
                // 正在等的那一行，结果回来是空的就退回原版
                if (selected != LoaderKind.VANILLA && versions[selected]?.isEmpty() == true) {
                    select(LoaderKind.VANILLA)
                }
            }
        }.apply { isDaemon = true; name = "loader-check" }.start()
    }

    private fun onLoaderVersion(value: LoaderVersion?, kind: LoaderKind) {
        if (filling || value == null) return
        val entry = current ?: return
        if (kind != selected) return
        choice = LoaderSource.choice(kind, entry.id, value.id)
        startButton.isDisable = false
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

        const val ROW =
            "-fx-background-color: #ffffff; -fx-border-color: #d9d9de; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;"

        const val ROW_ON =
            "-fx-background-color: ${DownloadStyles.ACCENT_BG};" +
                    "-fx-border-color: ${DownloadStyles.ACCENT_LINE}; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;"

        const val ROW_OFF =
            "-fx-background-color: #fbfbfc; -fx-border-color: #e8e8ee; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;"
    }
}
