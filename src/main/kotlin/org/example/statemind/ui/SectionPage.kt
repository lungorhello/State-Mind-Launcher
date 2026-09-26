package org.example.statemind.ui

import javafx.scene.Node
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority

/**
 * 带二级左侧 tab 的页面：左边一列子 tab，右边是子页面容器。
 *
 * 复用的还是 [NavBar]（compact 模式）和 [PageHost]，所以子页面同样享受
 * 懒加载 + 「切走再切回来输入不丢」。默认停在第一个子 tab。
 *
 * 目前「设置」用它；将来「探索」要分栏也可以照用。
 */
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

        // build() 本身就在 JavaFX 线程上被调用，这里直接切即可，不用 runLater
        sections.firstOrNull()?.let { host.open(it.id) }
        return box
    }
}
