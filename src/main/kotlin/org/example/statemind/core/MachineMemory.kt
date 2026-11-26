package org.example.statemind.core

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.roundToInt

/**
 * 物理内存读数，以及「这次启动给多少」的唯一算法。
 *
 * 读的是 `com.sun.management.OperatingSystemMXBean` —— 它在 `jdk.management` 里，不在 `java.management`。
 * jpackage 用 jdeps 自己算要带哪些模块，classpath 式引用常常漏掉它，所以打包脚本里显式写了
 * `--add-modules jdk.management`。万一还是读不到也不炸：全部退回 [DEFAULT_MB]，只是自动配置
 * 变回一个固定值，界面上的「已使用内存」显示读不到。
 */
object MachineMemory {

    private const val MB = 1024L * 1024L

    private val bean: com.sun.management.OperatingSystemMXBean? = runCatching {
        ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    }.getOrNull()

    /** 物理内存总量（MB）；读不到是 0。调用方要能接受 0，别拿它做除数。 */
    val totalMb: Long = runCatching { (bean?.totalMemorySize ?: 0L) / MB }.getOrDefault(0L)

    /**
     * 系统当前已用（MB）= 总量 − 空闲；读不到是 0。
     *
     * 用 getter 而不是存下来的值：设置页每两秒读一次，存下来的那份会一直停在启动那一刻。
     */
    val usedMb: Long
        get() = runCatching {
            val total = bean?.totalMemorySize ?: return@runCatching 0L
            val free = bean.freeMemorySize
            (total - free).coerceAtLeast(0L) / MB
        }.getOrDefault(0L)

    /** 当前可用（MB）= 总量 − 已用；读不到是 0。 */
    val freeMb: Long
        get() = (totalMb - usedMb).coerceAtLeast(0L)

    /**
     * 自动配置给多少。
     *
     * 算法照 PCL（`PageSetupLaunch.GetRam`，自动档）：先按版本需求定四个目标值，再从**当前
     * 可用内存**里一段一段地取，越往后取的比例越低。
     *
     * ```
     * 最低限度  0.5 + mod 数 / 150
     * 目标一    1.5 + mod 数 / 90     这一段按 100% 取
     * 目标二    2.7 + mod 数 / 50     这一段按 70% 取
     * 目标三    4.5 + mod 数 / 25     这一段按 40% 取
     * 目标四    目标三的两倍          这一段按 15% 取
     * ```
     *
     * 比例递减的意思是：可用内存充裕时尽量给足，紧张时先保这一段里该给的部分，剩下的
     * 留给系统。所以「自动配置」不是固定值 —— 关掉浏览器、清掉后台之后它会跟着涨，
     * 机器忙起来它会跟着降。
     *
     * 和 PCL 的两处出入：
     * - mod 数为 0 时按原版的目标值（目标二 2.5、目标三 4.0）；PCL 只看版本能不能装 mod，
     *   我们这边没装 mod 的加载器版本没必要按整合包的量预留。
     * - 下限抬到 1 GB。PCL 的 0.5 GB 在 1.12 之后连启动都过不去，给这么个数只会让人以为
     *   启动器算错了。
     */
    fun autoMb(): Int {
        if (totalMb <= 0) return DEFAULT_MB

        val mods = modCount()
        val modded = mods > 0
        val minimum = if (modded) 0.5 + mods / 150.0 else 0.5
        val target1 = if (modded) 1.5 + mods / 90.0 else 1.5
        val target2 = if (modded) 2.7 + mods / 50.0 else 2.5
        val target3 = if (modded) 4.5 + mods / 25.0 else 4.0

        var avail = freeMb / 1024.0
        var give = minOf(avail, target1)
        avail -= target1

        // 每段都按「整段大小」扣预算，而不是按真正拿走的量 —— PCL 就是这么算的：
        // 这一段的额度用掉了这片区域，没拿满的部分也不留给下一段再乘一次比例。
        if (avail >= 0.1) {
            give += minOf(avail * 0.7, target2 - target1)
            avail -= (target2 - target1) / 0.7
        }
        if (avail >= 0.1) {
            give += minOf(avail * 0.4, target3 - target2)
            avail -= (target3 - target2) / 0.4
        }
        if (avail >= 0.1) {
            give += minOf(avail * 0.15, target3)
            avail -= target3 / 0.15
        }

        val wanted = maxOf(give, minimum)
        val ceiling = (totalMb - RESERVE_MB).coerceAtLeast(AUTO_MIN_MB.toLong()).toInt()
        val aligned = (wanted * 1024.0 / STEP).roundToInt() * STEP
        return aligned.coerceIn(AUTO_MIN_MB, minOf(MAX_MB, ceiling))
    }

    /**
     * 当前选中版本装了多少个 mod。
     *
     * 只数 `mods/` 这一层的 jar / zip / litemod —— 和 PCL 的口径一致，停用的 mod 会换成
     * `.disabled` 后缀，本来也不该算进去。没选版本、目录不存在都按 0 算。
     */
    private fun modCount(): Int {
        val target = LaunchSelection.current ?: return 0
        val versionDir = File(File(target.versionRoot, "versions"), target.versionId)
        val files = File(versionDir, "mods").listFiles() ?: return 0
        return files.count { it.isFile && it.extension.lowercase() in MOD_EXT }
    }

    /**
     * 这次启动真正给多少。自定义值也不能超过机器装得下的量：留 1 GB 给系统，
     * 否则 `-Xmx` 比物理内存还大，启动阶段就会直接崩。
     */
    fun effectiveMb(): Int {
        val want = if (Prefs.memoryAuto || Prefs.memoryMb <= 0) autoMb() else Prefs.memoryMb
        val ceiling = if (totalMb > 0) {
            (totalMb - RESERVE_MB).coerceAtLeast(MIN_MB.toLong()).toInt()
        } else {
            MAX_MB
        }
        return want.coerceIn(MIN_MB, minOf(MAX_MB, ceiling)) / STEP * STEP
    }

    /** 读不到物理内存时的兜底值，也就是以前写死在启动参数里的那个数。 */
    const val DEFAULT_MB = 4096

    const val MIN_MB = 512
    const val MAX_MB = 16384

    /** 滑条与落盘值都按 256 MB 对齐，免得配置里出现 4097 这种数。 */
    const val STEP = 256

    /** 自动配置的下限：再低原版也跑不起来，读数是 0.5 GB 只会让人以为算错了。 */
    private const val AUTO_MIN_MB = 1024

    /** 算 mod 数时认的后缀。`.disabled` 的停用 mod 不算。 */
    private val MOD_EXT = setOf("jar", "zip", "litemod")

    /** 留给系统的那 1 GB：压缩内存、文件缓存、桌面合成都在里面。 */
    private const val RESERVE_MB = 1024L
}
