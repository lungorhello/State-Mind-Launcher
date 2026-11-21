package org.example.statemind.core.download

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.example.statemind.core.FailureText
import org.example.statemind.core.FileSource
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.loader.LoaderChoice

/** 一个下载任务对外的全部状态。字段都是 volatile：下载线程写，界面线程读。 */
class DownloadJob internal constructor(val id: Long, val versionId: String) {

    /**
     * 一段工作的进度。
     *
     * 几百个文件一起下的时候，只给一根总进度条看不出在干什么；
     * 按段列出来（下载原版资源 / 下载 Forge 主文件 / 安装 Forge）才知道卡在哪。
     */
    class StageState(val title: String, val order: Int = 0) {

        @Volatile
        var doneBytes: Long = 0L

        @Volatile
        var totalBytes: Long = 0L

        @Volatile
        var doneFiles: Int = 0

        @Volatile
        var totalFiles: Int = 0

        /** 等待中 / 下载中 / 分析中 / 安装中 / 完成 / 失败。 */
        @Volatile
        var status: String = "等待中"

        /** 本地安装步骤（安装器、补丁器）的进度：第几步 / 共几步。 */
        @Volatile
        var stepDone: Int = 0

        @Volatile
        var stepTotal: Int = 0

        /** 这一段已经收尾（不用再算进度，直接满格）。 */
        @Volatile
        var finished: Boolean = false

        /** 这一段失败的原因，一句人话（空串 = 没失败）。 */
        @Volatile
        var error: String = ""

        val progress: Double
            get() = when {
                finished -> 1.0
                stepTotal > 0 -> (stepDone.toDouble() / stepTotal).coerceIn(0.0, 1.0)
                totalBytes > 0L -> (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
                totalFiles > 0 -> (doneFiles.toDouble() / totalFiles).coerceIn(0.0, 1.0)
                else -> 0.0
            }
    }

    /** 任务里的每一段。下载途中会不断往后加（加载器的库要等安装器下完才知道）。 */
    val stages = java.util.concurrent.CopyOnWriteArrayList<StageState>()
    enum class State { WAITING, PREPARING, RUNNING, DONE, FAILED, CANCELLED }

    @Volatile var state: State = State.WAITING
        internal set

    /** 当前正在做什么 / 阶段说明，直接显示在界面上。 */
    @Volatile var note: String = "排队中"
        internal set

    /** 失败原因或收尾说明，成功时是空串。 */
    @Volatile var message: String = ""
        internal set

    /** 失败时最主要的那一条原因（人话），详情弹窗里的第一行。 */
    @Volatile var mainReason: String = ""
        internal set

    @Volatile var doneBytes: Long = 0L
        internal set

    @Volatile var totalBytes: Long = 0L
        internal set

    @Volatile var doneFiles: Int = 0
        internal set

    @Volatile var totalFiles: Int = 0
        internal set

    @Volatile var bytesPerSecond: Long = 0L
        internal set

    /** 下不动的文件数（走了所有源都失败）。 */
    @Volatile var failedFiles: Int = 0
        internal set

    private val stop = AtomicBoolean(false)

    fun cancel() {
        stop.set(true)
    }

    internal fun isCancelled(): Boolean = stop.get()

    val progress: Double
        get() = if (totalBytes <= 0L) 0.0 else (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)

    val finished: Boolean
        get() = state == State.DONE || state == State.FAILED || state == State.CANCELLED
}

/**
 * 下载任务的登记处。界面订阅 [onChange] 拿进度，不自己转圈问。
 *
 * 任务**串行**跑：同时开几个版本会让磁盘和网络互相抢，进度也各说各话。
 */
object DownloadCenter {

    private val list = CopyOnWriteArrayList<DownloadJob>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val finishers = CopyOnWriteArrayList<(DownloadJob) -> Unit>()
    private val failers = CopyOnWriteArrayList<(DownloadJob) -> Unit>()
    private val queue = LinkedBlockingQueue<Request>()

    /** 每个任务当初的请求。失败后「重新下载」要靠它复现同一份计划，版本目录也由它定位。 */
    private val requests = java.util.concurrent.ConcurrentHashMap<Long, Request>()
    private val counter = AtomicLong(0)

    @Volatile
    private var worker: Thread? = null

