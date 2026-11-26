package org.example.statemind.ui

import atlantafx.base.controls.ProgressSliderSkin
import javafx.scene.control.Slider

/**
 * 给滑条挂上「划过的一段变色」。
 *
 * 主题（AtlantaFX）其实自带这套皮肤和 `.progress-slider` 的样式，只是它的 CSS 里没写
 * `-fx-skin`，所以默认还是普通的 [javafx.scene.control.SliderSkin]，轨道从头到尾一个色。
 * 这里手动换成 [ProgressSliderSkin]：它会在轨道上加一层 `.progress`，宽度跟着圆点走。
 *
 * 两处设置页共用这一份，颜色要调只改下面的常量。
 */
fun Slider.accentTrack(): Slider = apply {
    skin = ProgressSliderSkin(this)
    val colors = """
        -color-slider-track-progress: $TRACK_FILL;
        -color-slider-thumb: $ACCENT;
        -color-slider-thumb-border: $ACCENT;
    """.trimIndent()
    style = if (style.isNullOrBlank()) colors else "$style\n$colors"
}

/** 启动器主题紫（淡）—— 与开关、卡片强调色同一个值。 */
const val ACCENT = "#a78bfa"

/** 划过的那段更淡一点：整条填满到位时会和圆点糊成一团，淡一档反而看得出「已经走过」。 */
private const val TRACK_FILL = "#c4b5fd"
