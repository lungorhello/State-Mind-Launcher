package org.example.statemind.ui.page.setting

import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Window
import org.example.statemind.core.GameDir
import org.example.statemind.core.GameDirStore
import org.example.statemind.core.InstanceScan
import org.example.statemind.ui.Dialogs
import org.example.statemind.ui.Page
import java.io.File
import java.util.concurrent.Callable

/**
 * 设置 · 实例：游戏目录，以及目录里的版本。
 *
 * 结构：
 *   1. 顶部一行 —— 「添加游戏目录」按钮 + 排列方式下拉（一行一个 / 一行两个）；
 *   2. 下面按目录分组 —— 每组标题是目录昵称（multi 系按实例拆成多组，标题是「昵称 · 实例名」），
 *      组内一个版本一张卡片：左边图标（占位方块）、右边版本名 + 描述（加载器 · 版本号）。
 *
 * 添加流程（[askDirectory]）：「选择文件夹 + 起个名字」→ 点「下一步」当场验证是不是 Minecraft 目录
 * （传统目录 / multi 系都收，见 [GameDir.resolve]）→ 成功弹窗报出识别结果和版本数，
 * 失败则说清原因并**带着已填的内容**退回表单，不用重填。
 *
 * 这一版**只做展示**：卡片点了没反应，启动仍在首页选 —— 但首页会把「版本 + 它所属的目录」
 * 一起带上，所以选了别的目录里的版本，启动用的就是那个目录。
 * 清单 = [GameDirStore.all]（内置的默认目录 + 用户添加的那几条），版本每次刷新现扫（[InstanceScan.scanAll]）。
 */
class SettingInstancePage : Page {

    override val id = "setting.instance"
    override val title = "实例"

    /**
     * 排列方式。以后 SVG 图标做好了再往里补「图标网格」一档 —— 那时卡片左侧的占位方块
     * 换成真图标，网格才有意义（现在两个档位都是无图标列表）。
     */
    enum class ViewMode(private val label: String) {
        LIST("一行一个"),
        GRID("一行两个");

        override fun toString(): String = label
    }

    private val modeBox = ComboBox<ViewMode>().apply {
        items.setAll(ViewMode.entries)
        value = ViewMode.LIST
        prefWidth = 116.0
    }

    /** 分组卡片都挂在这儿，刷新时整体重建。 */
    private val groups = VBox(16.0)

    /** 页面根节点。只为拿窗口给系统文件夹选择框当 owner。 */
    private var root: VBox? = null

    /** 最近一次扫描的结果。换排列方式只重排，不重扫。 */
    private var scanned: List<InstanceScan.Directory> = emptyList()

    override fun build(): Node {
        // ── 1. 顶部一行：添加按钮 + 排列方式 ────────────────────────────────
        val addButton = Button("添加游戏目录").apply {
            style = BTN_PRIMARY
            setOnAction { askDirectory() }
        }
        val viewLabel = Label("排列").apply { style = "-fx-font-size: 13px; -fx-text-fill: #555555;" }
        val topBar = HBox(
            10.0, addButton,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            viewLabel, modeBox
        ).apply { alignment = Pos.CENTER_LEFT }

        modeBox.valueProperty().addListener { _, _, _ -> layoutGroups() }

        // ── 2. 分组列表 ────────────────────────────────────────────────────
        val scroll = ScrollPane(groups).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            style = """
                -fx-background-color: transparent;
                -fx-background: transparent;
                -fx-border-color: transparent;
                -fx-padding: 0;
            """
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val box = VBox(14.0, topBar, scroll).apply { padding = Insets(20.0) }
        root = box
        refresh()
        return box
    }

    /** 每次切进这一页都重扫一遍：目录可能是刚被手改过配置文件、或者在同一台机器上新增了实例。 */
    override fun onEnter() = refresh()

    // ---------- 刷新 ----------

    /**
     * 重扫所有游戏目录（内置的默认目录 + 用户添加的），每个目录摊平成若干分组。
     * 纯文件读，没网络请求；版本数量在几十个的量级上是毫秒级，所以直接在界面线程做。
     */
    private fun refresh() {
        scanned = InstanceScan.scanAll(GameDirStore.all)
        layoutGroups()
    }

    /** 按当前排列方式把分组铺出来。只动节点，不读盘。 */
    private fun layoutGroups() {
        groups.children.clear()
        // 内置的默认目录永远在，所以这里不会是空的
        scanned.forEach { groups.children += groupCard(it, removable = !GameDirStore.isBuiltin(it.rootPath)) }
    }

