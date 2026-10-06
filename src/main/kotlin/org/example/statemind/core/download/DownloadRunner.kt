package org.example.statemind.core.download

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.example.statemind.core.FileSource
import org.example.statemind.core.MiniJson
import org.example.statemind.core.Prefs
import org.example.statemind.core.VersionEntry

/**
 * 执行一份 [DownloadPlan]：先算、后下，多线程按 [Prefs] 的线程数跑，整机限速走同一个令牌桶。
 *
 * 只在 [DownloadCenter] 的队列线程里调用，界面不直接碰它。
 */
internal object DownloadRunner {

    private const val PUBLISH_MS = 250L

    fun run(job: DownloadJob, entry: VersionEntry, root: File, source: FileSource) {
        job.state = DownloadJob.State.PREPARING
        job.note = "准备中…"
        DownloadCenter.notifyChanged()

        val plan = DownloadPlanner.plan(entry, root, source) { note ->
            job.note = note
            DownloadCenter.notifyChanged()
        }
        job.totalFiles = plan.files.size
        job.totalBytes = plan.totalBytes
        job.state = DownloadJob.State.RUNNING

        val done = AtomicLong(0)
        val doneFiles = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)

        // 已经下好的（大小对得上就当没坏）直接计入进度，重下时不会从 0 开始
        val pending = ArrayList<DownloadItem>()
        for (file in plan.files) {
            if (file.target.isFile && file.size > 0L && file.target.length() == file.size) {
                done.addAndGet(file.size)
                doneFiles.incrementAndGet()
            } else {
                pending += file
            }
        }

        val publishing = AtomicBoolean(true)
        val ticker = Thread { publishLoop(job, plan, done, doneFiles, publishing) }
            .apply { isDaemon = true; name = "download-progress"; start() }

        val threads = if (Prefs.downloadThreads == 0) Prefs.THREADS_MAX else Prefs.downloadThreads
        val limiter = RateLimiter(Prefs.downloadSpeedKbps.toLong() * 1024L)
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
                        val item = pending[index]
                        try {
                            Fetcher.fetch(item, { bytes ->
                                limiter.acquire(bytes)
                                done.addAndGet(bytes.toLong())
                            }, job::isCancelled)
                            doneFiles.incrementAndGet()
                        } catch (_: CancelledException) {
                            break
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            firstError.compareAndSet(null, "${item.target.name}：${e.message ?: e}")
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

        publishing.set(false)
        runCatching { ticker.join(1000) }

        job.failedFiles = failed.get()
        when {
            job.isCancelled() -> {
                job.state = DownloadJob.State.CANCELLED
                job.note = "已取消"
            }

            failed.get() > 0 -> {
                job.state = DownloadJob.State.FAILED
                job.note = "下载未完成"
                job.message = "${failed.get()} 个文件下载失败（例如 ${firstError.get()}）"
            }

            else -> {
                job.note = "正在布置资源…"
                DownloadCenter.notifyChanged()
                runCatching { layOutAssets(plan) }
                    .onFailure { job.message = "资源布置失败：${it.message ?: it}" }
                job.doneBytes = plan.totalBytes
                job.doneFiles = plan.files.size
                job.state = DownloadJob.State.DONE
                job.note = "下载完成"
                if (plan.skipped.isNotEmpty()) {
                    job.message = "有 ${plan.skipped.size} 个依赖库没有下载地址，已跳过"
                }
            }
        }
        DownloadCenter.notifyChanged()
        // 真的装到盘上了才发这一路通知：界面据此把「已安装的版本」重新扫一遍
        if (job.state == DownloadJob.State.DONE) DownloadCenter.notifyFinished(job)
    }

    /** 每 [PUBLISH_MS] 毫秒把计数刷到任务上，顺便算瞬时速度。 */
    private fun publishLoop(
        job: DownloadJob,
        plan: DownloadPlan,
        done: AtomicLong,
        doneFiles: AtomicInteger,
        publishing: AtomicBoolean
    ) {
        var lastBytes = 0L
        var lastAt = System.currentTimeMillis()
        var speed = 0L
        while (publishing.get() && !job.finished) {
            Thread.sleep(PUBLISH_MS)
            val bytes = done.get()
            val now = System.currentTimeMillis()
            val elapsed = (now - lastAt).coerceAtLeast(1L)
            val instant = (bytes - lastBytes) * 1000L / elapsed
            speed = (speed * 2 + instant) / 3
            lastBytes = bytes
            lastAt = now
            job.doneBytes = bytes
            job.doneFiles = doneFiles.get()
            job.bytesPerSecond = speed
            job.note = "正在下载 ${job.doneFiles} / ${plan.files.size} 个文件"
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
