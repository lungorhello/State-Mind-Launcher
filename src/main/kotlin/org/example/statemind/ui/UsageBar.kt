package org.example.statemind.ui

import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.shape.Rectangle

/**
 * 一条三段式的占用条：底（浅灰，铺满）→ 已用（灰）→ 分配（紫）。
 *
 * 后两段从左往右**接着排**，不互相覆盖 —— 分配段从已用段结束的地方开始，
 * 所以一眼能看出「系统已经吃了多少、再给游戏多少、还剩多少」。
 *
 * 为什么不用一条 Region 叠几层 `linear-gradient`：那种写法全靠背景层序和透明段切分，
 * 出错时表现就是整条一个颜色，还查不出是哪一层没画。这里三段是三个真实节点，宽度按比例绑，
 * 哪一段不在就是宽度为零，屏幕上直接看得出来。
 */
class UsageBar : HBox() {

    private val usedFrac = SimpleDoubleProperty(0.0)
    private val allocFrac = SimpleDoubleProperty(0.0)

    private val usedSeg = Region().apply { style = "-fx-background-color: $USED;" }
    private val allocSeg = Region().apply { style = "-fx-background-color: $ALLOC;" }

    init {
        style = "-fx-background-color: $BASE; -fx-background-radius: 4;"
        minHeight = HEIGHT
        prefHeight = HEIGHT
        maxHeight = HEIGHT
        maxWidth = Double.MAX_VALUE

        // 宽度 = 整条宽 × 比例。bind 到自己的 widthProperty，窗口拉宽时跟着走。
        usedSeg.prefWidthProperty().bind(widthProperty().multiply(usedFrac))
        allocSeg.prefWidthProperty().bind(widthProperty().multiply(allocFrac))
        children.addAll(usedSeg, allocSeg)

        // 两端的圆角靠裁切：子段是直角矩形，铺在圆角底上会把尖角露出来
        clip = Rectangle().apply {
            widthProperty().bind(this@UsageBar.widthProperty())
            heightProperty().bind(this@UsageBar.heightProperty())
            arcWidth = HEIGHT
            arcHeight = HEIGHT
        }
    }

    /**
     * [used]、[alloc] 都是占物理内存的比例（0–1）。
     * 分配段接在已用段右边，两者加起来超过整机内存时只画到满，不越界。
     */
    fun update(used: Double, alloc: Double) {
        val u = used.coerceIn(0.0, 1.0)
        usedFrac.set(u)
        allocFrac.set(alloc.coerceIn(0.0, 1.0 - u))
    }

    private companion object {
        const val HEIGHT = 8.0
        const val BASE = "#ededf1"
        const val USED = "#b6b9c4"
        const val ALLOC = "#a78bfa"
    }
}
