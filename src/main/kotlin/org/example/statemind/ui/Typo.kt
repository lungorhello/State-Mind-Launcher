package org.example.statemind.ui

/**
 * 界面上的文字只有这几档。各页各写一个字号颜色的话，同一屏里就会出现两套「标题」——
 * 要用文字样式就从这里取，不要在页面里现写 `-fx-font-size`。
 */
object Typo {

    /** 页内大标题。 */
    const val HEADING = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1f1f22;"

    /** 卡片小标题。 */
    const val SECTION = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #1f1f22;"

    /** 设置行的标题、表单字段名。 */
    const val LABEL = "-fx-font-size: 14px; -fx-text-fill: #1f1f22;"

    /** 跟在标题后面的补充说明。 */
    const val NOTE = "-fx-font-size: 12px; -fx-text-fill: #9a9aa0;"

    /** 数值、计数这类次要文字。 */
    const val DIM = "-fx-font-size: 13px; -fx-text-fill: #7c7c85;"

    /** 浅色底（深色卡片）上的说明文字。 */
    const val ON_DARK = "-fx-font-size: 13px; -fx-text-fill: #c9b3f2;"
}
