package org.example.statemind.ui.page

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import org.example.statemind.ui.Page

/**
 * content 由 App 传入（原左边那套启动表单还没迁进来）；不传就显示占位。
 *
 * [onEnterAction] 每次切回本页都跑一次：版本清单在别的页（设置 · 实例）会被删掉，
 * 首页下拉若只在启动时扫一次，删掉的版本会一直留在里面。
 */
class HomePage(
    private val content: (() -> Node)? = null,
    private val onEnterAction: () -> Unit = {}
) : Page {

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

    override fun onEnter() = onEnterAction()
}
