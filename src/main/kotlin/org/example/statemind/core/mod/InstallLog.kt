package org.example.statemind.core.mod

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 社区资源安装的流水账。
 *
 * 它们不经过 [org.example.statemind.core.download.DownloadCenter]（那套是给整个版本用的，
 * 有依赖图与资源布置），所以任务页光看下载队列是看不到的 —— 这里单独记一份，
 * 让「我刚点的那个模组装到哪了」有个地方可查。
 */
object InstallLog {

    enum class State { RUNNING, DONE, FAILED }

    class Entry internal constructor(
        val title: String,
        val kind: ModKind,
        val fileName: String,
        val target: File,
        val totalBytes: Long
    ) {
        @Volatile
        var state: State = State.RUNNING
            internal set

        @Volatile
        var doneBytes: Long = 0L
            internal set

        @Volatile
        var message: String = ""
            internal set

        val progress: Double
            get() = if (totalBytes <= 0L) 0.0 else (doneBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)

        val finished: Boolean get() = state != State.RUNNING
    }

    private val entries = CopyOnWriteArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val all: List<Entry> get() = entries

    fun begin(title: String, kind: ModKind, fileName: String, target: File, totalBytes: Long): Entry =
        Entry(title, kind, fileName, target, totalBytes).also {
            entries += it
            notifyChanged()
        }

    fun clearFinished() {
        entries.removeAll { it.finished }
        notifyChanged()
    }

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    fun notifyChanged() {
        listeners.forEach { it() }
    }
}
