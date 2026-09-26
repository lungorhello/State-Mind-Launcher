package org.example.statemind

import atlantafx.base.theme.PrimerLight
import javafx.animation.PauseTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Stage
import javafx.util.Duration
import org.example.statemind.core.BackendUtil
import org.example.statemind.core.BackendUtil.JavaInfo
import org.example.statemind.core.Account
import org.example.statemind.core.AccountStore
import org.example.statemind.core.auth.LaunchAuth
import org.example.statemind.core.JavaStore
import org.example.statemind.core.LaunchUtil
import org.example.statemind.core.GameDir
import org.example.statemind.ui.Dialogs
import org.example.statemind.ui.NavBar
import org.example.statemind.ui.Page
import org.example.statemind.ui.PageHost
import org.example.statemind.ui.ThirdPartySignIn
import org.example.statemind.ui.page.DownloadPage
import org.example.statemind.ui.page.HelpPage
import org.example.statemind.ui.page.HomePage
import org.example.statemind.ui.page.SettingPage
import java.util.concurrent.CountDownLatch

class App : Application() {

    /** 当前游戏进程。null 或已退出 = 没有实例在跑。 */
    @Volatile
    private var runningProcess: Process? = null

    private var statusLabel: Label? = null

    /** 末端状态（游戏运行中 / 已取消启动…）显示一会儿后自动清空。 */
    private lateinit var statusClear: PauseTransition

