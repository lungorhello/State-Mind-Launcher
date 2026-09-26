package org.example.statemind.core

import javafx.application.Platform
import java.util.concurrent.CopyOnWriteArrayList
import org.example.statemind.core.BackendUtil.JavaInfo

/**
 * Java 运行时的**全局**设置 + 本机扫描结果。
 *
 * 从「首页选 Java」改成了全局设置：整个启动器只有这一份 Java 配置，界面在「设置 · 启动」页。
 * 两种模式：
 *  - [auto] = true ：启动时按游戏版本 json 里要求的 Java 大版本自动匹配（[pickFor]），用户不用管；
 *  - [auto] = false：固定用 [manualPath] 指定的那一份。
 *
 * 扫描（[refresh]）在后台线程跑（[BackendUtil.getJavas] 约 1 秒），扫完回到 JavaFX 线程再通知界面。
 * 界面只订阅 [onChange]，不关心扫描过程，也不需要自己维护一份候选列表。
 *
 * 这是**全局**那一份。将来「版本设置」里会有「跟随全局设置 / 自动选择 / 自己选择」三档，
 * 那时这里就是「跟随全局设置」所指向的值。
 */
object JavaStore {

    /** 已扫到的候选。扫描完成前往里加，只在 JavaFX 线程上改。 */
    private val list = ArrayList<JavaInfo>()

    /** 订阅者。用 CopyOnWrite 免得遍历通知时集合被改。 */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** 候选列表；扫描完成前是空的。 */
    val javas: List<JavaInfo> get() = list

    /** 是否已经扫过一遍 —— 用来区分「还没扫」和「扫了但一个都没有」。 */
    var scanned: Boolean = false
        private set

    /** 是否自动选择（全局开关）。 */
    var auto: Boolean
        get() = Prefs.autoJava
        set(value) {
            if (value == Prefs.autoJava) return
            Prefs.autoJava = value
            // 从「自动」切回「手动」时，如果用户还从没手动选过，就先把自动挑的那份记下来，
            // 免得一关开关就变成「没有 Java 可用」。
            if (!value && manual == null) {
                Prefs.javaHome = (pickFor(null) ?: list.firstOrNull())?.path ?: ""
            }
            notifyChanged()
        }

    /** 手动指定的 Java 目录；空 = 没选过。 */
    var manualPath: String
        get() = Prefs.javaHome
        set(value) {
            if (value == Prefs.javaHome) return
            Prefs.javaHome = value
            notifyChanged()
        }

    /** 手动指定的那一份；指定的目录已经不在扫描结果里（被卸载/挪走）就是 null。 */
    val manual: JavaInfo? get() = list.firstOrNull { it.path == manualPath }

    // ---------- 订阅 ----------

    /** 订阅变化（扫描完成、开关切换、手动改选）。回调在 JavaFX 线程上跑。 */
    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    // ---------- 扫描 ----------

    /** 后台重扫本机 Java，扫完回界面线程通知。重复调用 = 重扫。 */
    fun refresh() {
        Thread {
            val found = runCatching { BackendUtil.getJavas() }.getOrDefault(emptyList())
            Platform.runLater {
                list.clear()
                list.addAll(found)
                scanned = true
                // 手动选的那份不见了：退回候选里的第一个，免得下次启动直接失败
                if (manualPath.isNotBlank() && list.none { it.path == manualPath }) {
                    Prefs.javaHome = list.firstOrNull()?.path ?: ""
                }
                notifyChanged()
            }
        }.apply {
            isDaemon = true
            name = "java-scan"
        }.start()
    }

    // ---------- 选择 ----------

    /** 启动时最终用哪一份。返回 null 表示没得用，由调用方弹提示。 */
    fun resolve(version: String?): JavaInfo? =
        if (auto) pickFor(version) else manual

    /** 按某个游戏版本要求的 Java 挑一份。版本读不出来（老 json 不写 javaVersion）就按「未知」处理。 */
    fun pickFor(version: String?): JavaInfo? =
        choose(list, version?.let { runCatching { LaunchUtil.requiredJavaMajor(it) }.getOrNull() })

    /**
     * 纯挑选逻辑（不碰磁盘、不碰界面，方便单独验证）：
     *  - 要求明确时：优先版本号**完全一致**的那份；没有就挑「比要求高」里面最低的（够用、又最不容易撞
     *    Mixin / ASM 的兼容问题）；只有在全都偏低时才退而求其次挑最高的那份 —— 那份启动前会被
     *    [LaunchUtil.checkJava] 拦下并说明原因，比悄悄起不来强；
     *  - 要求未知时：挑最高的一份（新版本对 Java 要求通常最高）。
     */
    fun choose(candidates: List<JavaInfo>, requiredMajor: Int?): JavaInfo? {
        if (candidates.isEmpty()) return null

        // 版本号认不出来的（如「zulu-17」）排到最后才考虑，但不至于丢掉
        val known = candidates.mapNotNull { c -> LaunchUtil.javaMajor(c.version)?.let { c to it } }
        if (known.isEmpty()) return candidates.first()

        if (requiredMajor == null) {
            return known.maxByOrNull { it.second }!!.first
        }
        known.firstOrNull { it.second == requiredMajor }?.let { return it.first }
        known.filter { it.second > requiredMajor }.minByOrNull { it.second }?.let { return it.first }
        return known.maxByOrNull { it.second }!!.first
    }
}
