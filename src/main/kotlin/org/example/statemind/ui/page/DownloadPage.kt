package org.example.statemind.ui.page

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import org.example.statemind.ui.Page

/** 下载中心。空壳，等下载器（版本 / 加载器 / 整合包）做好再填。 */
class DownloadPage : Page {

    override val id = "download"
    override val title = "下载"

    override fun build(): Node = StackPane(
        Label("下载 · 开发中")
    ).apply {
        alignment = Pos.CENTER
    }
}
