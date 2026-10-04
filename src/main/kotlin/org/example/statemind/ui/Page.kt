package org.example.statemind.ui

import javafx.scene.Node

/**
 * 一个页面。build() 只在该页第一次被打开时调用一次，之后节点由 [PageHost] 缓存复用；
 * 切进 / 切出分别走 onEnter() / onLeave()。
 */
interface Page {

    /** 导航靠它定位，定了就别改。 */
    val id: String

    val title: String

    /** 只在首次打开时调用，且一定在 JavaFX 线程上。 */
    fun build(): Node

    fun onEnter() {}

    fun onLeave() {}
}
