package org.example.statemind.core

/**
 * 启动时给 JVM 传哪套 GC 参数。
 *
 * 只留三档。再多就不是「选项」而是「考试」了 —— 大部分玩家用默认，卡顿明显的试 G1 那档，
 * 剩下的交给专业用户自己在那两档之外改配置文件。
 *
 * G1 那套来自 Aikar 的调优参数（原本给服务端写，客户端受益的主要是新老代比例和
 * `PerfDisableSharedMem`）。**故意没带 `-XX:+AlwaysPreTouch`**：它会在启动时把整块堆一次摸满，
 * 任务管理器里的占用直接顶到 `-Xmx`，玩家会以为启动器把内存吃光了。
 */
enum class GcPreset(val label: String, val minJava: Int) {

    /** 不传任何 GC 参数，让 JVM 按版本自己选。 */
    DEFAULT("默认", 0),

    /** G1 + Aikar 那套比例参数。任何 Java 8 以上的版本都认。 */
    G1("G1 调优", 8),

    /**
     * 分代 ZGC。Java 21/22 要显式开 `ZGenerational`；23 起分代是默认行为，那个开关反而成了
     * 过时选项，传了只会多一行警告。
     */
    ZGC("分代 ZGC", 21);

    /** 这份 Java 版本该传的参数；版本不够就一条都不传，别拿参数把游戏拦在启动前。 */
    fun args(javaMajor: Int): List<String> = when {
        javaMajor < minJava -> emptyList()
        this == DEFAULT -> emptyList()
        this == G1 -> G1_ARGS
        javaMajor >= 23 -> listOf("-XX:+UseZGC")
        else -> listOf("-XX:+UseZGC", "-XX:+ZGenerational")
    }

    companion object {

        /** 存的枚举名被手改坏了就退回默认，不至于打不开启动器。 */
        fun of(name: String?): GcPreset = entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * 从 `25.0.1` / `1.8.0_392` 这种版本串里取主版本号。取不出来返回 0，
         * 调用方据此当成「不知道」，而不是当成「很老的 Java」。
         */
        fun majorOf(version: String): Int {
            val head = version.trim().substringBefore('.').substringBefore('-')
            val first = head.toIntOrNull() ?: return 0
            return if (first == 1) {
                version.substringAfter('.', "").substringBefore('.').toIntOrNull() ?: 0
            } else {
                first
            }
        }

        private val G1_ARGS = listOf(
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1HeapRegionSize=8M",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32",
            "-XX:+PerfDisableSharedMem",
            "-XX:MaxTenuringThreshold=1"
        )
    }
}
