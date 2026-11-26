package org.example.statemind.ui.page.setting

import atlantafx.base.controls.ToggleSwitch
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.PauseTransition
import javafx.animation.Timeline
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.Slider
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration
import javafx.util.StringConverter
import org.example.statemind.core.BackendUtil.JavaInfo
import org.example.statemind.core.GcPreset
import org.example.statemind.core.JavaStore
import org.example.statemind.core.LaunchSelection
import org.example.statemind.core.MachineMemory
import org.example.statemind.core.Prefs
import org.example.statemind.ui.Page
import org.example.statemind.ui.Typo
import org.example.statemind.ui.UsageBar
import org.example.statemind.ui.accentTrack
import org.example.statemind.ui.helpMark
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 设置 · 启动 —— Java 虚拟机。
 *
 * Java 从首页搬到这里，并且从「每次启动临时选」变成**全局设置**：
 *  - 一个开关（Atlantafx 的 [ToggleSwitch]，就是主题自带那个胶囊开关）控制「自动选择 Java 版本」；
 *  - 下面是手动指定的那份 Java（原来首页那个下拉），自动选择开着时整体不生效。
 *
 * 开关**不要**自己再加 `setOnMouseClicked`：Atlantafx 的皮肤自己处理鼠标事件并调用 `fire()`，
 * 再挂一个点击回调会变成双触发（点一下等于没点）。
 *
 * 真正的挑选逻辑在 [JavaStore]：它管扫描、管落盘、管按游戏版本匹配，本页只负责显示和转发点击。
 * 将来「版本设置」里会有「跟随全局设置 / 自动选择 / 自己选择」三档，这里就是「全局」那一份。
 *
 * ## 为什么不是「一个下拉干到底」
 *
 * `ComboBox` 只在**选中项确实存在于 `items` 里**的时候才稳定地画出文字：
 *  - 选中值不在 `items` 里 → 选择模型会把它清成 `null`（`ListCell` 随之被重置）；
 *  - `value == null` 时走提示文字（`promptText`）那条路 —— 这条路在部分机器上**一个字都不画**。
 *
 * 上面两种情况恰好就是「自动选择开着」「一份 Java 都没扫到」这两个空态，也就是 0.2 起
 * 那个「下拉一片空白」。所以这里不再让下拉硬扛空态：
 *
 *  - 只有**真的能挑一份**（关掉自动选择、且至少扫到一份 Java）时，才让下拉登场，
 *    并且选中值一定取自 `items` 自己，绝不给它一个列表外的对象；
 *  - 其余状态一律换成 [field] —— 一块**只读文字**（`Label`），文字永远不会画不出来。
 *
 * 两者叠在同一个 [slot] 里、且**都参与布局**，只切换 `isVisible`：格子高度始终按最高的
 * 那个算，所以状态切换时版面不会跳一下。
 */
class SettingLaunchPage : Page {

    override val id = "setting.launch"
    override val title = "启动"

    /**
     * 自动选择开关。尺寸与动画仍来自主题的 `.toggle-switch` 规则，
     * 只把强调色（`-color-accent-emphasis`，主题默认是蓝色）换成启动器主题紫（淡）。
     */
    private val toggle = ToggleSwitch().apply {
        style = "-color-accent-emphasis: $ACCENT_SOFT;"
    }

    /** 手动指定的 Java。只在 [sync] 判定「可以挑」的时候才露出来。 */
    private val combo = ComboBox<JavaInfo>()

    /**
     * 空态显示的那块只读文字。样式仿输入框外形，但底色压灰，一眼看得出「现在没得选」。
     * 设成鼠标穿透：它只是一块牌子，点击不该被它拦下。
     */
    private val field = Label().apply {
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
        padding = Insets(0.0, 10.0, 0.0, 10.0)
        alignment = Pos.CENTER_LEFT
        isMouseTransparent = true
        style = """
            -fx-background-color: #f6f6f8;
            -fx-border-color: #dcdce2;
            -fx-border-width: 1;
            -fx-border-radius: 6;
            -fx-background-radius: 6;
            -fx-text-fill: #6b6b73;
            -fx-font-size: 13px;
        """
    }

    /** 下拉与只读文字共用的一格。两个都留在布局里，切换时高度不变。 */
    private val slot = StackPane(combo, field)

