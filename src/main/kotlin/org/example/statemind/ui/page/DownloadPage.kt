package org.example.statemind.ui.page

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.example.statemind.core.BackendUtil
import org.example.statemind.core.InstanceScan
import org.example.statemind.core.LaunchSelection
import org.example.statemind.core.Prefs
import org.example.statemind.core.RecentInstall
import org.example.statemind.core.RemoteVersion
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.download.DownloadCenter
import org.example.statemind.core.mod.InstallTargets
import org.example.statemind.core.mod.ModKind
import org.example.statemind.ui.Page
import org.example.statemind.ui.Typo
import org.example.statemind.ui.page.download.DownloadStyles
import org.example.statemind.ui.page.download.ModrinthView
import org.example.statemind.ui.page.download.TopBar
import org.example.statemind.ui.page.download.VersionHero
import org.example.statemind.ui.page.download.VersionInstallView
import org.example.statemind.ui.page.download.VersionListView
import java.io.File

class DownloadPage(private val onOpenTasks: () -> Unit = {}) : Page {

    override val id = "download"
    override val title = "下载"

    /**
     * 版本装进哪份游戏目录：首页当前选中的那份，没选就退到内置的 `.minecraft`。
     * 安装页要用它算版本名查重，装完还要按它落盘 —— 两处必须是同一个值。
     */
    private val gameRoot: () -> File = { LaunchSelection.current?.versionRoot ?: BackendUtil.minecraftDir }

    private val topBar = TopBar(onSelect = ::selectTab, onSearch = ::search)
    private val hero = VersionHero { entry -> openInstall(entry) }
    private val list = VersionListView { entry -> openInstall(entry) }
    private val install = VersionInstallView(
        onBack = { showList() },
        onStart = { startDownload() },
        root = gameRoot
    )
    private val community = ModrinthView({ InstallTargets.gameDir() }) { open ->
        // 进详情页后，外层的类型栏与搜索框让位；回来时恢复
        topBar.isVisible = !open
        topBar.isManaged = !open
    }

    private val unavailableTitle = Label().apply { style = Typo.SECTION }
    private val unavailableNote = Label().apply {
        style = Typo.NOTE
        isWrapText = true
        maxWidth = 420.0
    }
    private val unavailable = VBox(8.0, unavailableTitle, unavailableNote).apply {
        alignment = Pos.CENTER
        isVisible = false
    }

    private val vanillaRoot = VBox(12.0, hero, list)
    private val content = StackPane(vanillaRoot, community, unavailable)
    private val stage = StackPane()

    /** 顶部类型栏 + 内容区那一整块；进安装页时整块收起来。 */
    private var root: VBox? = null

    private var loaded = false

    override fun build(): Node {
        // 列表吃满剩下的高度，滚动条因此落在页面最右侧
        VBox.setVgrow(content, Priority.ALWAYS)
        community.isVisible = false

        val root = VBox(12.0, topBar, content).apply {
            padding = Insets(DownloadStyles.PAGE_PADDING)
        }
        this.root = root
        // 安装页没有不透明底，留着下面的内容会透出来
        install.isVisible = false
        stage.children.setAll(root, install)

        // 社区内容的默认筛选跟着「最近装好的版本」/「首页选中的版本」走。
        // 用户已经手动改过筛选的话，ModrinthView 会自己忽略这两条通知。
        // 这两路通知可能从下载线程打过来（RecentInstall 就是下载线程记的），
        // 直接改界面会抛「Not on FX application thread」，那次异常还会被下载队列
        // 当成下载失败记到任务上 —— 于是文件全下完却显示「已中断」。
        LaunchSelection.onChange { Platform.runLater { community.setPreferred(preferredMcVersion()) } }
        RecentInstall.onChange { Platform.runLater { community.setPreferred(preferredMcVersion()) } }
        return stage
    }

    override fun onEnter() {
        if (loaded) return
        loaded = true
        loadVersions()
    }

    // ---------- 数据 ----------

