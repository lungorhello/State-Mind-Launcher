package org.example.statemind.core

import javafx.application.Platform
import java.util.concurrent.CopyOnWriteArrayList
import org.example.statemind.core.BackendUtil.JavaInfo

/**
 * 全局唯一的 Java 设置与本机扫描结果，界面在「设置 · 启动」页。扫描在后台线程跑，
 * 界面订阅 [onChange] 拿结果，不用自己维护候选列表。
 */
object JavaStore {

    /** 只在 JavaFX 线程上改。 */
    private val list = ArrayList<JavaInfo>()

    /** CopyOnWrite 免得遍历通知时集合被改。 */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val javas: List<JavaInfo> get() = list

    /** 区分「还没扫」与「扫了但一个都没有」。 */
    var scanned: Boolean = false
        private set

    /** 游戏目录里装过版本没有 —— 自动选择算不出 Java 时靠它把「暂无实例」和「没有可用 Java」分开。 */
    var hasInstance: Boolean = false
        private set

    var auto: Boolean
        get() = Prefs.autoJava
        set(value) {
            if (value == Prefs.autoJava) return
            Prefs.autoJava = value
            // 切回「手动」且用户从没手动选过时，先记下自动挑的那份，免得一关开关就没 Java 可用
            if (!value && manual == null) {
                Prefs.javaHome = (pickFor(null) ?: list.firstOrNull())?.path ?: ""
            }
            notifyChanged()
        }

    /** 空 = 没选过。 */
    var manualPath: String
        get() = Prefs.javaHome
        set(value) {
            if (value == Prefs.javaHome) return
            Prefs.javaHome = value
            notifyChanged()
        }

    /** 指定的目录已不在扫描结果里（被卸载 / 挪走）时为 null。 */
    val manual: JavaInfo? get() = list.firstOrNull { it.path == manualPath }

    /** 回调在 JavaFX 线程上。 */
    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    /** 后台重扫，扫完回界面线程通知；重复调用就是重扫。 */
    fun refresh() {
        Thread {
            val found = runCatching { BackendUtil.getJavas() }.getOrDefault(emptyList())
            val anyInstance = runCatching { hasAnyInstance() }.getOrDefault(false)
            Platform.runLater {
                list.clear()
                list.addAll(found)
                hasInstance = anyInstance
                scanned = true
                // 手动选的那份不见了：退回第一个，免得下次启动直接失败
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

    /**
     * 任一游戏目录里有一份版本就算有实例。走和首页同一份扫描结果 ——
     * 两处各自判一次的话，「有没有实例」会被判成两个答案。
     */
    private fun hasAnyInstance(): Boolean =
        InstanceScan.scanAll(GameDirStore.all).any { it.versions.isNotEmpty() }

    /** 最终用哪一份；null = 没得用，由调用方弹提示。 */
    fun resolve(target: LaunchTarget?): JavaInfo? =
        if (auto) pickFor(target) else manual

    /**
     * 按目标版本要求的 Java 大版本挑一份。
     * 还没选版本（[target] 为 null）或版本读不出来（老 json 不写 javaVersion）时按「未知」处理。
     */
    fun pickFor(target: LaunchTarget?): JavaInfo? =
        choose(list, target?.let { t ->
            runCatching { LaunchUtil.requiredJavaMajor(t.versionId, t.versionRoot) }.getOrNull()
        })

    /**
     * 纯挑选逻辑（不碰磁盘、不碰界面，可单独验证）。
     *
     * 要求明确时：优先版本号完全一致；没有就挑「比要求高」里最低的（够用，且最不容易撞
     * Mixin / ASM 兼容问题）；全都偏低才挑最高的 —— 那份会被 [LaunchUtil.checkJava] 拦下
     * 并说明原因，比悄悄起不来强。要求未知时挑最高的一份。
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