    override fun start(stage: Stage) {
        stage.title = "State Mind Launcher"

        // 全局主题（须在创建 Scene 之前设置，首帧才生效）
        Application.setUserAgentStylesheet(PrimerLight().userAgentStylesheet)

        // 打开启动器即刻补全标准 .minecraft 骨架（无需先点启动），与 PCL 等启动器行为一致
        GameDir.ensure(BackendUtil.minecraftDir)

        val scanning = "(扫描中…)"

        // 游戏版本下拉：先用占位项，扫描在后台跑，不卡界面
        val gameVersion = ComboBox<String>().apply {
            items.add(scanning)
            value = scanning
            isDisable = true
            maxWidth = Double.MAX_VALUE
        }

        val status = Label("").apply {
            style = "-fx-font-size: 12px; -fx-text-fill: #666666;"
            isWrapText = true
        }
        statusLabel = status
        statusClear = PauseTransition(Duration.millis(STATUS_HOLD_MS.toDouble())).apply {
            setOnFinished { statusLabel?.text = "" }
        }

        val launchBtn = Button("启动游戏").apply {
            isDisable = true   // 扫描到版本后再放开
            setOnAction { doLaunch(gameVersion.value, this) }
        }

        // 启动表单。（Java 与玩家名都不在这里了：Java 在「设置 · 启动」，
        // 玩家名取「设置 · 玩家」里的当前账号，见 doLaunch）
        val leftPanel = VBox(
            fieldGroup("游戏版本", gameVersion),
            Region().apply { VBox.setVgrow(this, Priority.ALWAYS) },
            launchBtn,
            status
        ).apply {
            spacing = 14.0
            padding = Insets(16.0)
            prefWidth = 260.0
            style = """
                -fx-background-color: #f4f5f7;
                -fx-border-color: #e2e4e8;
                -fx-border-width: 0 1 0 0;
            """
        }

        // ── 分页（第 1 步：只搭框架，不迁移逻辑）──────────────────────────
        // 左边是导航栏，右边是页面容器；页面懒加载，只有被点开的那个才会 build()。
        // 首页的内容暂时是原来那套启动表单（整块挪过来显示），启动逻辑仍留在本文件里。
        val pages: List<Page> = listOf(HomePage { leftPanel }, DownloadPage(), SettingPage(), HelpPage())
        val pageHost = PageHost(pages)

        val navBar = NavBar(pages) { id -> pageHost.open(id) }
        // 切页只同步选中态 —— 导航栏宽度固定，不再为二级 tab 收窄（84 已经够窄了）
        pageHost.onPageChanged = { id -> navBar.select(id) }

        val content = BorderPane().apply {
            left = navBar      // 导航栏在左侧
            center = pageHost  // 页面容器；首页先显示那套启动表单
        }

        // 内置弹窗的遮罩层：铺在整个窗口最上面，平时隐藏。
        // 不用系统 Alert —— 那种方框是操作系统画的，样式改不动，和启动器两张皮。
        val overlay = StackPane()
        Dialogs.install(overlay)

        // 窗口可以放大，但不能拖得太小：再小左导航 + 二级 tab 就把内容区挤没了。
        // 660 = 84(主导航) + 96(二级 tab) + 480(内容区，够放下玩家页那张横幅卡片和三个按钮)；
        // 560 = 首页表单 + 「设置 · 启动」那种卡片页的舒适下限。想放宽/收紧就改这两个数。
        stage.minWidth = 660.0
        stage.minHeight = 560.0

        stage.scene = Scene(StackPane(content, overlay), 900.0, 620.0)
        stage.show()

        // 默认停在第一个页面（首页）
        pageHost.open(pages.first().id)

        // 后台扫描本机已安装版本（getGameCores 要读一堆 json）。
        // Java 的扫描同样慢，交给 JavaStore 自己跑后台线程 —— 它扫完会通知「设置 · 启动」页，
        // 本文件不用再管那份列表。
        Thread {
            val cores = runCatching { BackendUtil.getGameCores() }.getOrDefault(emptyList())
            JavaStore.refresh()
            Platform.runLater {
                gameVersion.items.clear()
                if (cores.isEmpty()) {
                    gameVersion.items.add("（未检测到已安装版本）")
                    gameVersion.value = gameVersion.items[0]
                    gameVersion.isDisable = true
                } else {
                    gameVersion.items.addAll(cores)
                    gameVersion.value = cores.first()
                    gameVersion.isDisable = false
                }
                // Java 有没有不在这里判：真点启动时再解析，缺 Java 会弹窗说清楚原因
                launchBtn.isDisable = cores.isEmpty()
                setStatus(
                    if (cores.isEmpty()) "未检测到已安装的游戏版本，请先安装一个版本"
                    else "就绪"
                )
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 写状态行。
     * autoClear = true 表示这是末端状态（启动完成 / 取消 / 失败），过一会儿自动消失；
     * 启动过程中的阶段性提示用默认值，会一直留到被下一条覆盖。
     */
    private fun setStatus(text: String, autoClear: Boolean = false) {
        val label = statusLabel ?: return
        label.text = text
        statusClear.stop()
        if (autoClear && text.isNotEmpty()) statusClear.playFromStart()
    }

    /**
     * 启动入口。Java 与玩家名都从**设置**里取，不再由首页表单传进来：
     *  - 玩家名    → 「设置 · 玩家」的当前账号；
     *  - Java      → 「设置 · 启动」的全局设置（自动匹配 / 手动指定）。
     * 缺哪个都用内置弹窗说清楚，不静默失败。
     *
     * 检查项（已有实例、Java 版本）也都用内置弹窗问，用回调串起来，不阻塞等待 —— 界面不会被弹窗卡住。
     */
    private fun doLaunch(version: String?, button: Button) {
        if (version.isNullOrBlank()) {
            setStatus("请先选好游戏版本", autoClear = true)
            return
        }

        val account = AccountStore.current
        if (account == null) {
            Dialogs.warn(
                "还没有玩家账号",
                "请先到「设置 · 玩家」里添加一个离线账号，启动时用它作为玩家名。"
            )
            setStatus("还没有玩家账号，已取消启动", autoClear = true)
            return
        }

        // 账号准备（第三方要联网校验令牌、备 authlib-injector）放后台跑，
        // 备好之后才继续 Java / 实例那些检查
        prepareAccount(account, version, button)
    }

    /**
     * 启动前的账号准备 —— 离线和第三方走同一个接口，差别都收在 [LaunchAuth] 里：
     *  - 离线：直接过；
     *  - 第三方：校验令牌（过期就刷新）→ 备好 authlib-injector → 产出 `-javaagent` 三件套。
     * 会联网，所以整体跑在后台线程，回来再决定是继续启动还是弹窗拦下。
     */
    private fun prepareAccount(account: Account, version: String, button: Button) {
        button.isDisable = true
        setStatus("准备账号…")
        Thread {
            val outcome = LaunchAuth.prepare(account) { msg -> Platform.runLater { setStatus(msg) } }
            Platform.runLater {
                button.isDisable = false
                when (outcome) {
                    is LaunchAuth.Outcome.Ready -> continueLaunch(version, outcome.credentials, button)

                    LaunchAuth.Outcome.NeedsRelogin -> {
                        // 令牌救不回来了，就地弹一个「只问密码」的续登窗 ——
                        // 皮肤站和邮箱都还记在凭证里，不用让用户回设置页重填一遍表单。
                        // 登录成功后重新跑一次账号准备：这时令牌是新的，会走到 Ready 接着启动。
                        setStatus("登录已失效，请重新登录", autoClear = true)
                        ThirdPartySignIn.promptRelogin(account) {
                            prepareAccount(account, version, button)
                        }
                    }

                    is LaunchAuth.Outcome.Failed -> {
                        setStatus("账号准备失败，已取消启动", autoClear = true)
                        Dialogs.error("账号准备失败", outcome.message)
                    }
                }
            }
        }.apply { isDaemon = true; name = "launch-account" }.start()
    }

    /** 账号备好了：接着检查 Java、已在跑的实例，再进入 Java 版本校验与启动。 */
    private fun continueLaunch(version: String, credentials: LaunchAuth.Credentials, button: Button) {
        val java = JavaStore.resolve(version)
        if (java == null) {
            Dialogs.error(
                "没有可用的 Java",
                "没找到能用来启动「$version」的 Java。\n\n" +
                        "请到「设置 · 启动」里指定一个 Java，或者先在本机安装 Java 再重开启动器。"
            )
            setStatus("没有可用的 Java，已取消启动", autoClear = true)
            return
        }

        // 已有实例在跑：提醒后由用户决定是否继续
        val current = runningProcess
        if (current != null && current.isAlive) {
            Dialogs.confirm(
                title = "已有实例正在运行",
                body = "检测到已有游戏实例（进程号 ${current.pid()}）。" +
                        "再次启动会同时运行多个实例，占用更多内存与 CPU。",
                kind = Dialogs.Kind.WARN,
                confirmText = "继续启动"
            ) { checkJavaThenStart(version, java, credentials, button) }
            return
        }
        checkJavaThenStart(version, java, credentials, button)
    }

    /** Java 版本校验：低于最低要求直接拦，高于推荐值先确认一次。 */
    private fun checkJavaThenStart(
        version: String,
        java: JavaInfo,
        credentials: LaunchAuth.Credentials,
        button: Button
    ) {
        val check = LaunchUtil.checkJava(version, java.version)
        if (check == null) {
            startGame(version, java, credentials, button)
            return
        }
        if (check.blocked) {
            Dialogs.error(
                "Java 版本不符",
                "所选 Java 版本低于该版本的最低要求，无法启动。\n\n${check.message}"
            )
            setStatus("Java 版本不符，已取消启动", autoClear = true)
            return
        }
        Dialogs.confirm(
            title = "Java 版本提醒",
            body = check.message,
            kind = Dialogs.Kind.WARN,
            confirmText = "仍要启动"
        ) { startGame(version, java, credentials, button) }
    }

    /** 真正开始启动。前面所有确认都过了之后才会走到这里。 */
    private fun startGame(
        version: String,
        java: JavaInfo,
        credentials: LaunchAuth.Credentials,
        button: Button
    ) {
        // 从这一刻锁住按钮，直到游戏窗口出现，防止加载期间被重复点击
        button.isDisable = true
        setStatus("准备启动…")

        // 启动会阻塞，扔后台跑，界面更新回到主线程
        Thread {
            try {
                val process = LaunchUtil.launch(
                    LaunchUtil.Config(
                        version = version,
                        javaHome = java.path,
                        playerName = credentials.playerName,
                        uuid = credentials.uuid,
                        accessToken = credentials.accessToken,
                        userType = credentials.userType,
                        extraJvmArgs = credentials.extraJvmArgs,
                    )
                ) { msg -> Platform.runLater { setStatus(msg) } }
                runningProcess = process

                // 收着游戏输出：一是秒退时拿来报警，二是靠日志判断窗口有没有出来
                val log = StringBuilder()
                val windowReady = CountDownLatch(1)
                Thread {
                    try {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            if (windowReady.count > 0L &&
                                WINDOW_READY_MARKS.any { line.contains(it) }
                            ) {
                                windowReady.countDown()
                            }
                            synchronized(log) {
                                log.append(line).append('\n')
                                if (log.length > 20000) log.delete(0, log.length - 20000)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true }.start()

                Platform.runLater { setStatus("游戏进程已启动，等待窗口…") }
                awaitWindow(process, windowReady)

                if (!process.isAlive) {
                    // 2 秒内就退了（Java 版本不对、缺库、崩溃），把尾巴日志贴出来
                    val code = process.exitValue()
                    val tail = synchronized(log) { log.toString() }.trim()
                        .lines().takeLast(18).joinToString("\n")
                    runningProcess = null
                    Platform.runLater {
                        setStatus("启动失败（退出码 $code）", autoClear = true)
                        Dialogs.error("启动失败", "游戏进程提前退出了（退出码 $code）\n\n$tail")
                    }
                } else {
                    // 窗口已经出来了，解锁按钮。此时进程仍在跑，再点会走上面的实例检查
                    Platform.runLater {
                        button.isDisable = false
                        setStatus("游戏运行中", autoClear = true)
                    }
                    // 另起一个线程等进程结束，退出后回到空闲状态
                    Thread {
                        try {
                            process.waitFor()
                        } catch (_: Exception) {
                        }
                        runningProcess = null
                        Platform.runLater { setStatus("游戏已退出", autoClear = true) }
                    }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                runningProcess = null
                val msg = e.message ?: e.toString()
                Platform.runLater {
                    setStatus("启动出错", autoClear = true)
                    Dialogs.error("启动出错", msg)
                }
            } finally {
                Platform.runLater { button.isDisable = false }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 等游戏窗口出现。三种情况都会返回：
     * 日志里出现窗口就绪字样、进程已经退出（秒退）、或者等到超时。
     */
    private fun awaitWindow(process: Process, windowReady: CountDownLatch) {
        val deadline = System.currentTimeMillis() + WINDOW_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return
            if (windowReady.count == 0L) return
            Thread.sleep(150)
        }
    }

    private fun fieldGroup(labelText: String, control: Control): VBox {
        return VBox(
            Label(labelText).apply {
                style = "-fx-font-size: 13px; -fx-text-fill: #555555;"
            },
            control
        ).apply {
            spacing = 6.0
        }
    }

    private companion object {
        /** 末端状态在状态行停留多久后自动清空。 */
        const val STATUS_HOLD_MS = 6000L

        /** 等窗口出现的最长时间；超了就当它已经起来了，免得按钮一直锁着。 */
        const val WINDOW_WAIT_MS = 30_000L

        /** 日志里出现这些字样说明渲染窗口已经建好（不同版本措辞略有差异，取常见的几个）。 */
        val WINDOW_READY_MARKS = listOf(
            "Backend library: LWJGL",
            "Sound engine started",
            "OpenGL Vendor"
        )
    }
}
