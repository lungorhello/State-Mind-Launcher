package org.example.statemind.ui

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.scene.control.PasswordField
import javafx.scene.control.RadioButton
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.VBox
import org.example.statemind.core.Account
import org.example.statemind.core.AccountStore
import org.example.statemind.core.Prefs
import org.example.statemind.core.auth.AuthException
import org.example.statemind.core.auth.AuthServer
import org.example.statemind.core.auth.AuthStore
import org.example.statemind.core.auth.LoginEmail
import org.example.statemind.core.auth.Profile
import org.example.statemind.core.auth.Session
import org.example.statemind.core.auth.YggdrasilApi
import java.util.concurrent.Callable

/**
 * 第三方（皮肤站）账号的「登录动作」—— 界面上有两处要用它：
 *  - 「设置 · 玩家」里**添加**一个新账号（[signIn]）；
 *  - 启动时令牌失效，弹出**续登**窗（[promptRelogin]）。
 *
 * 两处的差别只有一个 [Account] 参数：传 null 就是新建一条账号（`accounts.txt` 加一行），
 * 传已存在的账号就只换令牌（`auth.json` 里那条 accessToken 换新），账号清单不动 ——
 * 所以不必写两套登录流程，也不会出现「续登完列表里多出一个重复账号」。
 *
 * 密码**只在这一次请求里用**，用完就丢：落盘的只有服务器发的 accessToken。
 * 认证、选角色、刷新令牌都在后台线程跑，回调一律回到 JavaFX 线程。
 *
 * 两个 [signIn] 都把**成功回调放在最后一个参数**（失败那个有默认实现）：这样
 * `signIn(...) { account -> ... }` 里的尾随 lambda 就是成功分支 ——
 * 顺序反过来的话尾随 lambda 会悄悄绑到 `onError` 上，编译期才发现（踩过一次）。
 */
object ThirdPartySignIn {

    /** 皮肤站表单输入框的皮：跟内置弹窗里的输入框同一套。 */
    const val FIELD_STYLE =
        "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: #cdb6f2;" +
                "-fx-background-color: #ffffff; -fx-border-width: 1; -fx-font-size: 13px;" +
                "-fx-padding: 8 10 8 10;"

    /** 同上，但描边转红 —— 邮箱格式不对时换成它。 */
    const val FIELD_STYLE_BAD =
        "-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: #dc2626;" +
                "-fx-background-color: #fff8f8; -fx-border-width: 1; -fx-font-size: 13px;" +
                "-fx-padding: 8 10 8 10;"

    /** 表单输入框（「添加第三方登录」弹窗里那三个）。 */
    fun field(prompt: String): TextField = TextField().apply {
        promptText = prompt
        maxWidth = FORM_WIDTH
        style = FIELD_STYLE
    }

    /** 密码输入框。 */
    fun passwordField(prompt: String = "密码"): PasswordField = PasswordField().apply {
        promptText = prompt
        maxWidth = FORM_WIDTH
        style = FIELD_STYLE
    }

    // ---------- 登录 ----------

    /**
     * 用户手填地址的那种（设置页添加账号）。地址要先解析出真正的 API 根（ALI 机制），
     * 这步会联网，所以和认证一起放在后台线程里。
     */
    fun signIn(
        serverAddress: String,
        username: String,
        password: String,
        existing: Account? = null,
        onError: (Throwable) -> Unit = { e -> showError(e) },
        onSuccess: (Account) -> Unit
    ) {
        val ctx = prepare(serverAddress, username, password, existing, onSuccess, onError) ?: return
        run(ctx) { AuthServer.locate(serverAddress.trim()) }
    }

    /**
     * 服务器已经解析好的那种（启动前续登）：皮肤站地址和邮箱都记在凭证里，
     * 直接拿来用，省掉一次地址解析请求。
     */
    fun signIn(
        server: AuthServer,
        username: String,
        password: String,
        existing: Account? = null,
        onError: (Throwable) -> Unit = { e -> showError(e) },
        onSuccess: (Account) -> Unit
    ) {
        val ctx = prepare(server.apiRoot, username, password, existing, onSuccess, onError) ?: return
        run(ctx) { server }
    }

    /**
     * 令牌失效后的**续登窗**：只问密码 —— 皮肤站和邮箱都从凭证里取，不让用户再填一遍表单。
     *
     * 凭证本身丢了（`auth.json` 被清过 / 手工改坏）时不硬来，提示回设置页重新添加。
     * 取消什么都不发生；登录成功后 [onSuccess] 拿到的账号就是原来那条（id 不变）。
     */
    fun promptRelogin(account: Account, onSuccess: (Account) -> Unit) {
        val entry = AuthStore.find(account.id)
        if (entry == null) {
            Dialogs.warn(
                "需要重新添加",
                "「${account.name}」的登录信息已经不完整，请到「设置 · 玩家」里重新添加一次。"
            )
            return
        }

        val pass = passwordField()
        Dialogs.confirm(
            title = "重新登录",
            body = "「${entry.serverLabel}」上的登录已失效，请输入 ${entry.username} 的密码。",
            kind = Dialogs.Kind.WARN,
            confirmText = "登录并启动",
            extra = pass,
            // 密码空着时把按钮按住：点了也没用，不如根本不给点
            confirmDisabledWhen = Bindings.createBooleanBinding(
                Callable { pass.text.isBlank() },
                pass.textProperty()
            )
        ) {
            signIn(entry.toServer(), entry.username, pass.text, existing = account, onSuccess = onSuccess)
        }
    }

