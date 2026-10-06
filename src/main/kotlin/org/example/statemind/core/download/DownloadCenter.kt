package org.example.statemind.core.download

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.example.statemind.core.FileSource
import org.example.statemind.core.VersionEntry

/** 一个下载任务对外的全部状态。字段都是 volatile：下载线程写，界面线程读。 */
class DownloadJob internal constructor(val id: Long, val versionId: String) {

    enum class State { WAITING, PREPARING, RUNNING, DONE, FAILED, CANCELLED }

    @Volatile var state: State = State.WAITING
        internal set

    /** 当前正在做什么 / 阶段说明，直接显示在界面上。 */
    @Volatile var note: String = "排队中"
        internal set

    /** 失败原因或收尾说明，成功时是空串。 */
    @Volatile var message: String = ""
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
    private val queue = LinkedBlockingQueue<Request>()
    private val counter = AtomicLong(0)

    @Volatile
    private var worker: Thread? = null

    val jobs: List<DownloadJob> get() = list

    private class Request(
        val job: DownloadJob,
        val entry: VersionEntry,
        val root: File,
        val source: FileSource
    )

    /** 登记一个下载。落点是 [root]（默认就是 StateMind 自带的 `.minecraft`）。 */
    fun submit(entry: VersionEntry, root: File, source: FileSource): DownloadJob {
        val job = DownloadJob(counter.incrementAndGet(), entry.id)
        list += job
        queue += Request(job, entry, root, source)
        startWorker()
        notifyChanged()
        return job
    }

    /** 只清已经结束的；正在跑的留着。 */
    fun clearFinished() {
        list.removeAll { it.finished }
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

    internal fun notifyChanged() {
        listeners.forEach { it() }
    }

    private fun startWorker() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = Thread({
                while (true) {
                    val request = runCatching { queue.take() }.getOrNull() ?: continue
                    runCatching { DownloadRunner.run(request.job, request.entry, request.root, request.source) }
                        .onFailure { error ->
                            request.job.state = DownloadJob.State.FAILED
                            request.job.message = error.message ?: error.toString()
                            request.job.note = "已中断"
                            notifyChanged()
                        }
                }
            }, "download-queue").apply { isDaemon = true; start() }
        }
    }
}
