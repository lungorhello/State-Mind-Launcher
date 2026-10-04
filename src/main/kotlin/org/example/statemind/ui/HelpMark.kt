package org.example.statemind.ui

import javafx.scene.Cursor
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.util.Duration

/** 说明用的「?」小圆标，悬停出提示。界面上不适合直接写出来的解释都挂在它身上。 */
fun helpMark(text: String): Label = Label("?").apply {
    style = """
        -fx-background-color: #e5e5ea;
        -fx-background-radius: 8;
        -fx-text-fill: #55555c;
        -fx-font-size: 11px;
        -fx-font-weight: bold;
        -fx-padding: 0;
        -fx-min-width: 16; -fx-min-height: 16;
        -fx-max-width: 16; -fx-max-height: 16;
        -fx-alignment: center;
    """
    cursor = Cursor.HAND
    Tooltip.install(this, Tooltip(text).apply { showDelay = Duration.millis(250.0) })
}
