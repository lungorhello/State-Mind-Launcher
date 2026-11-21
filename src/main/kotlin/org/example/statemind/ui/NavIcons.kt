package org.example.statemind.ui

import javafx.scene.Group
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin
import javafx.scene.transform.Affine

/**
 * 界面图标：图形是从 SVG 里提出来的矢量路径（`d` + 各自的 viewBox），用 [SVGPath] 画 ——
 * 缩放不糊，颜色也能跟着选中态变。
 *
 * 两路画法，靠 [Data.strokeWidth] 区分：
 * - **填充型**（「游戏图标包 v1.4」，左侧主导航在用的那套）：路径围出的实心块，颜色走 `fill`；
 * - **描边型**（lucide 那类线性图标）：画的是线本身，颜色走 `stroke`。
 *
 * 调用方不必知道手里是哪种 —— 换色只走 [Icon.color]。
 *
 * 图标的 viewBox 各不相同（图标包是紧凑包围盒、线性图标是 24 网格），
 * 所以画的时候要按各自的 viewBox 缩放平移。
 */
internal object NavIcons {

    /**
     * [strokeWidth] 为 null 表示填充型；否则是描边宽度 —— 单位是 **viewBox**，
     * 会跟着 [Affine] 一起缩放，所以同一个图标在不同尺寸下粗细比例一致。
     */
    private class Data(
        val d: String,
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
        val strokeWidth: Double? = null
    )

    /** [color] 是唯一的换色入口，按填充/描边自己分派 —— 外面别去碰内部的 [SVGPath]。 */
    class Icon(val node: StackPane, private val path: SVGPath, private val stroked: Boolean) {

        var color: Color
            get() = (if (stroked) path.stroke else path.fill) as Color
            set(value) {
                if (stroked) path.stroke = value else path.fill = value
            }
    }

    /** home（界面.svg） */
    private val HOME = Data(
        "M7.26 3.8c.508 0 .762 0 .93.143q.036.031.066.068c.144.168.144.423.144.93v1.54c0 .676 0 1.014-.191 1.239a1 1 0 0 1-.09.089C7.896 8 7.557 8 6.88 8H3.12c-.676 0-1.014 0-1.239-.191a1 1 0 0 1-.088-.09C1.6 7.496 1.6 7.158 1.6 6.48V4.94c0-.507 0-.76.144-.93a1 1 0 0 1 .066-.067c.168-.143.422-.143.93-.143zm-3.91.8c-.423 0-.635-.001-.775.119a1 1 0 0 0-.055.055c-.12.14-.12.353-.12.776v.699c0 .423 0 .635.12.775q.025.03.055.055c.14.12.352.12.775.12h.5c.423 0 .634 0 .775-.12a1 1 0 0 0 .055-.055c.12-.14.12-.352.12-.775v-.7c0-.422 0-.634-.12-.775a1 1 0 0 0-.055-.055c-.14-.12-.352-.12-.775-.12zM5.9 6.2a.4.4 0 0 0 0 .799h.6a.4.4 0 0 0 0-.8zm0-1.4a.4.4 0 1 0 0 .8h1.3a.4.4 0 1 0 0-.8zm2-2.8a.5.5 0 0 1 0 1H2.1a.5.5 0 0 1 0-1z",
        1.60, 1.60, 6.80, 6.80
    )

    /** download（导入.svg） */
    private val DOWNLOAD = Data(
        "M7.6 5.616a.6.6 0 0 1 .6.6v.01c0 .93 0 1.395-.264 1.704a1 1 0 0 1-.122.122c-.309.263-.774.263-1.704.263H3.89c-.93 0-1.395 0-1.704-.263a1 1 0 0 1-.122-.122C1.8 7.62 1.8 7.156 1.8 6.226v-.01a.6.6 0 0 1 1.2 0v.045c0 .38 0 .57.107.697a1 1 0 0 0 .05.05c.126.109.317.108.697.108h2.29c.38 0 .571 0 .697-.107a1 1 0 0 0 .051-.051C7 6.832 7 6.641 7 6.261v-.045l.011-.12a.6.6 0 0 1 .59-.48M5.197 1.685a.6.6 0 0 1 .6.6v.06c0 .317 0 .476.083.584a.4.4 0 0 0 .04.043c.097.095.277.115.636.156.189.021.315.062.398.14a.6.6 0 0 1 .187.41c.01.239-.231.518-.715 1.076l-.567.655c-.378.437-.568.656-.81.677a1 1 0 0 1-.103 0c-.243-.021-.432-.24-.81-.677l-.567-.655c-.484-.558-.726-.837-.716-1.075a.6.6 0 0 1 .188-.411c.083-.078.209-.118.397-.14.36-.04.54-.061.637-.156a.4.4 0 0 0 .039-.043c.083-.108.083-.267.083-.584v-.06a.6.6 0 0 1 .6-.6z",
        1.68, 1.68, 6.63, 6.63
    )