    // ── 内存分配 ──────────────────────────────────────────────────────────

    private val autoRadio = RadioButton("自动配置")
    private val customRadio = RadioButton("自定义")

    /** 自动那一行的读数。滑条只属于「自定义」，自动值就写在它自己那一行。 */
    private val autoReadout = Label().apply {
        style = Typo.DIM
        alignment = Pos.CENTER_RIGHT
    }

    private val memorySlider = Slider(
        MachineMemory.MIN_MB.toDouble(),
        MachineMemory.MAX_MB.toDouble(),
        MachineMemory.DEFAULT_MB.toDouble()
    ).apply {
        maxWidth = Double.MAX_VALUE
        isShowTickMarks = false
        isShowTickLabels = false
    }.accentTrack()

    private val memoryReadout = Label().apply {
        style = Typo.DIM
        minWidth = 78.0
        alignment = Pos.CENTER_RIGHT
    }

    /** 已用（灰）与游戏分配（紫）接在同一条上：底是浅灰余量。 */
    private val usageBar = UsageBar()

    private val usedLabel = Label().apply { style = Typo.DIM }
    private val allocLabel = Label().apply { style = Typo.DIM }

    /** 上一次读到的系统占用比例；拖滑条时拿它重画，不必每动一格就去问一次系统。 */
    private var usedFraction = 0.0

    /** 程序改滑条值时不要反过来写盘。 */
    private var memoryUpdating = false

    /** 拖动中的值；停手 300 ms 才落盘，每滑一格写一次文件太吵。 */
    private var pendingMemoryMb = MachineMemory.DEFAULT_MB
    private val memoryCommit = PauseTransition(Duration.millis(300.0)).apply {
        setOnFinished { if (pendingMemoryMb > 0) Prefs.memoryMb = pendingMemoryMb }
    }

    /** GC 方案。内容随当前 Java 版本变：21 以下不列分代 ZGC。 */
    private val gcCombo = ComboBox<GcPreset>().apply {
        prefWidth = 170.0
        maxWidth = 170.0
        converter = object : StringConverter<GcPreset>() {
            override fun toString(value: GcPreset?): String = value?.label.orEmpty()
            override fun fromString(text: String?): GcPreset? = null
        }
        valueProperty().addListener { _, _, now ->
            if (now != null) {
                Prefs.gc = now
                updateGcStatus()
            }
        }
    }

    /** GC 下拉下面那一行：说清选中的档位到底会做什么。 */
    private val gcStatus = Label().apply { style = Typo.NOTE }

    /**
     * 页面在屏幕上时每两秒读一次系统占用。
     *
     * 读的是 OS 对象上的一个数字（一次系统调用），比一次布局便宜得多。切走就停 ——
     * 停在后台还挂一个定时器没有意义。
     */
    private val memoryTick = Timeline(
        KeyFrame(Duration.seconds(2.0), EventHandler { refreshUsage() })
    ).apply { cycleCount = Animation.INDEFINITE }

    /** PageHost 是懒加载 + 缓存，正常只会 build 一次；这里再兜一道，免得重复订阅。 */
    private var wired = false