    val jobs: List<DownloadJob> get() = list

    private class Request(
        val job: DownloadJob,
        val entry: VersionEntry,
        val root: File,
        val source: FileSource,
        val loader: LoaderChoice?,
        val fabricApiVersionId: String?
    )

    /**
     * 登记一个下载。落点是 [root]（默认就是 StateMind 自带的 `.minecraft`）。
     * 选了加载器时原版与加载器**同一个任务**下 —— 拆成两个任务的话，用户看到两条互不相干的进度。
     *
     * [name] 是安装页上那个可以改的版本名，空着就用默认的版本 id。它是最终 `versions/<名>/`
     * 的文件夹名，也是这个任务的 `versionId`。
     */
    fun submit(
        entry: VersionEntry,
        root: File,
        source: FileSource,
        loader: LoaderChoice? = null,
        name: String = "",
        fabricApiVersionId: String? = null
    ): DownloadJob {
        val versionId = name.trim().ifBlank { loader?.id ?: entry.id }
        val job = DownloadJob(counter.incrementAndGet(), versionId)
        val request = Request(job, entry, root, source, loader, fabricApiVersionId)
        list += job
        requests[job.id] = request
        queue += request
        startWorker()
        notifyChanged()
        return job
    }

    /**
     * 按原样再跑一遍失败的任务。
     *
     * 已下好的文件会过 `usable` 校验被跳过，所以这不是「从头重下」，是「补齐缺的那几份」。
     * 版本名沿用原来那个 —— 换名字会留下两份互不相干的半成品。
     */
    fun retry(job: DownloadJob): DownloadJob? {
        val old = requests[job.id] ?: return null
        return submit(old.entry, old.root, old.source, old.loader, job.versionId, old.fabricApiVersionId)
    }

    /** 这个任务的版本目录（`<root>/versions/<名>`）。用来算目录大小、删除半成品。 */
    fun versionDirOf(job: DownloadJob): File? =
        requests[job.id]?.let { File(it.root, "versions/${job.versionId}") }

    /** 只清已经结束的；正在跑的留着。 */
    fun clearFinished() {
        list.removeAll { job ->
            if (job.finished) requests.remove(job.id)
            job.finished
        }
        notifyChanged()
    }

    /** 回调在调用方线程（下载线程）上，界面自己 [javafx.application.Platform.runLater]。 */
    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    /**
     * 一个任务**装完**（成功落地）时回调一次 —— 用在「首页那个版本下拉刷新」这类
     * 派生信息上：装好之后该多出来的东西，不必等用户重启启动器。
     *
     * 进度类的通知走 [onChange]；这个只在终态为成功时响一次，别混着用。
     */
    fun onJobFinished(listener: (DownloadJob) -> Unit) {
        finishers += listener
    }

    internal fun notifyFinished(job: DownloadJob) {
        finishers.forEach { it(job) }
    }

    /**
     * 任务终态为失败时回调一次。
     *
     * 界面据此**主动**弹出原因，而不是等人去点任务页上的「查看详情」——
     * 下到一半失败时人可能已经切到别的页面了。
     */
    fun onJobFailed(listener: (DownloadJob) -> Unit) {
        failers += listener
    }

    internal fun notifyFailed(job: DownloadJob) {
        failers.forEach { it(job) }
    }

    internal fun notifyChanged() {
        listeners.forEach { it() }
    }

    private fun startWorker() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = Thread({
                while (true) {
                    val request = runCatching { queue.take() }.getOrNull() ?: continue
                    runCatching {
                        DownloadRunner.run(
                            request.job,
                            request.entry,
                            request.root,
                            request.source,
                            request.loader,
                            request.fabricApiVersionId
                        )
                    }
                        .onFailure { error ->
                            request.job.state = DownloadJob.State.FAILED
                            request.job.mainReason = FailureText.of(error).text
                            request.job.message = "安装过程中出现问题"
                            request.job.note = "已中断"
                            notifyChanged()
                        }
                    // 正常收尾和上面这条异常路径都可能以失败结束，统一在这里报一次
                    if (request.job.state == DownloadJob.State.FAILED) notifyFailed(request.job)
                }
            }, "download-queue").apply { isDaemon = true; start() }
        }
    }
}
