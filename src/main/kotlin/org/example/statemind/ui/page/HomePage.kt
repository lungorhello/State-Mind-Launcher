package org.example.statemind.ui.page

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import org.example.statemind.ui.Page

/**
 * 首页。
 *
 * 现在的内容是**暂时的**：由外部（App）把原来左边那套启动表单传进来显示，
 * 启动逻辑仍留在 App 里，没有迁移。以后首页有真内容了，把 content 参数去掉即可。
 */
class HomePage(private val content: (() -> Node)? = null) : Page {

    override val id = "home"
    override val title = "首页"

    override fun build(): Node {
        content?.let { return it() }
        return StackPane(Label("首页 · 内容待迁入")).apply { alignment = Pos.CENTER }
    }
}
