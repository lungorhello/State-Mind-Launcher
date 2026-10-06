package org.example.statemind.ui.page

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.example.statemind.core.BackendUtil
import org.example.statemind.core.Prefs
import org.example.statemind.core.RemoteVersion
import org.example.statemind.core.download.DownloadCenter
import org.example.statemind.core.VersionEntry
import org.example.statemind.ui.Page
import org.example.statemind.ui.page.download.DownloadStyles
import org.example.statemind.ui.page.download.TopBar
import org.example.statemind.ui.page.download.VersionHero
import org.example.statemind.ui.page.download.VersionInstallView
import org.example.statemind.ui.page.download.VersionListView

class DownloadPage(private val onOpenTasks: () -> Unit = {}) : Page {

    override val id = "download"
    override val title = "下载"

    private val topBar = TopBar { text -> list.setQuery(text) }
    private val hero = VersionHero { entry -> openInstall(entry) }
    private val list = VersionListView { entry -> openInstall(entry) }
    private val install = VersionInstallView(
        onBack = { showList() },
        onStart = { startDownload() }
    )

    private val stage = StackPane()

    /**
     * 列表态的根节点。切到安装页时整块收起来 —— 安装页自己没有不透明底，
     * 留着它就会从下面透出来。
     */
    private var listRoot: VBox? = null

    private var loaded = false

    override fun build(): Node {
        val root = VBox(12.0, topBar, hero, list).apply {
            padding = Insets(DownloadStyles.PAGE_PADDING)
        }
        // 列表吃满剩下的高度，滚动条因此落在页面最右侧
        VBox.setVgrow(list, Priority.ALWAYS)
        listRoot = root

        install.isVisible = false
        stage.children.setAll(root, install)
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
        hero.setLoading()
        list.setLoading()
        Thread {
            val outcome = runCatching { RemoteVersion.load(Prefs.versionListSource.attempts) }
            Platform.runLater {
                outcome
                    .onSuccess { result ->
                        hero.setResult(result)
                        install.setSource(result.source.label)
                        list.setVersions(result.versions)
                    }
                    .onFailure { e ->
                        hero.setFailure()
                        list.setFailure("版本清单获取失败：${e.message ?: e}") { loadVersions() }
                    }
            }
        }.apply { isDaemon = true; name = "download-manifest" }.start()
    }

    // ---------- 两屏之间 ----------

    private fun openInstall(entry: VersionEntry) {
        install.show(entry)
        listRoot?.isVisible = false
        install.isVisible = true
    }

    private fun showList() {
        install.isVisible = false
        listRoot?.isVisible = true
    }

    /** 「开始下载」：登记一个下载任务，然后跳到任务页看进度。 */
    private fun startDownload() {
        val entry = install.current ?: return
        DownloadCenter.submit(entry, BackendUtil.minecraftDir, Prefs.fileSource)
        onOpenTasks()
    }
}