    override fun build(): Node {
        // ── 小标题 ────────────────────────────────────────────────────────
        val heading = Label("Java虚拟机").apply { style = Typo.HEADING }

        // ── 自动选择：一行「标题 + ? + 开关」 ──────────────────────────────
        val autoTitle = Label("自动选择 Java 版本").apply { style = Typo.LABEL }
        toggle.selectedProperty().addListener { _, _, now -> JavaStore.auto = now }
        val autoRow = HBox(
            10.0,
            autoTitle,
            helpMark("开启后按游戏版本自动选择合适的 Java；关闭后使用手动指定的 Java。"),
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            toggle
        ).apply { alignment = Pos.CENTER_LEFT }

        // ── 手动指定 ──────────────────────────────────────────────────────
        val javaTitle = Label("Java 版本").apply { style = Typo.LABEL }
        val javaNote = Label("在自动选择关闭后全局生效").apply { style = Typo.NOTE }
        val javaHeader = HBox(9.0, javaTitle, javaNote).apply { alignment = Pos.BASELINE_LEFT }

        combo.apply {
            maxWidth = Double.MAX_VALUE
            converter = object : StringConverter<JavaInfo>() {
                override fun toString(value: JavaInfo?): String = value?.toString().orEmpty()
                override fun fromString(text: String?): JavaInfo? = null
            }
            // 下拉里的一项被选中 → 记进全局设置
            valueProperty().addListener { _, _, now ->
                if (now != null && now.path.isNotBlank()) JavaStore.manualPath = now.path
            }
        }

        // ── 组装卡片 ──────────────────────────────────────────────────────
        val divider = Region().apply {
            minHeight = 1.0
            prefHeight = 1.0
            maxHeight = 1.0
            style = "-fx-background-color: #ececf0;"
        }

        val card = VBox(12.0, autoRow, divider, javaHeader, slot).apply {
            padding = Insets(14.0)
            style = CARD
        }

        // ── 版本列表：共享原版材料要不要摆出来 ────────────────────────────
        val listHeading = Label("版本列表").apply { style = Typo.HEADING }
        val baseTitle = Label("显示共享原版文件").apply { style = Typo.LABEL }
        val baseToggle = ToggleSwitch().apply {
            style = "-color-accent-emphasis: $ACCENT_SOFT;"
            isSelected = Prefs.showBaseVersions
            selectedProperty().addListener { _, _, now -> Prefs.showBaseVersions = now }
        }
        val baseRow = HBox(
            10.0,
            baseTitle,
            helpMark("原版版本与加载器版本共用的那份游戏本体。文件只存一份，列出来只是方便查看。"),
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            baseToggle
        ).apply { alignment = Pos.CENTER_LEFT }

        val listCard = VBox(baseRow).apply {
            padding = Insets(14.0)
            style = CARD
        }

        // ── 内存分配 ──────────────────────────────────────────────────────
        val memHead = Label("内存分配").apply { style = Typo.HEADING }

        ToggleGroup().apply {
            autoRadio.toggleGroup = this
            customRadio.toggleGroup = this
        }
        autoRadio.setOnAction { pickMemory(true) }
        customRadio.setOnAction { pickMemory(false) }

        memorySlider.valueProperty().addListener { _, _, now ->
            if (memoryUpdating) return@addListener
            val mb = ((now.toDouble() / MachineMemory.STEP).roundToInt() * MachineMemory.STEP)
                .coerceIn(MachineMemory.MIN_MB, memorySlider.max.toInt())
            if (mb != pendingMemoryMb) {
                pendingMemoryMb = mb
                memoryCommit.playFromStart()
            }
            memoryReadout.text = gb(mb)
            paintUsage()
        }

        // 单选在左、内容在右。GridPane 的第一列按最宽的那个单选定宽，
        // 两条滑条的起点因此对齐 —— HBox 里塞一个 VBox 是做不到这一点的。
        val autoLine = HBox(autoReadout).apply {
            alignment = Pos.CENTER_RIGHT
            maxWidth = Double.MAX_VALUE
        }
        val customLine = HBox(12.0, memorySlider, memoryReadout).apply {
            alignment = Pos.CENTER_LEFT
            maxWidth = Double.MAX_VALUE
        }
        HBox.setHgrow(memorySlider, Priority.ALWAYS)

        val memGrid = GridPane().apply {
            hgap = 14.0
            vgap = 10.0
            columnConstraints.add(ColumnConstraints().apply { hgrow = Priority.NEVER })
            columnConstraints.add(ColumnConstraints().apply {
                hgrow = Priority.ALWAYS
                setFillWidth(true)
            })
            add(autoRadio, 0, 0)
            add(autoLine, 1, 0)
            add(customRadio, 0, 1)
            add(customLine, 1, 1)
        }

        val legend = HBox(
            6.0,
            dot(USED_BAR),
            usedLabel,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            dot(ACCENT_SOFT),
            allocLabel
        ).apply { alignment = Pos.CENTER_LEFT }

        val gcRow = HBox(
            10.0,
            Label("GC 方案").apply { style = Typo.LABEL },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            gcCombo
        ).apply { alignment = Pos.CENTER_LEFT }

        // 选中的档位到底做了什么，直接写在下面一行。工具提示要点开才看得见，这里不用。
        val gcBlock = VBox(6.0, gcRow, gcStatus)

        val memDivider = Region().apply {
            minHeight = 1.0
            prefHeight = 1.0
            maxHeight = 1.0
            style = "-fx-background-color: #ececf0;"
        }

        val memCard = VBox(12.0, memGrid, legend, usageBar, memDivider, gcBlock).apply {
            padding = Insets(14.0)
            style = CARD
        }

        val root = VBox(14.0, heading, card, listHeading, listCard, memHead, memCard).apply {
            padding = Insets(20.0)
        }

        if (!wired) {
            wired = true
            JavaStore.onChange { sync() }          // 扫描完成 / 开关切换都会回到这里刷界面
            LaunchSelection.onChange { sync() }    // 首页换版本 → 「将使用 Java x.y.z」跟着变
        }
        sync()
        syncMemory()
        return root
    }

