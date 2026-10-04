package org.example.statemind.ui.page

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import org.example.statemind.ui.Page

/** 现阶段只放帮助 / 常见问题，以后会塞零散小功能（离线 Wiki、崩溃分析、小工具），故叫「探索」。 */
class HelpPage : Page {

    override val id = "help"
    override val title = "探索"

    override fun build(): Node = StackPane(
        Label("探索 · 开发中")
    ).apply {
        alignment = Pos.CENTER
    }
}
