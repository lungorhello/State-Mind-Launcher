package org.example.statemind.ui.page.download

internal object DownloadStyles {

    const val ACCENT = "#7c3aed"
    const val ACCENT_TEXT = "#6d28d9"
    const val ACCENT_BG = "#efe6fd"
    const val ACCENT_LINE = "#cdb6f2"
    const val CARD_BG = "#ffffff"
    const val CARD_LINE = "#d9d9de"
    const val CARD_DIVIDER = "#ececf0"
    const val TEXT = "#1f1f22"
    const val TEXT_DIM = "#7c7c85"
    const val TEXT_FAINT = "#c2c2c9"

    /** 跟设置页一致。 */
    const val PAGE_PADDING = 20.0

    const val CARD_RADIUS = 10
    const val BAR_WIDTH = 80.0

    const val CARD =
        "-fx-background-color: $CARD_BG; -fx-border-color: $CARD_LINE; -fx-border-width: 1;" +
                "-fx-border-radius: $CARD_RADIUS; -fx-background-radius: $CARD_RADIUS;"

    const val CARD_TOP =
        "-fx-background-color: $CARD_BG; -fx-border-color: $CARD_LINE $CARD_LINE transparent $CARD_LINE;" +
                "-fx-border-width: 1 1 0 1;" +
                "-fx-border-radius: $CARD_RADIUS $CARD_RADIUS 0 0;" +
                "-fx-background-radius: $CARD_RADIUS $CARD_RADIUS 0 0;"

    const val CARD_BOTTOM =
        "-fx-background-color: $CARD_BG; -fx-border-color: transparent $CARD_LINE $CARD_LINE $CARD_LINE;" +
                "-fx-border-width: 0 1 1 1;" +
                "-fx-border-radius: 0 0 $CARD_RADIUS $CARD_RADIUS;" +
                "-fx-background-radius: 0 0 $CARD_RADIUS $CARD_RADIUS;"

    const val CARD_MIDDLE =
        "-fx-background-color: $CARD_BG; -fx-border-color: transparent $CARD_LINE transparent $CARD_LINE;" +
                "-fx-border-width: 0 1 0 1;"

    /** 可点行的悬停底色。所有「点一下有反应」的行都得有，否则一眼看不出哪些能点。 */
    const val HOVER_BG = "#f7f7fa"

    const val CARD_HOVER =
        "-fx-background-color: $HOVER_BG; -fx-border-color: $CARD_LINE; -fx-border-width: 1;" +
                "-fx-border-radius: $CARD_RADIUS; -fx-background-radius: $CARD_RADIUS;"

    const val CARD_HOVER_TOP =
        "-fx-background-color: $HOVER_BG; -fx-border-color: $CARD_LINE $CARD_LINE transparent $CARD_LINE;" +
                "-fx-border-width: 1 1 0 1;" +
                "-fx-border-radius: $CARD_RADIUS $CARD_RADIUS 0 0;" +
                "-fx-background-radius: $CARD_RADIUS $CARD_RADIUS 0 0;"

    const val CARD_HOVER_BOTTOM =
        "-fx-background-color: $HOVER_BG; -fx-border-color: transparent $CARD_LINE $CARD_LINE $CARD_LINE;" +
                "-fx-border-width: 0 1 1 1;" +
                "-fx-border-radius: 0 0 $CARD_RADIUS $CARD_RADIUS;" +
                "-fx-background-radius: 0 0 $CARD_RADIUS $CARD_RADIUS;"

    const val CARD_HOVER_MIDDLE =
        "-fx-background-color: $HOVER_BG; -fx-border-color: transparent $CARD_LINE transparent $CARD_LINE;" +
                "-fx-border-width: 0 1 0 1;"

    /**
     * 跟内置弹窗、设置页的输入框同一套皮。
     *
     * 高度写死 32：AtlantaFX 给 ComboBox 的默认内边距很大，光靠 padding 压不住，
     * 一行筛选框会跟旁边的按钮差出一截，显示成「半截空盒子里塞了行字」。
     */
    const val FIELD =
        "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: $ACCENT_LINE;" +
                "-fx-background-color: $CARD_BG; -fx-border-width: 1; -fx-font-size: 13px;" +
                "-fx-pref-height: 32; -fx-min-height: 32; -fx-max-height: 32;" +
                "-fx-padding: 0 10 0 10;"
}