    // ---------- 内部 ----------

    /** 一次登录的上下文。省得每个内部方法都拖一长串参数。 */
    private class Ctx(
        val clientToken: String,
        val username: String,
        val password: String,
        val existing: Account?,
        /** 上次用的角色 —— 多角色时默认选它，用户不用重新挑。 */
        val preferredProfileId: String?,
        val onSuccess: (Account) -> Unit,
        val onError: (Throwable) -> Unit
    )

    /** 表单校验。不通过就弹一句提示并返回 null（这时候还没开始联网）。 */
    private fun prepare(
        serverAddress: String,
        username: String,
        password: String,
        existing: Account?,
        onSuccess: (Account) -> Unit,
        onError: (Throwable) -> Unit
    ): Ctx? {
        val address = serverAddress.trim()
        val user = username.trim()
        if (address.isEmpty() || user.isEmpty() || password.isEmpty()) {
            Dialogs.warn("信息不完整", "服务器地址、邮箱、密码都要填。")
            return null
        }
        // 「登录」按钮正常情况下点不到（表单里已按格式置灰），这里是最后一道保险
        LoginEmail.error(user)?.let { problem ->
            Dialogs.warn("邮箱格式不对", problem)
            return null
        }
        return Ctx(
            clientToken = Prefs.yggdrasilClientToken,
            username = user,
            password = password,
            existing = existing,
            preferredProfileId = existing?.let { AuthStore.find(it.id)?.profileId },
            onSuccess = onSuccess,
            onError = onError
        )
    }

    /** 解析服务器 → 认证。网络请求不能占着界面线程，全程后台。 */
    private fun run(ctx: Ctx, resolve: () -> AuthServer) {
        Thread {
            val outcome = runCatching {
                val server = resolve()
                server to YggdrasilApi.authenticate(server, ctx.username, ctx.password, ctx.clientToken)
            }
            Platform.runLater {
                outcome.fold(
                    onSuccess = { (server, session) -> bind(ctx, server, session) },
                    onFailure = ctx.onError
                )
            }
        }.apply { isDaemon = true; name = "third-party-login" }.start()
    }

    /** 认证过了：会话里已经有选中的角色就直接存，没有就让用户挑一个。 */
    private fun bind(ctx: Ctx, server: AuthServer, session: Session) {
        if (session.selected != null) {
            persist(ctx, server, session)
            return
        }
        when (session.available.size) {
            0 -> Dialogs.warn(
                "没有角色",
                "这个账号在「${server.name}」上还没有角色，先去它的网站创建一个再来登录。"
            )

            1 -> select(ctx, server, session, session.available.first())
            else -> askProfile(ctx, server, session)
        }
    }

    /** 一个账号多个角色时，列出来让用户挑（默认选上次用的那个）。 */
    private fun askProfile(ctx: Ctx, server: AuthServer, session: Session) {
        val group = ToggleGroup()
        val options = VBox(6.0)
        session.available.forEach { profile ->
            options.children += RadioButton(profile.name).apply {
                toggleGroup = group
                userData = profile
                style = "-fx-font-size: 13px;"
            }
        }
        val radios = options.children.filterIsInstance<RadioButton>()
        val initial = radios.firstOrNull { (it.userData as Profile).id == ctx.preferredProfileId }
            ?: radios.first()
        initial.isSelected = true

        Dialogs.confirm(
            title = "选择角色",
            body = "这个账号在「${server.name}」上有好几个角色，用哪个？",
            confirmText = "就用它",
            extra = options,
            contentHeight = 175.0
        ) {
            (group.selectedToggle?.userData as? Profile)?.let {
                select(ctx, server, session, it)
            }
        }
    }

    /** 把选中的角色绑到令牌上：refresh 会让服务器把这个角色记为「当前使用」。 */
    private fun select(ctx: Ctx, server: AuthServer, session: Session, pick: Profile) {
        Thread {
            val outcome = runCatching {
                YggdrasilApi.refresh(server, session.accessToken, ctx.clientToken, pick)
            }
            Platform.runLater {
                outcome.fold(
                    onSuccess = { persist(ctx, server, it) },
                    onFailure = ctx.onError
                )
            }
        }.apply { isDaemon = true; name = "third-party-select" }.start()
    }

    /**
     * 落盘：账号清单（`accounts.txt`）+ 令牌仓库（`auth.json`）两边都写。
     * 续登时**只碰令牌**那条记录，账号清单原样不动。
     */
    private fun persist(ctx: Ctx, server: AuthServer, session: Session) {
        val player = session.selected ?: return
        try {
            val account = if (ctx.existing == null) {
                val created = AccountStore.addThirdParty(player.name, server.name)
                AuthStore.saveFromSession(created.id, server, ctx.username, session)
                created
            } else {
                AuthStore.saveFromSession(ctx.existing.id, server, ctx.username, session)
                ctx.existing
            }
            ctx.onSuccess(account)
        } catch (e: Throwable) {
            ctx.onError(e)
        }
    }

    /** 默认的失败处理：把异常翻译成一句人话再弹出来。 */
    private fun showError(e: Throwable) {
        val message = (e as? AuthException)?.userText?.takeIf { it.isNotBlank() }
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: "未知错误"
        Dialogs.error("登录失败", message)
    }

    /** 表单宽度 —— 和内置弹窗的内容区同宽，两边看起来是一套东西。 */
    private const val FORM_WIDTH = 376.0
}
