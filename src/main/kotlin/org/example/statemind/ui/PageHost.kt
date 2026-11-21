package org.example.statemind.ui

import javafx.scene.Node
import javafx.scene.layout.StackPane

/** 切换靠可见性、不移除节点：切回来时输入框内容与滚动位置都还在。 */
class PageHost(private val pages: List<Page>) : StackPane() {

    private val byId = pages.associateBy { it.id }
    private val built = HashMap<String, Node>()
    private var current: Page? = null

    val currentPage: Page? get() = current

    var onPageChanged: ((String) -> Unit)? = null

    /** id 不存在、或该页已在显示时什么都不做。 */
    fun open(id: String) {
        val page = byId[id] ?: return
        if (current?.id == id) return

        current?.onLeave()

        val node = built.getOrPut(page.id) { page.build() }
        if (node !in children) children.add(node)
        children.forEach { it.isVisible = it === node }

        current = page
        page.onEnter()
        onPageChanged?.invoke(id)
    }

    /**
     * 让当前页重新走一遍 onLeave / onEnter。
     *
     * [open] 在「已经停在这一页」时直接返回，所以外层容器切回来时（顶级导航切走再切回
     * 「设置」），子页收不到 onEnter，界面会停在离开时那份数据上。这里给它一条明确的路；
     * 界面上的刷新按钮也走这里。
     */
    fun refreshCurrent() {
        current?.onLeave()
        current?.onEnter()
    }
}
