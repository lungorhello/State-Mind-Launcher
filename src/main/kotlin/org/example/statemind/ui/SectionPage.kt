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

    override fun build(): Node {
        val host = PageHost(sections)
        val tabs = NavBar(sections, compact = true) { host.open(it) }
        host.onPageChanged = { tabs.select(it) }

        val box = HBox(tabs, host)
        HBox.setHgrow(host, Priority.ALWAYS)

        // build() 本身就在 JavaFX 线程上被调用，直接切即可
        sections.firstOrNull()?.let { host.open(it.id) }
        return box
    }
}
