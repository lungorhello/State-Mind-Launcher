package org.example.statemind.core

import java.io.File
import java.util.Properties
import java.util.UUID

/** 数据根目录下的 `settings.properties`；用标准 properties 格式，方便直接用记事本改。 */
object Prefs {

    private val file: File by lazy {
        File(GameDir.appRoot(), "settings.properties")
    }

    private val props: Properties by lazy {
        Properties().apply {
            runCatching {
                if (file.isFile) file.reader(Charsets.UTF_8).use { load(it) }
            }
        }
    }

    /** 删账号前是否弹确认框；用户勾过「不再提醒」后为 false。 */
    var confirmDelete: Boolean
        get() = readFlag(KEY_CONFIRM_DELETE, true)
        set(value) = writeFlag(KEY_CONFIRM_DELETE, value)

    /** Java 自动选择（默认开）；读写都收在 [JavaStore]。 */
    var autoJava: Boolean
        get() = readFlag(KEY_AUTO_JAVA, true)
        set(value) = writeFlag(KEY_AUTO_JAVA, value)

    var javaHome: String
        get() = props.getProperty(KEY_JAVA_HOME).orEmpty()
        set(value) {
            props.setProperty(KEY_JAVA_HOME, value)
            save()
        }

    /** 「设置 · 下载」：两处下载源的取法。存枚举名 —— 手改配置文件写错了退回默认，不至于打不开。 */
    var fileSource: FileSource
        get() = FileSource.of(props.getProperty(KEY_FILE_SOURCE))
        set(value) {
            props.setProperty(KEY_FILE_SOURCE, value.name)
            save()
        }

    var versionListSource: VersionListSource
        get() = VersionListSource.of(props.getProperty(KEY_VERSION_LIST_SOURCE))
        set(value) {
            props.setProperty(KEY_VERSION_LIST_SOURCE, value.name)
            save()
        }

    /** 「设置 · 下载」：同时下载几个文件。0 = 无限制，实际封顶 [THREADS_MAX]。 */
    var downloadThreads: Int
        get() = readInt(KEY_DOWNLOAD_THREADS, 0, THREADS_MAX)
        set(value) {
            props.setProperty(KEY_DOWNLOAD_THREADS, value.coerceIn(0, THREADS_MAX).toString())
            save()
        }

    /** 「设置 · 下载」：整机限速（KB/s）。0 = 不限速。 */
    var downloadSpeedKbps: Int
        get() = readInt(KEY_DOWNLOAD_SPEED_KBPS, 0, SPEED_MAX_KBPS)
        set(value) {
            props.setProperty(KEY_DOWNLOAD_SPEED_KBPS, value.coerceIn(0, SPEED_MAX_KBPS).toString())
            save()
        }

    /** 这个启动器实例的随机标识，生成一次长期复用：同一份 clientToken 发出去的令牌才能 refresh。 */
    val yggdrasilClientToken: String
        get() {
            props.getProperty(KEY_YGGDRASIL_CLIENT)?.let { if (it.isNotBlank()) return it }
            val token = UUID.randomUUID().toString()
            props.setProperty(KEY_YGGDRASIL_CLIENT, token)
            save()
            return token
        }

    val location: File get() = file

    /** 手改配置文件写了负数/离谱数值时夹回范围，不至于把界面或下载器搞坏。 */
    private fun readInt(key: String, defaultValue: Int, max: Int): Int =
        props.getProperty(key)?.trim()?.toIntOrNull()?.coerceIn(0, max) ?: defaultValue

    private fun readFlag(key: String, defaultValue: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: defaultValue

    private fun writeFlag(key: String, value: Boolean) {
        props.setProperty(key, value.toString())
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writer(Charsets.UTF_8).use { props.store(it, "State Mind Launcher · 偏好设置") }
        }
    }

    private const val KEY_CONFIRM_DELETE = "confirm.deleteAccount"
    private const val KEY_AUTO_JAVA = "java.autoSelect"
    private const val KEY_JAVA_HOME = "java.home"
    private const val KEY_YGGDRASIL_CLIENT = "auth.yggdrasilClientToken"
    private const val KEY_FILE_SOURCE = "download.fileSource"
    private const val KEY_VERSION_LIST_SOURCE = "download.versionListSource"
    private const val KEY_DOWNLOAD_THREADS = "download.threads"
    private const val KEY_DOWNLOAD_SPEED_KBPS = "download.speedKbps"

    /** 「无限制」时的实际线程上限：再高只会把磁盘和网络排队排死。 */
    const val THREADS_MAX = 64

    /** 限速滑条上限 100 MB/s —— 再高就没有限速的意义了。 */
    const val SPEED_MAX_KBPS = 102_400
}
