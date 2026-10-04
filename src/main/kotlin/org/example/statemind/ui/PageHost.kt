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
}