    /** 首页可能刚换过版本 —— 回来时重算一遍「将使用哪份 Java」，内存读数也顺手刷新。 */
    override fun onEnter() {
        sync()
        syncMemory()
        if (memoryTick.status != Animation.Status.RUNNING) memoryTick.play()
    }

    /** 切走就停：这条定时器只在看得见的时候有用。 */
    override fun onLeave() = memoryTick.stop()

    /** 按当前状态刷一遍界面。幂等，可以随便调。 */
    private fun sync() {
        val auto = JavaStore.auto
        // 只在真的不一致时才赋值 —— 否则会反过来再触发一遍开关的监听
        if (toggle.isSelected != auto) toggle.isSelected = auto

        val javas = JavaStore.javas

        // 有没有实例先判：一个版本都没装的时候，报「将使用 Java 25.0.1」是句空话
        field.text = when {
            !JavaStore.scanned -> "扫描中…"
            !JavaStore.hasInstance -> "暂无实例"
            javas.isEmpty() -> "没有可用 Java"
            !auto -> "请选择 Java"
            else -> "将使用 Java ${JavaStore.pickFor(LaunchSelection.current)?.version.orEmpty()}"
        }

        // 只有「关掉自动选择 + 至少有一份 Java」才让下拉登场，其余一律用只读文字
        val selectable = !auto && javas.isNotEmpty()

        // 内容真的变了才 setAll —— 直接重建会把选中项冲掉，白白触发一轮变更事件
        if (combo.items.toList() != javas) combo.items.setAll(javas)

        // 选中值**只能**取自 items 自己：给列表外的对象，选择模型会把它清成 null，文字就没了
        val want = if (selectable) javas.firstOrNull { it.path == JavaStore.manual?.path } ?: javas.first() else null
        if (combo.value != want) combo.value = want

        field.isVisible = !selectable
        combo.isVisible = selectable
        syncGc()
    }

    /** 切自动 / 自定义。切到自定义时从没调过就从自动值起步，不让滑条突然蹦到最小值。 */
    private fun pickMemory(auto: Boolean) {
        if (Prefs.memoryAuto == auto) return
        if (!auto && Prefs.memoryMb <= 0) Prefs.memoryMb = MachineMemory.autoMb()
        Prefs.memoryAuto = auto
        syncMemory()
    }

    /**
     * 刷内存那一块。分配值来自设置，系统占用由 [memoryTick] 每两秒补一次。
     */
    private fun syncMemory() {
        val total = MachineMemory.totalMb
        val auto = Prefs.memoryAuto

        // 只在真不一致时赋值 —— 否则会反过来再触发一遍单选的选中状态
        if (autoRadio.isSelected != auto) autoRadio.isSelected = auto
        if (customRadio.isSelected == auto) customRadio.isSelected = !auto

        memoryUpdating = true
        try {
            if (total > 0) {
                // 上限给「物理内存 − 1 GB」，别让滑条拉到一个注定起不来的数
                memorySlider.max = (total - 1024L).coerceIn(1024L, MachineMemory.MAX_MB.toLong()).toDouble()
            }
            memorySlider.isDisable = auto
            // 冻结（自动）时也把自定义值摆回滑条上：切回自定义要能接上离散之前的位置，
            // 否则滑条被自动值顶走，一切回来就跳了。
            val want = if (Prefs.memoryMb > 0) Prefs.memoryMb else MachineMemory.autoMb()
            memorySlider.value = want.toDouble().coerceIn(memorySlider.min, memorySlider.max)
        } finally {
            memoryUpdating = false
        }

        pendingMemoryMb = memorySlider.value.toInt()
        memoryReadout.text = gb(pendingMemoryMb)
        syncGc()
        refreshUsage()
    }

