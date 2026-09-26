package org.example.statemind.ui

import javafx.scene.Node

/**
 * 一个页面。
 *
 * 生命周期：build() 只在页面第一次被打开时调用一次，之后节点被 PageHost 缓存复用；
 * 每次切进 / 切出分别走 onEnter() / onLeave()。
 */
interface Page {

    /** 唯一标识，导航靠它定位，定了就别改。 */
    val id: String

    /** 标签栏上显示的名字。 */
    val title: String

    /** 构建页面内容。只在首次打开时调用，且一定在 JavaFX 线程上。 */
    fun build(): Node

    /** 每次切进这个页面时调用。 */
    fun onEnter() {}

    /** 每次切出这个页面时调用。 */
    fun onLeave() {}
}