    /** setting（设置-02.svg） */
    private val SETTING = Data(
        "M5.984 2.31c.351.004.676.19.854.494l.497.847.485.856a1 1 0 0 1 0 .986l-.485.856-.498.847a1 1 0 0 1-.854.493L5 7.697l-.983-.007a1 1 0 0 1-.854-.494l-.498-.848-.485-.855a1 1 0 0 1 0-.986l.484-.855.499-.848a1 1 0 0 1 .854-.493L5 2.303zM5.6 3.962A1.2 1.2 0 1 0 4.4 6.04a1.2 1.2 0 0 0 1.2-2.078",
        2.05, 2.05, 5.90, 5.90
    )

    /** help（网格-03.svg） */
    private val EXPLORE = Data(
        "M3.25 4.8c.515 0 .773 0 .955.129q.097.069.166.166c.13.182.129.44.129.955v.7c0 .515 0 .773-.129.955a.7.7 0 0 1-.166.166c-.182.13-.44.129-.955.129s-.773 0-.955-.129a.7.7 0 0 1-.166-.166C1.999 7.523 2 7.265 2 6.75v-.7c0-.515 0-.773.129-.955a.7.7 0 0 1 .166-.166c.182-.13.44-.13.955-.13M7.1 6.2c.375 0 .563 0 .694.096a.5.5 0 0 1 .11.11C8 6.538 8 6.725 8 7.1s0 .563-.096.694a.5.5 0 0 1-.11.11C7.663 8 7.474 8 7.1 8h-.8c-.375 0-.562 0-.694-.096a.5.5 0 0 1-.11-.11C5.4 7.663 5.4 7.474 5.4 7.1c0-.375 0-.562.095-.694a.5.5 0 0 1 .111-.11c.132-.096.319-.096.694-.096zM6.75 2c.515 0 .773 0 .955.129q.097.069.166.166c.13.182.129.44.129.955v.7c0 .515 0 .773-.129.955a.7.7 0 0 1-.166.166c-.182.13-.44.13-.955.13s-.773 0-.955-.13a.7.7 0 0 1-.166-.166c-.13-.182-.129-.44-.129-.955v-.7c0-.515 0-.773.129-.955a.7.7 0 0 1 .166-.166c.182-.13.44-.129.955-.129M3.6 2c.375 0 .563 0 .694.096a.5.5 0 0 1 .11.11c.096.131.096.32.096.694 0 .375 0 .562-.096.694a.5.5 0 0 1-.11.11c-.131.096-.32.096-.694.096h-.7c-.375 0-.563 0-.694-.096a.5.5 0 0 1-.11-.11C2 3.462 2 3.275 2 2.9s0-.563.096-.694a.5.5 0 0 1 .11-.11C2.337 2 2.525 2 2.899 2z",
        2.00, 2.00, 6.00, 6.00
    )

    /**
     * task（lucide `arrow-down-to-line`）：描边型，24 网格、线宽 2、圆头圆角。
     * 标题栏的下载任务入口用它（见 [WindowChrome.titleBar]）—— 那里两侧的最小化、关闭
     * 本来就是圆头线条，摆一起是一路的；而左侧导航那套是填充型，所以下载页仍用自己的图标。
     *
     * 三段子路径：竖线、V 形箭头、底线。原图标第二段是相对坐标（`m6 11 6 6 6-6`），
     * 合并成一条路径时换成了等价的绝对写法，免得前一段的落点影响它。
     */
    private val TASK = Data(
        "M12 17V3M6 11L12 17L18 11M19 21H5",
        0.0, 0.0, 24.0, 24.0,
        strokeWidth = 2.0
    )

