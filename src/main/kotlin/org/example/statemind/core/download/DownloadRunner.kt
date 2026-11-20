package org.example.statemind.core.download

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.example.statemind.core.FailureText
import org.example.statemind.core.FileSource
import org.example.statemind.core.GameDir
import org.example.statemind.core.InstallMarker
import org.example.statemind.core.MiniJson
import org.example.statemind.core.Prefs
import org.example.statemind.core.RecentInstall
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.install.InstallCancelled
import org.example.statemind.core.install.InstallStep
import org.example.statemind.core.loader.LoaderChoice

/**
 * 执行一份 [DownloadPlan]：先算、后下，多线程按 [Prefs] 的线程数跑，整机限速走同一个令牌桶。
 *
 * 界面按**阶段**显示进度（下载原版资源 / 下载 Forge 主文件 / 安装 Forge），
 * 但下载本身仍然是并行的 —— 能一起下的就一起下，分段只是为了让人看得见「现在在干哪一段」。
 *
 * 只在 [DownloadCenter] 的队列线程里调用，界面不直接碰它。
 */
internal object DownloadRunner {

    private const val PUBLISH_MS = 250L

    fun run(
        job: DownloadJob,
        entry: VersionEntry,
        root: File,
        source: FileSource,
        loader: LoaderChoice? = null,
        fabricApiVersionId: String? = null
    ) {
        job.state = DownloadJob.State.PREPARING
        job.note = "准备中…"
        DownloadCenter.notifyChanged()

        // 目录是这一次新建的才留「未完成」痕迹：中途失败或取消时，首页下拉不该把这个
        // 半成品当成已安装版本列出来。目录本来就在（别的启动器装的）就不碰它。
        val versionDir = File(root, "versions/${job.versionId}")
        val createdDir = !versionDir.exists()
        if (createdDir) InstallMarker.mark(versionDir)

        // job.versionId 就是用户可能改过的版本名：计划、落盘、首页显示都按它走
        val plan = DownloadPlanner.plan(entry, root, source, loader, job.versionId, fabricApiVersionId) { note ->
            job.note = note
            DownloadCenter.notifyChanged()
        }
        job.state = DownloadJob.State.RUNNING

        val board = Board(job)
        val vanillaRuns = plan.vanilla.mapIndexed { index, stage ->
            board.stage(stage.title, stage.files, DownloadOrders.VANILLA_BASE + index * 10)
        }
        val loaderPlan = plan.loader
        val installerRun = loaderPlan?.let { lp ->
            // Fabric 系没有安装器，也不该冒出「下载主文件」这一段
            if (lp.installer == null && lp.prepare == null) {
                null
            } else {
                val item = lp.installer
                val run = board.stage(
                    DownloadStages.main(lp.label),
                    item?.let { listOf(it) } ?: emptyList(),
                    DownloadOrders.LOADER_MAIN
                )
                // 安装器本地已经有了：这段照样显示，只是直接算完成
                if (item == null) {
                    run.state.status = "完成"
                    run.state.finished = true
                }
                run
            }
        }
        // Fabric / Quilt / LiteLoader 的库表在 plan 阶段就读到了，跟原版文件一起并行下
        val loaderRuns = loaderPlan?.stages?.mapIndexed { index, stage ->
            board.stage(stage.title, stage.files, DownloadOrders.LOADER_DIRECT + index * 10)
        } ?: emptyList()

        // Forge 系：库清单在安装器里，下完安装器才解析得出。但这两段**先占好位置**，
        // 用户从一开始就能看见「接下来要做什么」，而不是下到最后才冒出来。
        val analyzeRun = loaderPlan?.takeIf { it.prepare != null }
            ?.let { board.stage(DownloadStages.analyze(it.label), emptyList(), DownloadOrders.LOADER_ANALYZE) }
        val loaderLibRun = loaderPlan?.takeIf { it.prepare != null }
            ?.let { board.stage(DownloadStages.libs(it.label), emptyList(), DownloadOrders.LOADER_LIBS) }
        val loaderInstallRun = loaderPlan?.takeIf { it.prepare != null }
            ?.let { board.stage(DownloadStages.install(it.label), emptyList(), DownloadOrders.LOADER_INSTALL) }

        val extraRuns = plan.extra.mapIndexed { index, stage ->
            board.stage(stage.title, stage.files, DownloadOrders.EXTRA_BASE + index * 10)
        }
        val installRun = board.stage(DownloadStages.INSTALL_GAME, emptyList(), DownloadOrders.INSTALL_GAME)

        val failed = AtomicInteger(0)
        val limiter = RateLimiter(Prefs.downloadSpeedKbps.toLong() * 1024L)
        val threads = if (Prefs.downloadThreads == 0) Prefs.THREADS_MAX else Prefs.downloadThreads
        board.skipped += plan.skipped

        val publishing = AtomicBoolean(true)
        val ticker = Thread({ publishLoop(job, board) { publishing.get() } }, "download-progress")
            .apply { isDaemon = true; start() }

        try {
            // ① 能并行的都一起下：原版那几段 + 加载器主文件 + 加载器自己的库 + 前置（Fabric API）
            downloadWave(
                board, job,
                vanillaRuns + listOfNotNull(installerRun) + loaderRuns + extraRuns,
                limiter, threads, failed
            )

            // ② 加载器的库清单藏在安装器里：主文件到位之后才解析得出它要哪些库
            val lp = loaderPlan
            if (lp != null && lp.prepare != null && !job.isCancelled()) {
                analyzeRun?.state?.status = "分析中"
                DownloadCenter.notifyChanged()
                val prepared = lp.prepare()
                analyzeRun?.state?.status = "完成"
                analyzeRun?.state?.finished = true
                DownloadCenter.notifyChanged()

                for (stage in prepared.stages) {
                    if (job.isCancelled()) break
                    val run = when (stage.title) {
                        DownloadStages.libs(lp.label) -> loaderLibRun
                        DownloadStages.install(lp.label) -> loaderInstallRun
                        else -> null
                    } ?: board.stage(stage.title, stage.files, DownloadOrders.LOADER_LIBS)
                    run.hasSteps = stage.steps.isNotEmpty()
                    run.adopt(stage.files)
                    downloadWave(board, job, listOf(run), limiter, threads, failed)
                    if (failed.get() > 0 || job.isCancelled()) break
                    runSteps(job, run, stage.steps)
                }
                board.skipped += prepared.skipped
            }

            // ③ 铺资源、整理版本目录
            if (!job.isCancelled() && failed.get() == 0) {
                installRun.state.status = "处理中"
                DownloadCenter.notifyChanged()
                layOutAssets(plan)
                installRun.state.status = "完成"
                installRun.state.finished = true
                DownloadCenter.notifyChanged()
            }
        } catch (_: InstallCancelled) {
            job.state = DownloadJob.State.CANCELLED
            job.note = "已取消"
        } catch (e: Exception) {
            job.state = DownloadJob.State.FAILED
            job.note = "安装失败"
            board.noteFailure(FailureText.of(e))
            board.markUnfinishedFailed(FailureText.of(e).text)
            job.mainReason = board.mainReason()
            job.message = "安装过程中出现问题"
        } finally {
            publishing.set(false)
            runCatching { ticker.join(1000) }
        }

        if (job.state == DownloadJob.State.RUNNING) {
            job.failedFiles = failed.get()
            when {
                job.isCancelled() -> {
                    job.state = DownloadJob.State.CANCELLED
                    job.note = "已取消"
                }

                failed.get() > 0 -> {
                    job.state = DownloadJob.State.FAILED
                    job.note = "下载未完成"
                    board.markUnfinishedFailed(board.mainReason())
                    job.mainReason = board.mainReason()
                    job.message = "${failed.get()} 个文件下载失败"
                }

                else -> {
                    board.publish()
                    job.doneBytes = board.doneBytes.get()
                    job.doneFiles = board.doneFiles.get()
                    job.totalBytes = board.totalBytes.get()
                    job.totalFiles = board.totalFiles.get()
                    job.state = DownloadJob.State.DONE
                    job.note = "下载完成"
                    if (board.skipped.isNotEmpty()) {
                        job.message = "有 ${board.skipped.size} 个依赖库没有下载地址，已跳过"
                    }
                }
            }
        }
        DownloadCenter.notifyChanged()

        // 真的装到盘上了才发这一路通知：界面据此把「已安装的版本」重新扫一遍
        if (job.state == DownloadJob.State.DONE) {
            InstallMarker.clear(versionDir)
            // 装完就把隔离目录的骨架建齐（mods / shaderpacks / config / saves …），
            // 不然用户得先启动一次游戏，这些文件夹才会出现
            GameDir.ensureVersion(versionDir)
            RecentInstall.remember(job.versionId, entry.id, loader?.kind?.label)
            DownloadCenter.notifyFinished(job)
        }

        // 取消就把这次新建的版本目录整个抹掉。留着半份文件，下次重下会被「大小对得上」
        // 的续传判断当成已经下完 —— 看上去点一下一秒就装好，其实缺文件。
        // 目录不是这次建的（别的启动器装的）就不动它，只清掉我们自己的临时文件。
        if (job.state == DownloadJob.State.CANCELLED) {
            job.note = "正在清理…"
            DownloadCenter.notifyChanged()
            if (createdDir) {
                versionDir.deleteRecursively()
            } else {
                versionDir.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }
                    .forEach { runCatching { it.delete() } }
            }
        }
    }

    /**
     * 下一批文件。
     *
     * 同一批里的文件**并行**下（能一起下的就一起下）；本地已有且校验得过的
     * 直接计入进度，不重下。
     */
    private fun downloadWave(
        board: Board,
        job: DownloadJob,
        runs: List<Board.Run>,
        limiter: RateLimiter,
        threads: Int,
        failed: AtomicInteger
    ) {
        val pending = ArrayList<Pair<Board.Run, DownloadItem>>()
        for (run in runs) {
            if (run.state.status == "等待中") run.state.status = "下载中"
            for (item in run.files) {
                // 本地已有的先验一遍：大小对但内容坏掉的文件照样重下，
                // 不然装上去才发现用不了（PCL 也对每个文件走校验，速度看着才均匀）。
                if (Fetcher.usable(item)) {
                    run.skip(item)
                } else {
                    pending += run to item
                }
            }
        }
        if (pending.isEmpty()) {
            runs.forEach { it.finishIfDone() }
            return
        }

        val next = AtomicInteger(0)
        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads) { runnable -> Thread(runnable, "download").apply { isDaemon = true } }

        repeat(threads) {
            pool.execute {
                try {
                    while (true) {
                        if (job.isCancelled()) break
                        val index = next.getAndIncrement()
                        if (index >= pending.size) break
                        val (run, item) = pending[index]
                        try {
                            Fetcher.fetch(item, { bytes ->
                                limiter.acquire(bytes)
                                run.got(bytes.toLong())
                            }, job::isCancelled)
                            run.complete()
                        } catch (_: CancelledException) {
                            break
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            val reason = FailureText.of(e)
                            board.noteFailure(reason)
                            run.failed(reason.text)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        pool.shutdown()
        latch.await()
        runCatching { pool.awaitTermination(30, TimeUnit.SECONDS) }
        runs.forEach { it.finishIfDone() }
        DownloadCenter.notifyChanged()
    }

    /** 跑这一段里的本地安装步骤（安装器、补丁器）。 */
    private fun runSteps(job: DownloadJob, run: Board.Run, steps: List<InstallStep>) {
        if (steps.isEmpty()) return
        run.state.status = "安装中"
        run.state.stepTotal = steps.size
        DownloadCenter.notifyChanged()
        steps.forEachIndexed { index, step ->
            if (job.isCancelled()) throw InstallCancelled()
            run.state.stepDone = index
            DownloadCenter.notifyChanged()
            step.run({ note ->
                job.note = note
                DownloadCenter.notifyChanged()
            }, job::isCancelled)
            run.state.stepDone = index + 1
            DownloadCenter.notifyChanged()
        }
        run.state.status = "完成"
        run.state.finished = true
        DownloadCenter.notifyChanged()
    }

    /** 每 [PUBLISH_MS] 毫秒把计数刷到任务上，顺便算瞬时速度。 */
    private fun publishLoop(job: DownloadJob, board: Board, alive: () -> Boolean) {
        var lastBytes = 0L
        var lastAt = System.currentTimeMillis()
        var speed = 0L
        while (alive() && !job.finished) {
            Thread.sleep(PUBLISH_MS)
            val bytes = board.doneBytes.get()
            val now = System.currentTimeMillis()
            val elapsed = (now - lastAt).coerceAtLeast(1L)
            val instant = (bytes - lastBytes) * 1000L / elapsed
            speed = (speed * 2 + instant) / 3
            lastBytes = bytes
            lastAt = now
            board.publish()
            job.doneBytes = bytes
            job.doneFiles = board.doneFiles.get()
            job.totalBytes = board.totalBytes.get()
            job.totalFiles = board.totalFiles.get()
            job.bytesPerSecond = speed
            job.note = board.note()
            DownloadCenter.notifyChanged()
        }
    }

    /**
     * 老版本（1.7.2 之前）的资源索引要求把资源**另铺一份**：`virtual` 铺到 `assets/virtual/<索引>/`，
     * `map_to_resources` 铺到游戏目录的 `resources/`。判断依据是索引 json 里的标志位，不按版本号猜。
     */
    private fun layOutAssets(plan: DownloadPlan) {
        if (!plan.virtualAssets && !plan.mapToResources) return
        val indexFile = File(plan.assetsDir, "indexes/${plan.assetIndexId}.json")
        if (!indexFile.isFile) return
        val objects = (MiniJson.parse(indexFile.readText(Charsets.UTF_8)) as? Map<*, *>)
            ?.get("objects") as? Map<*, *> ?: return
        val virtualDir = File(plan.assetsDir, "virtual/${plan.assetIndexId}")
        val resourcesDir = File(plan.gameDir, "resources")
        for ((name, value) in objects) {
            val fileName = name as? String ?: continue
            val hash = (value as? Map<*, *>)?.get("hash") as? String ?: continue
            if (hash.length < 2) continue
            val objectFile = File(plan.assetsDir, "objects/${hash.substring(0, 2)}/$hash")
            if (!objectFile.isFile) continue
            if (plan.virtualAssets) copy(objectFile, File(virtualDir, fileName))
            if (plan.mapToResources) copy(objectFile, File(resourcesDir, fileName))
        }
    }

    private fun copy(from: File, to: File) {
        if (to.isFile && to.length() == from.length()) return
        to.parentFile?.mkdirs()
        runCatching { from.copyTo(to, overwrite = true) }
    }
}

/** 阶段在界面上的先后顺序。数字小的在上面。 */
internal object DownloadOrders {
    const val VANILLA_BASE = 100
    const val LOADER_MAIN = 200
    const val LOADER_DIRECT = 210
    const val LOADER_ANALYZE = 220
    const val LOADER_LIBS = 230
    const val LOADER_INSTALL = 240
    const val EXTRA_BASE = 300
    const val INSTALL_GAME = 900
}

/**
 * 任务的阶段板：登记每一段、记它的进度。
 *
 * 计数用原子类：下载线程写，界面线程读。
 */
internal class Board(private val job: DownloadJob) {

    /** 一段工作。 */
    class Run(val title: String, initial: List<DownloadItem>, val order: Int) {
        val state = DownloadJob.StageState(title, order)

        private val done = AtomicLong(0L)
        private val count = AtomicInteger(0)

        /**
         * 这一段要下哪些文件。
         *
         * Forge 系的两段一开始是空的 —— 要等安装器下完、读出库表才知道要下什么，
         * 那时用 [adopt] 补上。
         */
        var files: List<DownloadItem> = initial
            private set

        /** 这一段一共有多少字节 / 多少文件（已存在的也算）。 */
        var totalBytes: Long = initial.sumOf { it.size }
            private set

        var totalFiles: Int = initial.size
            private set

        /** 这一段后面还要跑本地步骤（安装器），文件数归零不等于做完了。 */
        @Volatile
        var hasSteps: Boolean = false

        /** 库表解析出来之后，把真正要下的文件补进这一段。 */
        fun adopt(items: List<DownloadItem>) {
            files = items
            totalBytes = items.sumOf { it.size }
            totalFiles = items.size
        }

        fun skip(item: DownloadItem) {
            done.addAndGet(item.size)
            count.incrementAndGet()
        }

        fun got(bytes: Long) {
            done.addAndGet(bytes)
        }

        fun complete() {
            count.incrementAndGet()
        }

        fun failed(reason: String) {
            count.incrementAndGet()
            state.status = "失败"
            state.error = reason
        }

        fun finishIfDone() {
            if (hasSteps) return
            if (count.get() >= totalFiles && state.status != "失败") {
                state.status = "完成"
                state.finished = true
            }
        }

        fun publish() {
            state.doneBytes = done.get()
            state.doneFiles = count.get()
            state.totalBytes = totalBytes
            state.totalFiles = totalFiles
        }
    }

    val runs = java.util.concurrent.CopyOnWriteArrayList<Run>()

    /** 没有下载地址、只能跳过的库。 */
    val skipped = ArrayList<String>()

    /** 失败原因按粗分类计数：弹窗里只报最主要的那一条。 */
    private val reasonCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val reasonText = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 记一次失败，并把它的粗分类计入统计。 */
    fun noteFailure(reason: FailureText.Reason) {
        reasonCounts.merge(reason.category, 1, Int::plus)
        reasonText[reason.category] = reason.text
    }

    /** 出现最多的那一类失败的人话描述；没有失败就是空串。 */
    fun mainReason(): String {
        val top = reasonCounts.entries.maxByOrNull { it.value }?.key ?: return ""
        return reasonText[top] ?: ""
    }

    /** 异常中断时，把没收尾的段标成失败，让用户看到卡在哪一段。 */
    fun markUnfinishedFailed(reason: String) {
        for (run in runs) {
            if (run.state.status != "失败" && run.state.status != "等待中" && !run.state.finished) {
                run.state.status = "失败"
                run.state.error = reason
            }
        }
    }

    val doneBytes = AtomicLong(0L)
    val totalBytes = AtomicLong(0L)
    val doneFiles = AtomicInteger(0)
    val totalFiles = AtomicInteger(0)

    fun stage(title: String, files: List<DownloadItem>, order: Int): Run {
        val run = Run(title, files, order)
        runs += run
        job.stages += run.state
        return run
    }

    /** 把各段的计数汇总到任务上，并刷新每段自己的进度。 */
    fun publish() {
        var bytes = 0L
        var files = 0
        var allBytes = 0L
        var allFiles = 0
        for (run in runs) {
            run.publish()
            bytes += run.state.doneBytes
            files += run.state.doneFiles
            allBytes += run.totalBytes
            allFiles += run.totalFiles
        }
        doneBytes.set(bytes)
        doneFiles.set(files)
        totalBytes.set(allBytes)
        totalFiles.set(allFiles)
    }

    /** 当前在干什么：正在下载 / 安装的那一段。 */
    fun note(): String {
        val active = runs.lastOrNull {
            it.state.status == "下载中" || it.state.status == "安装中" || it.state.status == "分析中"
        }
        return active?.title ?: "整理中…"
    }
}

/**
 * 全局令牌桶：所有线程共用一份配额，谁也别偷偷多下。
 *
 * 每次超配额最多睡 200 ms —— 睡太久的话用户点了取消还得等它醒。
 * [bytesPerSecond] 为 0 表示不限速，这时整个类不起作用。
 */
internal class RateLimiter(private val bytesPerSecond: Long) {

    private val startedAt = System.nanoTime()
    private var used = 0L

    @Synchronized
    fun acquire(bytes: Int) {
        if (bytesPerSecond <= 0L) return
        used += bytes
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val over = used - bytesPerSecond * elapsed
        if (over <= 0.0) return
        val sleep = ((over / bytesPerSecond) * 1000.0).toLong().coerceIn(1L, 200L)
        runCatching { Thread.sleep(sleep) }
    }
}
