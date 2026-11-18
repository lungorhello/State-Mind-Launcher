package org.example.statemind.ui.page

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import org.example.statemind.ui.Page

/** content 由 App 传入（原左边那套启动表单还没迁进来）；不传就显示占位。 */
class HomePage(private val content: (() -> Node)? = null) : Page {

    override val id = "home"
    override val title = "首页"

    override fun build(): Node {
        val inner = content?.invoke() ?: Label("首页 · 内容待迁入")
        // 留白与其他页一致；卡片自己定宽，别让 StackPane 把它拉满
        return StackPane(inner).apply {
            alignment = Pos.TOP_LEFT
            padding = Insets(20.0)
        }
    }
}
