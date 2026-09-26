package org.example.statemind.ui.page.setting

import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.example.statemind.core.Account
import org.example.statemind.core.AccountStore
import org.example.statemind.core.AccountType
import org.example.statemind.core.AccountStore.NameCheck
import org.example.statemind.core.Prefs
import org.example.statemind.core.auth.AuthStore
import org.example.statemind.core.auth.LoginEmail
import org.example.statemind.ui.Dialogs
import org.example.statemind.ui.Page
import org.example.statemind.ui.ThirdPartySignIn
import org.example.statemind.ui.helpMark
import java.util.concurrent.Callable

/**
 * 设置 · 玩家：账号管理。
 *
 * 结构：
 *   1. 顶部横幅 —— 当前账号（渐变紫底 + 「你好 xxx」+ 登录方式）
 *   2. 一排添加按钮 —— 离线（可用）/ 第三方（可用）/ 微软（占位禁用）
 *   3. 账户卡片 —— 每个账号一行，紫色描边 + 徽标按类型着色（第三方亮黄、离线淡紫）+ 红色删除按钮；账号多了可以滚动
 *
 * 数据来自 [AccountStore]（存在数据根目录的 `accounts.txt`，安装版在 `%APPDATA%\StateMind`、
 * 便携版在解压包内的 `data\`），增删会立刻落盘。
 * 点某一行 = 把它设为当前账号（行边框加粗高亮，横幅跟着变）。
 *
 * 第三方账号的**登录动作**不在这里，在 [ThirdPartySignIn] —— 启动前令牌失效时弹的那个
 * 「重新登录」窗走的是同一套代码，两边共用才不会出现「这边能登、那边登不了」。
 */
class SettingPlayerPage : Page {

    override val id = "setting.player"
    override val title = "玩家"

    /** 横幅上的两行字，账号一变就刷。 */
    private val greetName = Label().apply {
        style = "-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: white;"
    }
    private val greetType = Label().apply {
        style = "-fx-font-size: 13px; -fx-text-fill: #c9b3f2;"
    }

    /** 账号行都塞这儿，刷新时整体重建。 */
    private val rows = VBox(8.0)

    /** 顶部横幅。底色跟当前账号的登录方式走，所以在 [refresh] 里刷。 */
    private val banner = VBox(4.0, greetName, greetType).apply {
        padding = Insets(18.0, 20.0, 18.0, 20.0)
    }

    override fun build(): Node {
        // ── 1. 顶部横幅 ────────────────────────────────────────────────────

        // ── 2. 添加按钮 ────────────────────────────────────────────────────
        val addOffline = Button("添加离线用户").apply {
            style = BTN_PRIMARY
            setOnAction { addOfflineAccount() }
        }
        val addThird = Button("添加第三方登录").apply {
            style = BTN_PRIMARY
            setOnAction { addThirdPartyAccount() }
        }
        val addMicrosoft = Button("添加微软登录").apply {
            style = BTN_MUTED
            isDisable = true
        }
        val buttonRow = HBox(10.0, addOffline, addThird, addMicrosoft)

        // ── 3. 账户卡片 ────────────────────────────────────────────────────
        val cardTitle = Label("管理你的账户").apply {
            style = "-fx-font-size: 14px; -fx-text-fill: #1f1f22;"
        }
        val helpTip = helpMark("点一行可把它设为当前账号")
        val cardHeader = HBox(
            8.0, cardTitle, helpTip,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        ).apply { alignment = Pos.CENTER_LEFT }

        // 账号一多就把卡片撑破页面，所以套一层滚动
        rows.padding = Insets(0.0, 4.0, 0.0, 0.0)
        val scroll = ScrollPane(rows).apply {
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

        val card = VBox(10.0, cardHeader, scroll).apply {
            padding = Insets(14.0)
            style = """
                -fx-background-color: #ffffff;
                -fx-border-color: #d9d9de;
                -fx-border-width: 1;
                -fx-border-radius: 10;
                -fx-background-radius: 10;
            """
        }

        val root = VBox(14.0, banner, buttonRow, card).apply {
            padding = Insets(20.0)
            VBox.setVgrow(card, Priority.ALWAYS)
        }

        refresh()
        return root
    }

    // ---------- 刷新 ----------

    private fun refresh() {
        val cur = AccountStore.current
        // 第三方账号：横幅换金色，小字显示「皮肤站 · 账号」；其余（离线 / 微软）沿用紫色 + 类型名
        val third = cur?.takeIf { it.type == AccountType.THIRD_PARTY }
        val entry = third?.let { AuthStore.find(it.id) }
        banner.style = if (third != null) BANNER_THIRD else BANNER_DEFAULT
        greetType.style = if (third != null) SUB_THIRD else SUB_DEFAULT

        greetName.text = cur?.let { "你好 ${it.name}" } ?: "还没有账号"
        greetType.text = when {
            entry != null -> "${entry.serverLabel} · ${entry.username}"
            cur != null -> cur.type.display
            else -> "点下面的「添加离线用户」创建一个"
        }

        rows.children.clear()
        if (AccountStore.accounts.isEmpty()) {
            rows.children += Label("还没有账号，点上面的「添加离线用户」创建一个。").apply {
                style = "-fx-font-size: 13px; -fx-text-fill: #9a9aa0;"
            }
            return
        }
        AccountStore.accounts.forEach { acc ->
            rows.children += accountRow(acc, acc.id == cur?.id)
        }
    }

    /** 一行账号：左边描边框（名字 + 类型徽标），右边红色删除。 */
    private fun accountRow(acc: Account, isCurrent: Boolean): Node {
        val name = Label(acc.name).apply {
            style = "-fx-font-size: 16px; -fx-text-fill: #1f1f22;"
        }
        val isThird = acc.type == AccountType.THIRD_PARTY
        val badge = Label(acc.type.display).apply {
            // 第三方登录亮黄（和其它登录方式一眼分开）；离线 / 微软沿用主题紫
            style = if (isThird) """
                -fx-background-color: #facc15;
                -fx-background-radius: 6;
                -fx-text-fill: #4a3200;
                -fx-font-size: 12px;
                -fx-padding: 3 10 3 10;
            """ else """
                -fx-background-color: #7c3aed;
                -fx-background-radius: 6;
                -fx-text-fill: white;
                -fx-font-size: 12px;
                -fx-padding: 3 10 3 10;
            """
        }

        val infoBox = HBox(
            8.0, name,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            badge
        ).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 14.0, 10.0, 14.0)
            // 卡片底色只区分「是不是当前账号」，不区分登录方式 —— 登录方式由右边徽标表达，
            // 卡片再染一次色会让整页显得花。
            val tint = if (isCurrent) "#f3ecff" else "#faf7ff"
            style = if (isCurrent) """
                -fx-background-color: $tint;
                -fx-border-color: #7c3aed;
                -fx-border-width: 2;
                -fx-border-radius: 8;
                -fx-background-radius: 8;
            """ else """
                -fx-background-color: $tint;
                -fx-border-color: #cdb6f2;
                -fx-border-width: 1;
                -fx-border-radius: 8;
                -fx-background-radius: 8;
            """
            cursor = Cursor.HAND
            minWidth = 0.0   // 让它在窄容器里能被压缩，不会把整行顶出去
            setOnMouseClicked {
                AccountStore.setCurrent(acc.id)
                refresh()
            }
        }

