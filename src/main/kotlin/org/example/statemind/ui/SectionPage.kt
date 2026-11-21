package org.example.statemind.ui

import javafx.scene.Node
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority

/** 带左侧二级 tab 的页面，复用 [NavBar]（compact）与 [PageHost]，默认停在第一个子 tab。 */
abstract class SectionPage(
    override val id: String,
    override val title: String,
    private val sections: List<Page>
) : Page {

    private lateinit var host: PageHost

    override fun build(): Node {
        host = PageHost(sections)
        val tabs = NavBar(sections, compact = true) { host.open(it) }
        host.onPageChanged = { tabs.select(it) }

        val box = HBox(tabs, host)
        HBox.setHgrow(host, Priority.ALWAYS)

        // build() 本身就在 JavaFX 线程上被调用，直接切即可
        sections.firstOrNull()?.let { host.open(it.id) }
        return box
    }

    /**
     * 顶层导航切回本页时把它转给当前子页。
     *
     * 子页的 onEnter 才是刷新的地方（重扫目录、重新读设置）。不转的话，从「下载」切回
     * 「设置」看到的还是离开那一刻的内容 —— 得先切个二级 tab 才更新。
     */
    override fun onEnter() {
        if (::host.isInitialized) host.refreshCurrent()
    }
}