    /** back（lucide `arrow-left`）：描边型，跟标题栏那套线条图标同源。 */
    private val BACK = Data(
        "M19 12H5M12 19L5 12L12 5",
        0.0, 0.0, 24.0, 24.0,
        strokeWidth = 2.0
    )

    /**
     * cross（叉号）：描边型，两条对角线。
     *
     * 原图是 32 网格、线宽 2，但线只画在 7..25 这一段里 —— 按整张 32 网格缩放，
     * 叉号会缩成原来三分之二大，看着比旁边的图标小一圈。这里把包围盒取成线段本身。
     */
    private val CROSS = Data(
        "M7 7L25 25M7 25L25 7",
        7.0, 7.0, 18.0, 18.0,
        strokeWidth = 2.0
    )

    /**
     * trash（垃圾桶）：填充型。
     *
     * 原图 91 网格：一条路径画出桶身与盖子，三根竖条是三个独立的 `rect`。
     * 这里把竖条也改写成路径的子路径（`M…h…v…h…z`），拼进同一条 `d` ——
     * `SVGPath` 一个内容可以含多段子路径，填一次色就全都有了。
     *
     * 包围盒取的是图形实际范围（不是整张 91 网格），不然桶会缩成原来一半大。
     */
    private val TRASH = Data(
        "M67.305,36.442v-8.055c0-0.939-0.762-1.701-1.7-1.701H54.342v-5.524c0-0.938-0.761-1.7-1.699-1.7h-12.75 " +
                "c-0.939,0-1.701,0.762-1.701,1.7v5.524H26.93c-0.939,0-1.7,0.762-1.7,1.701v8.055c0,0.938,0.761,1.699,1.7,1.699h0.488v34.021 " +
                "c0,0.938,0.761,1.7,1.699,1.7h29.481c3.595,0,6.52-2.924,6.52-6.518V38.142h0.486C66.543,38.142,67.305,37.381,67.305,36.442z " +
                "M41.592,22.862h9.35v3.824h-9.35V22.862z M61.719,67.345c0,1.719-1.4,3.117-3.12,3.117h-27.78v-32.32l30.9,0.002V67.345z " +
                "M63.904,34.742H28.629v-4.655h11.264h12.75h11.262V34.742z " +
                "M36.066,44.962h3.4v19.975h-3.4z M44.566,44.962h3.4v19.975h-3.4z M53.066,44.962h3.4v19.975h-3.4z",
        25.2, 21.1, 42.2, 49.4
    )

    /**
     * clock（时钟）：描边型，元信息里的「最近更新」用它。
     */
    private val CLOCK = Data(
        "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2",
        3.0, 3.0, 18.0, 18.0,
        strokeWidth = 2.0
    )

    /**
     * globe（地球）：描边型，元信息里的「平台」用它。
     */
    private val GLOBE = Data(
        "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.7 3.8 5.8 3.8 9s-1.3 6.3-3.8 9" +
                "c-2.5-2.7-3.8-5.8-3.8-9S9.5 5.7 12 3z",
        3.0, 3.0, 18.0, 18.0,
        strokeWidth = 2.0
    )

    /**
     * package（箱子）：描边型，元信息里的「支持版本 / 加载器」用它。
     */
    private val PACKAGE = Data(
        "M12 2l9 5v10l-9 5-9-5V7zM3 7l9 5 9-5M12 12v10",
        3.0, 2.0, 18.0, 20.0,
        strokeWidth = 2.0
    )

    /**
     * person（人）：描边型，元信息里的「作者」用它。
     */
    private val PERSON = Data(
        "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
        4.0, 3.0, 16.0, 18.0,
        strokeWidth = 2.0
    )

