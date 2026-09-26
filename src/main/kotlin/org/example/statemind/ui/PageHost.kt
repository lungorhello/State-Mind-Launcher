package org.example.statemind.ui

import javafx.scene.Node
import javafx.scene.layout.StackPane

/**
 * 页面容器：同一时刻只显示一个页面。
 *
 * 两个要点：
 * 1. 懒加载——页面第一次被打开时才 build()，没点开的页面不占内存；
 * 2. 切换靠可见性，不移除节点——切回来时输入框内容、滚动位置都还在。
 */
class PageHost(private val pages: List<Page>) : StackPane() {

    private val byId = pages.associateBy { it.id }
    private val built = HashMap<String, Node>()
    private var current: Page? = null

    /** 当前显示的页面；还没打开过任何页面时为 null。 */
    val currentPage: Page? get() = current

    /** 页面切换后回调，参数是新页面的 id。 */
    var onPageChanged: ((String) -> Unit)? = null

    /**
     * 打开指定页面。
     * id 不存在、或该页已经在显示时，什么都不做。
     */
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
}
