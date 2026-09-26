package org.example.statemind.ui.page

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import org.example.statemind.ui.Page

/**
 * 探索。现阶段放帮助 / 使用说明 / 常见问题，以后还会塞进一些零散小功能
 * （离线 Wiki 查询、崩溃分析入口、小工具集），所以叫"探索"而不是"帮助"。
 */
class HelpPage : Page {

    override val id = "help"
    override val title = "探索"

    override fun build(): Node = StackPane(
        Label("探索 · 开发中")
    ).apply {
        alignment = Pos.CENTER
    }
}