        val deleteBtn = Button("✕").apply {
            minWidth = 34.0
            prefWidth = 34.0
            minHeight = 34.0
            prefHeight = 34.0
            style = """
                -fx-background-color: #e11d2e;
                -fx-background-radius: 6;
                -fx-text-fill: white;
                -fx-font-size: 14px;
                -fx-font-weight: bold;
            """
            setOnAction { deleteAccount(acc) }
        }

        val row = HBox(10.0, infoBox, deleteBtn).apply { alignment = Pos.CENTER_LEFT }
        HBox.setHgrow(infoBox, Priority.ALWAYS)
        return row
    }

    // ---------- 操作 ----------

    /** 添加离线账号：输入名字 → 校验 → 有风险再确认一次 → 落盘。全程用内置弹窗串联。 */
    private fun addOfflineAccount() {
        Dialogs.prompt(
            title = "添加离线用户",
            body = "给这个离线账号起个名字",
            placeholder = "3 - 16 个字符",
            confirmText = "下一步"
        ) { input -> submitOfflineName(input) }
    }

    private fun submitOfflineName(input: String) {
        when (val check = AccountStore.checkOfflineName(input)) {
            is NameCheck.Invalid -> Dialogs.warn("无法添加", check.reason)

            is NameCheck.Risky -> Dialogs.confirm(
                title = "玩家名提醒",
                body = check.reason,
                kind = Dialogs.Kind.WARN,
                confirmText = "仍要添加"
            ) { addOfflineNow(input) }

            NameCheck.Ok -> addOfflineNow(input)
        }
    }

    private fun addOfflineNow(input: String) {
        if (AccountStore.addOffline(input) == null) {
            Dialogs.error("无法添加", "这个玩家名不能使用。")
        }
        refresh()
    }

    private fun deleteAccount(acc: Account) {
        // 之前勾过「不再提醒」就直接删，不再打扰
        if (!Prefs.confirmDelete) {
            removeNow(acc)
            return
        }
        val noAsk = CheckBox("不再提醒").apply { style = "-fx-font-size: 13px;" }
        Dialogs.confirm(
            title = "删除账号",
            body = "确定要删除「${acc.name}」吗？",
            kind = Dialogs.Kind.WARN,
            confirmText = "删除",
            extra = noAsk
        ) {
            if (noAsk.isSelected) Prefs.confirmDelete = false
            removeNow(acc)
        }
    }

    private fun removeNow(acc: Account) {
        AccountStore.remove(acc.id)
        refresh()
    }

    // ---------- 第三方登录（皮肤站） ----------

    /** 添加第三方账号：填「服务器地址 + 邮箱 + 密码」→ 后台登录 → 存令牌。 */
    private fun addThirdPartyAccount() {
        val server = ThirdPartySignIn.field("皮肤站地址，如 littleskin.cn").apply {
            text = "littleskin.cn"
        }
        // 只收邮箱：皮肤站虽然也认用户名，但卡片小字要显示「皮肤站 · 账号」，
        // 用户名看不出是哪家的账号，所以统一按邮箱收（校验见 LoginEmail）。
        val user = ThirdPartySignIn.field("邮箱")
        val pass = ThirdPartySignIn.passwordField()

        // 邮箱格式提示：**这一行一直在**（只切文字显隐、高度写死），
        // 这样输入过程中卡片不会忽高忽低，「登录」按钮永远停在同一个位置 ——
        // 和内置弹窗「内容区高度恒定」是同一条规矩。
        // 放在**最下面**（密码框和按钮之间）：三个输入框之间就能保持等距，
        // 中间不会多出一块说不清的空当（用户 2026-09-26 定的）。
        val emailHint = Label().apply {
            maxWidth = 376.0
            // 高度固定 16px 好让版式稳定；minHeight 留 0 当安全阀 ——
            // 万一用户系统字体渲染偏高、空间不够，先压这一行（12px 的字看不出来），
            // 而不是去挤三个输入框。
            minHeight = 0.0
            prefHeight = HINT_ROW_HEIGHT
            maxHeight = HINT_ROW_HEIGHT
            style = HINT_STYLE
        }

        // 边打字边校验：格式不对就红字 + 输入框描边转红，格式对了自己消失。
        user.textProperty().addListener { _, _, _ ->
            val problem = LoginEmail.error(user.text)
            emailHint.text = problem.orEmpty()
            user.style = if (problem == null) FIELD_STYLE else FIELD_STYLE_BAD
        }

        Dialogs.confirm(
            title = "添加第三方登录",
            body = "",
            confirmText = "登录",
            extra = VBox(8.0, server, user, pass, emailHint),
            // 刚好装下「标题 + 三个输入框 + 一行提示 + 四个 8px 间距」，不留多余空当
            contentHeight = 178.0,
            // 邮箱没填或格式不对时「登录」是灰的 —— 点了也没用，不如根本不给点
            confirmDisabledWhen = Bindings.createBooleanBinding(
                Callable { !LoginEmail.isValid(user.text) },
                user.textProperty()
            )
        ) {
            // 认证、选角色、落盘都在 ThirdPartySignIn 里（和启动前续登共用同一套）
            ThirdPartySignIn.signIn(server.text, user.text, pass.text) { account ->
                Dialogs.info("已添加", "「${account.name}」已经加入账号列表。")
                refresh()
            }
        }
    }

    private companion object {
        /** 横幅底色 —— 默认主题紫渐变（离线 / 微软）。 */
        const val BANNER_DEFAULT =
            "-fx-background-color: linear-gradient(to right, #26053d, #7c3aed);" +
                    "-fx-background-radius: 10;"

        /** 横幅底色 —— 第三方登录用金色渐变，比徽标黄深一档好让白字压得住。 */
        const val BANNER_THIRD =
            "-fx-background-color: linear-gradient(to right, #42290a, #e2a806);" +
                    "-fx-background-radius: 10;"

        /** 横幅第二行 —— 默认淡紫字（配紫底）。 */
        const val SUB_DEFAULT = "-fx-font-size: 13px; -fx-text-fill: #c9b3f2;"

        /** 横幅第二行 —— 第三方用淡黄字（配金底）。 */
        const val SUB_THIRD = "-fx-font-size: 13px; -fx-text-fill: #fde68a;"

        /** 主按钮：白底紫边（对应「添加离线用户」）。 */
        const val BTN_PRIMARY =
            "-fx-background-color: #ffffff; -fx-border-color: #7c3aed; -fx-border-width: 1;" +
                    "-fx-border-radius: 8; -fx-background-radius: 8;" +
                    "-fx-text-fill: #2b2b2b; -fx-font-size: 13px; -fx-padding: 8 16 8 16;"

        /** 占位按钮：灰底、看着像能点但实际禁用（-fx-opacity 覆盖掉禁用态的透明）。 */
        const val BTN_MUTED =
            "-fx-background-color: #d6d6d9; -fx-background-radius: 8;" +
                    "-fx-text-fill: #2b2b2b; -fx-font-size: 13px; -fx-padding: 8 16 8 16;" +
                    "-fx-opacity: 1;"

        /** 表单输入框的皮 —— 单一来源在 [ThirdPartySignIn]（续登窗也用同一套）。 */
        const val FIELD_STYLE = ThirdPartySignIn.FIELD_STYLE
        const val FIELD_STYLE_BAD = ThirdPartySignIn.FIELD_STYLE_BAD

        /** 邮箱格式提示那一行的字。 */
        const val HINT_STYLE = "-fx-font-size: 12px; -fx-text-fill: #dc2626;"

        /**
         * 邮箱格式提示那一行的高度（写死）。它**始终占着这一格**，只是没错误时不显示字，
         * 所以填错不会把下面的密码框和按钮顶下去。
         */
        const val HINT_ROW_HEIGHT = 16.0
    }
}