    /** 会阻塞，所以整体扔后台线程。 */
    private fun loadVersions() {
        val attempts = Prefs.versionListSource.attempts
        // 上次的清单先摆出来，再在后台刷新一次。国内直连官方清单常常要等满超时，
        // 这十几秒里给一张白纸，用户会以为页面坏了。
        val cached = RemoteVersion.cached(attempts.first().source)
        if (cached != null) {
            applyManifest(cached)
        } else {
            hero.setLoading()
            list.setLoading()
        }

        Thread {
            val outcome = runCatching { RemoteVersion.load(attempts) }
            Platform.runLater {
                outcome
                    .onSuccess { result ->
                        // 网络不通退到缓存时，跟刚才画的是同一份，不必再刷一遍
                        if (cached == null || !result.fromCache) applyManifest(result)
                    }
                    .onFailure { e ->
                        if (cached == null) {
                            hero.setFailure()
                            list.setFailure("版本清单获取失败：${e.message ?: e}") { loadVersions() }
                        }
                    }
            }
        }.apply { isDaemon = true; name = "download-manifest" }.start()
    }

    private fun applyManifest(result: RemoteVersion.Result) {
        hero.setResult(result)
        install.setSource(result.source.label)
        list.setVersions(result.versions)
        community.setVersions(result.versions)
        community.setPreferred(preferredMcVersion())
    }

    /**
     * 社区内容默认按哪个游戏版本筛：本次刚装好的那个优先，其次首页选中的版本。
     * 首页存的是启动目标，加载器版本的 id 形如 `fabric-loader-0.16.14-1.21.1`，
     * 得从版本 json 里读回真正的原版版本号 —— 直接拿它去筛会一条都匹配不上。
     */
    private fun preferredMcVersion(): String? =
        RecentInstall.mcVersion
            ?: LaunchSelection.current?.let { InstanceScan.mcVersionOf(it.versionRoot, it.versionId) }

    // ---------- tab 与搜索 ----------

    private fun selectTab(kind: ModKind?) {
        when {
            kind == null -> {
                vanillaRoot.isVisible = true
                community.isVisible = false
                unavailable.isVisible = false
            }

            kind.installable -> {
                vanillaRoot.isVisible = false
                unavailable.isVisible = false
                community.isVisible = true
                community.show(kind)
            }

            else -> {
                vanillaRoot.isVisible = false
                community.isVisible = false
                unavailable.isVisible = true
                showUnavailable(kind)
            }
        }
    }

    /**
     * 搜索按「词所属的 tab」分发，不看当前 tab ——
     * TopBar 切 tab 时是先设文本（触发这里）再回调 [selectTab]，顺序对不上。
     */
    private fun search(kind: ModKind?, text: String) {
        when {
            kind == null -> list.setQuery(text)
            kind.installable -> community.setQuery(text)
            else -> Unit
        }
    }

    private fun showUnavailable(kind: ModKind) {
        unavailableTitle.text = "${kind.label}暂不可用"
        unavailableNote.text = when (kind) {
            ModKind.MODPACK ->
                "整合包是一个带安装清单的压缩包，需要解包、按清单下载依赖，再合并覆盖文件。"

            else -> ""
        }
    }

    // ---------- 两屏之间 ----------

    private fun openInstall(entry: VersionEntry) {
        install.show(entry)
        root?.isVisible = false
        install.isVisible = true
    }

    private fun showList() {
        install.isVisible = false
        root?.isVisible = true
    }

    /** 「开始下载」：登记一个下载任务，退回列表，再跳到任务页看进度。 */
    private fun startDownload() {
        val entry = install.current ?: return
        // 装进首页当前选中的那份游戏目录。写死内置目录的话，多目录（multi 系 / 用户自加）
        // 场景下版本会落到别处，跟随后装模组用的隔离目录彻底分家。
        val root = gameRoot()
        DownloadCenter.submit(
            entry,
            root,
            Prefs.fileSource,
            install.choice,
            install.versionName,
            install.fabricApiVersionId
        )
        // 先退出安装页：任务页只是过去看一眼进度，回来时该落在版本列表上，
        // 而不是那个已经点过「开始下载」的确认页
        showList()
        onOpenTasks()
    }
}