    /** 一个目录（或一个 multi 系实例）：标题行 + 里面的版本卡片。[removable] 为假的是内置目录。 */
    private fun groupCard(g: InstanceScan.Directory, removable: Boolean): Node {
        val title = Label(g.title).apply {
            style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #1f1f22;"
        }
        val path = Label(g.gameDir.absolutePath).apply {
            style = "-fx-font-size: 12px; -fx-text-fill: #9a9aa0;"
            maxWidth = Double.MAX_VALUE
            // 首选宽度必须压到 0：路径很长，让它按文字撑开的话整行会溢出，
            // JavaFX 就会按比例把同一行的「N 个版本」和「移除」一起压扁（实测它们会变成「...」）。
            // 压到 0 之后这行的首选宽度只由标题决定，路径自己跟着容器宽度省略号收尾。
            prefWidth = 0.0
        }
        val nameBox = VBox(2.0, title, path).apply { minWidth = 0.0 }   // 窄窗口里能被压缩，长路径靠省略号
        HBox.setHgrow(nameBox, Priority.ALWAYS)

        val note = Label(
            if (g.type == GameDir.Type.EMPTY) g.type.label
            else "${g.type.label} · ${g.versions.size} 个版本"
        ).apply { style = "-fx-font-size: 12px; -fx-text-fill: #9a9aa0;" }
        val header = HBox(10.0, nameBox, note).apply { alignment = Pos.CENTER_LEFT }
        if (removable) {
            header.children += Button("移除").apply {
                style = BTN_GHOST_SMALL
                setOnAction { askRemove(g) }
            }
        }

        val cards = VBox(8.0)
        // 路径不在（目录被删/被搬走）时不留空话 —— 组标题上已经写着「路径不存在」了
        fillCards(cards, g.versions, if (g.gameDir.isDirectory) "这个目录里还没有版本。" else "")

        return VBox(10.0, header, cards).apply {
            padding = Insets(12.0)
            style = GROUP_STYLE
        }
    }

    /** 把版本卡片按当前排列方式摆进容器。[emptyHint] 为空表示什么都不说。 */
    private fun fillCards(container: VBox, versions: List<InstanceScan.Version>, emptyHint: String) {
        container.children.clear()
        if (versions.isEmpty()) {
            if (emptyHint.isNotEmpty()) {
                container.children += Label(emptyHint).apply {
                    style = "-fx-font-size: 12px; -fx-text-fill: #9a9aa0;"
                }
            }
            return
        }
        if (modeBox.value != ViewMode.GRID) {
            versions.forEach { container.children += versionCard(it) }
            return
        }
        // 一行两个：prefWidth 归零 + 让它们吃满剩余宽度，两张卡才是严格对半分
        versions.chunked(2).forEach { pair ->
            val row = HBox(8.0)
            pair.forEach { v ->
                val card = versionCard(v)
                card.prefWidth = 0.0
                HBox.setHgrow(card, Priority.ALWAYS)
                row.children += card
            }
            // 单数个时补个空位，最后一张卡不会自己撑满一整行
            if (pair.size == 1) {
                row.children += Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
            }
            container.children += row
        }
    }