    /**
     * pin（图钉）：描边型，实例页「设为默认下载位置」用它。
     * 这个图形离开上下文不算自明，按钮上必须带 Tooltip。
     */
    private val PIN = Data(
        "M12 17v5M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12" +
                "a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1z",
        5.0, 2.0, 14.0, 20.0,
        strokeWidth = 2.0
    )

    /** folder（文件夹）：描边型，实例页「打开文件夹」用它。 */
    private val FOLDER = Data(
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9" +
                "A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z",
        2.0, 3.0, 20.0, 17.0,
        strokeWidth = 2.0
    )

    /** refresh（顺时针箭头）：描边型，实例页的重扫按钮用它。 */
    private val REFRESH = Data(
        "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8M21 3v5h-5",
        3.0, 3.0, 18.0, 18.0,
        strokeWidth = 2.0
    )

    /** 返回箭头，[size] 是见方边长。 */
    fun back(size: Double, color: Color): Icon = render(BACK, size, color)

    /** 叉号，[size] 是见方边长。 */
    fun cross(size: Double, color: Color): Icon = render(CROSS, size, color)

    /** 垃圾桶，[size] 是见方边长。 */
    fun trash(size: Double, color: Color): Icon = render(TRASH, size, color)

    /** 时钟，[size] 是见方边长。 */
    fun clock(size: Double, color: Color): Icon = render(CLOCK, size, color)

    /** 地球，[size] 是见方边长。 */
    fun globe(size: Double, color: Color): Icon = render(GLOBE, size, color)

    /** 箱子，[size] 是见方边长。 */
    fun packageIcon(size: Double, color: Color): Icon = render(PACKAGE, size, color)

    /** 人形，[size] 是见方边长。 */
    fun person(size: Double, color: Color): Icon = render(PERSON, size, color)

    /** 图钉，[size] 是见方边长。 */
    fun pin(size: Double, color: Color): Icon = render(PIN, size, color)

    /** 文件夹，[size] 是见方边长。 */
    fun folder(size: Double, color: Color): Icon = render(FOLDER, size, color)

    /** 刷新（顺时针箭头），[size] 是见方边长。 */
    fun refresh(size: Double, color: Color): Icon = render(REFRESH, size, color)

    /** 下载量用的箭头（跟标题栏那个下载入口同一条路径）。 */
    fun download(size: Double, color: Color): Icon = render(TASK, size, color)

    /** 返回 null 表示这个页面没配图标（按钮退化成纯文字）。 */
    fun byPage(pageId: String, size: Double, color: Color): Icon? {
        val data = when (pageId) {
            "home" -> HOME
            "download" -> DOWNLOAD
            "setting" -> SETTING
            "help" -> EXPLORE
            "task" -> TASK
            else -> return null
        }
        return render(data, size, color)
    }

    private fun render(data: Data, size: Double, color: Color): Icon {
        val strokeWidth = data.strokeWidth
        val path = SVGPath().apply {
            content = data.d
            if (strokeWidth == null) {
                fill = color
            } else {
                // 不显式置空的话 SVGPath 会默认填黑，线条图标会糊成一团色块
                fill = null
                stroke = color
                this.strokeWidth = strokeWidth
                strokeLineCap = StrokeLineCap.ROUND
                strokeLineJoin = StrokeLineJoin.ROUND
            }
            // 按自己的 viewBox 缩放到 [size] 见方并挪到原点：x' = s·(x - minX)
            val s = size / maxOf(data.w, data.h)
            transforms.setAll(Affine(s, 0.0, -data.x * s, 0.0, s, -data.y * s))
        }

        // 必须套一层 Group：上面的 Affine 已经补偿掉「包围盒偏移」，而 StackPane 居中子节点时
        // 会再补偿一次（按子节点 layoutBounds 的 minX/minY），两边叠加 → 图标整体偏出去。
        // Group 的 layoutBounds 含子节点变换，套上它之后补偿只发生一次。
        val box = StackPane(Group(path)).apply {
            minWidth = size
            prefWidth = size
            maxWidth = size
            minHeight = size
            prefHeight = size
            maxHeight = size
            isMouseTransparent = true        // 点图标就是点按钮，别让它抢事件
        }
        return Icon(box, path, strokeWidth != null)
    }
}