    /** 当前这份 Java 够不着的档位直接不列 —— 列出来再禁用，比不列更让人猜。 */
    private fun syncGc() {
        val major = JavaStore.pickFor(LaunchSelection.current)?.version?.let(GcPreset::majorOf) ?: 0
        val available = GcPreset.entries.filter { major <= 0 || it.minJava <= major }
        if (gcCombo.items.toList() != available) gcCombo.items.setAll(available)
        val want = Prefs.gc.takeIf { it in available } ?: GcPreset.DEFAULT
        if (gcCombo.value != want) gcCombo.value = want
        updateGcStatus()
    }

    private fun updateGcStatus() {
        gcStatus.text = when (Prefs.gc) {
            GcPreset.DEFAULT -> "当前：JVM 将自动选择"
            GcPreset.G1 -> "当前：G1，按 Aikar 参数调优"
            GcPreset.ZGC -> "当前：分代 ZGC"
        }
    }

    /** 这次启动真正会分到多少：自动档取自动值，自定义档取滑条上的数。 */
    private val effectiveMb: Int
        get() = if (Prefs.memoryAuto) MachineMemory.autoMb() else pendingMemoryMb

    /** 读一次系统占用。只有定时器和进页面时调用。 */
    private fun refreshUsage() {
        val total = MachineMemory.totalMb
        val used = MachineMemory.usedMb
        usedFraction = fraction(used.toDouble())

        // 自动值看的是「现在还能空出多少」，所以每次读数都重算一遍。
        // 只动自动那一行的读数：滑条在冻结时保持自定义值，切回来才不会跳。
        autoReadout.text = gb(MachineMemory.autoMb())

        usedLabel.text = if (total > 0) "已使用 ${gb(used)} / ${gb(total)}" else "已使用内存读取失败"
        paintUsage()
    }

    /**
     * 已用在前、分配在后，剩下的浅灰就是余量。
     * 一段都没画出来说明那一项真的是 0，不是样式没生效。
     */
    private fun paintUsage() {
        val allocMb = effectiveMb
        val free = MachineMemory.freeMb
        // 分配超过可用时把「可用多少」一起写出来 —— 只写分配值，玩家看不出这一下会换来多少换页
        allocLabel.text = if (allocMb > free) {
            "游戏分配 ${gb(allocMb)} · 可用 ${gb(free)}"
        } else {
            "游戏分配 ${gb(allocMb)}"
        }
        allocLabel.style = if (allocMb > free) WARN_TEXT else Typo.DIM
        usageBar.update(usedFraction, fraction(allocMb.toDouble()))
    }

    /** 占物理内存的比例，画那条叠起来的条用；总量读不到就是 0。 */
    private fun fraction(mb: Double): Double {
        val total = MachineMemory.totalMb
        return if (total > 0) (mb / total).coerceIn(0.0, 1.0) else 0.0
    }

    private fun dot(color: String) = Region().apply {
        minWidth = 8.0
        prefWidth = 8.0
        maxWidth = 8.0
        minHeight = 8.0
        prefHeight = 8.0
        maxHeight = 8.0
        style = "-fx-background-color: $color; -fx-background-radius: 4;"
    }

    private fun gb(mb: Number): String = String.format(Locale.ROOT, "%.1f GB", mb.toDouble() / 1024.0)

    private companion object {
        /** 白底、1px 描边、10 圆角 —— 本页三张卡共用。 */
        const val CARD = """
            -fx-background-color: #ffffff;
            -fx-border-color: #d9d9de;
            -fx-border-width: 1;
            -fx-border-radius: 10;
            -fx-background-radius: 10;
        """

        /**
         * 启动器主题紫（淡）—— 界面上的强调色统一从这里取。
         * 用「-color-accent-emphasis」这个名字覆盖主题自带的强调色变量，只影响挂上它的那棵子树。
         */
        const val ACCENT_SOFT = "#a78bfa"

        /** 系统已用那段的灰。 */
        const val USED_BAR = "#b6b9c4"

        /** 分配超过可用时的提示色（暗橙）：比红温和，又比灰醒目。 */
        const val WARN_TEXT = Typo.DIM + " -fx-text-fill: #b07400;"
    }
}