    /** 一张版本卡片：左边图标，右边版本名 + 描述。 */
    private fun versionCard(v: InstanceScan.Version): HBox {
        val name = Label(v.id).apply {
            style = "-fx-font-size: 13px; -fx-text-fill: #1f1f22;"
            maxWidth = Double.MAX_VALUE
            prefWidth = 0.0        // 同组标题的路径：长版本名不能把「一行两个」那一行撑爆
        }
        val desc = Label(v.description).apply {
            style = "-fx-font-size: 12px; -fx-text-fill: #7c7c85;"
            maxWidth = Double.MAX_VALUE
            prefWidth = 0.0
        }
        val info = VBox(2.0, name, desc).apply { minWidth = 0.0 }
        HBox.setHgrow(info, Priority.ALWAYS)

        return HBox(11.0, versionIcon(v.loader), info).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(9.0, 12.0, 9.0, 12.0)
            minWidth = 0.0                    // 窄容器里能压缩，别把整行顶出去
            maxWidth = Double.MAX_VALUE
            style = CARD_STYLE
        }
    }

    /**
     * 版本图标 —— **占位**：加载器配色的圆角方块 + 一个字母。
     * 等 SVG 图标做好后，整块换成 `ImageView` 即可（尺寸留的就是 40×40）。
     */
    private fun versionIcon(loader: InstanceScan.Loader): StackPane {
        val text = Label(
            when (loader) {
                InstanceScan.Loader.VANILLA -> "原"
                InstanceScan.Loader.NEOFORGE -> "N"
                InstanceScan.Loader.FORGE -> "F"
                InstanceScan.Loader.FABRIC -> "F"
                InstanceScan.Loader.QUILT -> "Q"
                InstanceScan.Loader.LITELOADER -> "L"
                InstanceScan.Loader.OPTIFINE -> "O"
                InstanceScan.Loader.UNKNOWN -> "?"
            }
        ).apply {
            style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: white;"
        }
        return StackPane(text).apply {
            minWidth = ICON_SIZE
            prefWidth = ICON_SIZE
            maxWidth = ICON_SIZE
            minHeight = ICON_SIZE
            prefHeight = ICON_SIZE
            maxHeight = ICON_SIZE
            style = "-fx-background-color: ${iconColor(loader)}; -fx-background-radius: 9;"
        }
    }

    private fun iconColor(loader: InstanceScan.Loader): String = when (loader) {
        InstanceScan.Loader.VANILLA -> "#6b7280"      // 中性灰
        InstanceScan.Loader.NEOFORGE -> "#d97706"     // 橙
        InstanceScan.Loader.FORGE -> "#55677a"        // 石板蓝
        InstanceScan.Loader.FABRIC -> "#b0894f"       // 麻布色
        InstanceScan.Loader.QUILT -> "#8b5cf6"        // 紫
        InstanceScan.Loader.LITELOADER -> "#7c5c3a"   // 棕
        InstanceScan.Loader.OPTIFINE -> "#5b8c2a"     // 绿
        InstanceScan.Loader.UNKNOWN -> "#9ca3af"
    }

    // ---------- 添加目录 ----------

    /** 表单回填：验证没过时带着上次填的内容重新打开，不用重填。 */
    private data class Prefill(val path: String, val name: String)

    /** 添加游戏目录：选路径 + 起名字 → 「下一步」当场验证。 */
    private fun askDirectory(prefill: Prefill? = null) {
        val window = root?.scene?.window

        val pathField = TextField().apply {
            promptText = "游戏目录的路径"
            text = prefill?.path.orEmpty()
            maxWidth = Double.MAX_VALUE
            style = FIELD_STYLE
        }
        val nameField = TextField().apply {
            promptText = "在启动器里显示的名字"
            text = prefill?.name.orEmpty()
            maxWidth = CONTENT_WIDTH
            style = FIELD_STYLE
        }
        val browse = Button("浏览…").apply {
            style = BTN_GHOST
            setOnAction {
                chooseDirectory(window, pathField.text)?.let { picked ->
                    pathField.text = picked.absolutePath
                    // 名字还空着就顺手填上文件夹名（PCL 也是这个习惯），填过的不动
                    if (nameField.text.isBlank()) nameField.text = defaultName(picked)
                }
            }
        }
        val pathRow = HBox(8.0, pathField, browse).apply { alignment = Pos.CENTER_LEFT }
        HBox.setHgrow(pathField, Priority.ALWAYS)

        val form = VBox(12.0, field("文件夹", pathRow), field("文件夹名称", nameField))

        Dialogs.confirm(
            title = "添加游戏目录",
            body = "",
            confirmText = "下一步",
            extra = form,
            contentHeight = 158.0,
            // 路径或名字还空着就点不动 —— 免得点了才发现没填完
            confirmDisabledWhen = Bindings.createBooleanBinding(
                Callable { pathField.text.isBlank() || nameField.text.isBlank() },
                pathField.textProperty(), nameField.textProperty()
            )
        ) {
            submitDirectory(pathField.text, nameField.text)
        }
    }

    /** 系统文件夹选择框。选完只把路径填进输入框，**验证一律放到「下一步」**（跟用户定的流程一致）。 */
    private fun chooseDirectory(owner: Window?, current: String): File? {
        val chooser = DirectoryChooser().apply {
            title = "选择游戏目录"
            (File(current.trim()).takeIf { it.isDirectory }
                ?: File(System.getProperty("user.home")).takeIf { it.isDirectory })
                ?.let { initialDirectory = it }
        }
        return chooser.showDialog(owner)
    }

    /** 选的是 `.minecraft` 本身时，默认名字取它外面那层文件夹（更像个名字）。 */
    private fun defaultName(dir: File): String =
        if (dir.name == ".minecraft" || dir.name == "minecraft") dir.parentFile?.name ?: dir.name
        else dir.name

    /** 「下一步」：验证 → 通过就入库并弹成功，不通过就说清原因、带原内容退回表单。 */
    private fun submitDirectory(rawPath: String, rawName: String) {
        val path = rawPath.trim()
        val name = rawName.trim()
        val dir = File(path)

        /** 验证没过：说一句人话，然后带着刚才填的内容回到表单。 */
        fun retry(title: String, body: String) {
            Dialogs.confirm(
                title = title,
                body = body,
                kind = Dialogs.Kind.ERROR,
                confirmText = "重新填写",
                cancelText = null
            ) { askDirectory(Prefill(path, name)) }
        }

        if (path.isEmpty() || name.isEmpty()) {
            retry("信息不完整", "路径和名字都要填。")
            return
        }
        if (!dir.isDirectory) {
            retry("找不到这个文件夹", "「$path」不存在，或者不是一个文件夹。")
            return
        }
        if (GameDirStore.hasPath(dir.absolutePath)) {
            retry("已经添加过了", "这个目录已经在列表里了。")
            return
        }
        if (GameDirStore.hasName(name)) {
            retry("名字重复了", "已经有叫「$name」的目录了，换一个名字。")
            return
        }
        if (!GameDir.looksLikeMinecraft(dir)) {
            retry(
                "这不是 Minecraft 目录",
                "「$name」里没找到 versions 或 instances 子目录。\n" +
                        "如果你选的是启动器的数据文件夹，请选到里面的 .minecraft。"
            )
            return
        }

        // 验证过了：摊平 + 扫版本，成功弹窗里要报的就是这一步的结果
        val resolved = GameDir.resolve(dir)
        if (resolved.isEmpty()) {
            retry("这里还没有实例", "「$name」里的 instances 文件夹是空的。")
            return
        }
        val versions = resolved.sumOf { r ->
            if (r.gameDir.isDirectory) InstanceScan.scan(r.gameDir).size else 0
        }
        val type = resolved.first().type

        if (GameDirStore.add(name, path) == null) {
            retry("无法添加", "这个目录或名字刚刚已经被用过了。")
            return
        }

        val summary = if (type == GameDir.Type.MULTIMC) {
            "识别为 multi 系目录（instances 分实例），找到 ${resolved.size} 个实例、$versions 个版本。"
        } else {
            "识别为${type.label}（单目录式），找到 $versions 个版本。"
        }
        Dialogs.info("添加成功", "$summary\n${dir.absolutePath}")
        refresh()
    }

    // ---------- 移除目录 ----------

    private fun askRemove(g: InstanceScan.Directory) {
        val instances = scanned.count { it.rootPath == g.rootPath }
        val body = buildString {
            append("确定要把「").append(g.sourceName).append("」从列表里移除吗？\n")
            if (instances > 1) {
                append("「").append(g.sourceName).append("」下有 ").append(instances)
                    .append(" 个实例，整条目录都会一起消失。\n")
            }
            append("只从启动器列表里移除，硬盘上的文件不会被删除。")
        }
        Dialogs.confirm(
            title = "移除游戏目录",
            body = body,
            kind = Dialogs.Kind.WARN,
            confirmText = "移除"
        ) {
            GameDirStore.remove(g.rootPath)
            refresh()
        }
    }

    // ---------- 小工具 ----------

    private fun field(labelText: String, control: Node): VBox = VBox(
        6.0,
        Label(labelText).apply { style = "-fx-font-size: 13px; -fx-text-fill: #555555;" },
        control
    )

    private companion object {
        /** 版本图标边长。SVG 图标接进来时照这个尺寸做。 */
        const val ICON_SIZE = 40.0

        /** 表单控件宽度上限，跟内置弹窗一致。 */
        const val CONTENT_WIDTH = 376.0

        /** 主按钮：白底紫边（跟「设置 · 玩家」一致）。 */
        const val BTN_PRIMARY =
            "-fx-background-color: #ffffff; -fx-border-color: #7c3aed; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: #2b2b2b; -fx-font-size: 13px; -fx-padding: 8 16 8 16;" +
                    "-fx-cursor: hand;"

        /** 次要按钮：白底灰边（「浏览…」用）。 */
        const val BTN_GHOST =
            "-fx-background-color: #ffffff; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: #52525b; -fx-font-size: 13px; -fx-padding: 8 14 8 14;" +
                    "-fx-cursor: hand;"

        /** 行内小按钮（组标题右侧的「移除」）：透明底 + 细灰边，不抢视线。 */
        const val BTN_GHOST_SMALL =
            "-fx-background-color: transparent; -fx-border-color: #d4d4d8; -fx-border-width: 1;" +
                    "-fx-border-radius: 7; -fx-background-radius: 7;" +
                    "-fx-text-fill: #71717a; -fx-font-size: 12px; -fx-padding: 4 10 4 10;" +
                    "-fx-cursor: hand;"

        /** 分组容器：白卡（跟其它设置页的卡片同一套）。 */
        const val GROUP_STYLE =
            "-fx-background-color: #ffffff; -fx-border-color: #d9d9de; -fx-border-width: 1;" +
                    "-fx-border-radius: 10; -fx-background-radius: 10;"

        /** 版本卡片：淡紫底 + 淡紫描边（跟「设置 · 玩家」里非当前账号那行同色）。 */
        const val CARD_STYLE =
            "-fx-background-color: #faf7ff; -fx-border-color: #cdb6f2; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;"

        /** 弹窗表单里的输入框：跟内置弹窗同一套皮。 */
        const val FIELD_STYLE =
            "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: #cdb6f2;" +
                    "-fx-background-color: #ffffff; -fx-border-width: 1; -fx-font-size: 13px;" +
                    "-fx-padding: 8 10 8 10;"
    }
}
