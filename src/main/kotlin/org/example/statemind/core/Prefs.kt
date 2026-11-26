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

    /**
     * 实例页是否列出「共享原版材料」—— 被加载器版本 `inheritsFrom` 指回去的那份原版。
     * 默认不列：它不是玩家装的版本，点进去只会绕过加载器。默认关，需要时自己打开。
     */
    var showBaseVersions: Boolean
        get() = readFlag(KEY_SHOW_BASE, false)
        set(value) = writeFlag(KEY_SHOW_BASE, value)

    /**
     * 「设置 · 启动」：内存分配是不是自动。
     *
     * 默认自动。以前这个值是写死在 [LaunchUtil.Config] 里的 4096，界面上一个字都看不到，
     * 装大整合包时只会莫名其妙地卡住或者崩掉。
     */
    var memoryAuto: Boolean
        get() = readFlag(KEY_MEMORY_AUTO, true)
        set(value) = writeFlag(KEY_MEMORY_AUTO, value)

    /** 自定义分配（MB）。0 = 还没手动调过，切到自定义时从自动值起步。 */
    var memoryMb: Int
        get() = props.getProperty(KEY_MEMORY_MB)?.trim()?.toIntOrNull()
            ?.coerceIn(MachineMemory.MIN_MB, MachineMemory.MAX_MB) ?: 0
        set(value) {
            props.setProperty(KEY_MEMORY_MB, value.coerceIn(MachineMemory.MIN_MB, MachineMemory.MAX_MB).toString())
            save()
        }

    /** GC 方案。默认不传参数，交给 JVM；两档调优见 [GcPreset]。 */
    var gc: GcPreset
        get() = GcPreset.of(props.getProperty(KEY_GC))
        set(value) {
            props.setProperty(KEY_GC, value.name)
            save()
        }

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

    /** 「设置 · 下载」：模组 / 资源包 / 光影装到「当前选中的版本」，还是每次下载时现选一个目录。 */
    var resourceTarget: ResourceTarget
        get() = ResourceTarget.of(props.getProperty(KEY_RESOURCE_TARGET))
        set(value) {
            props.setProperty(KEY_RESOURCE_TARGET, value.name)
            save()
        }

    /** 上次在下载框里挑过的目录。只用来定下次弹框的起始位置，不决定任何文件去哪。 */
    var resourceDir: String
        get() = props.getProperty(KEY_RESOURCE_DIR).orEmpty()
        set(value) {
            props.setProperty(KEY_RESOURCE_DIR, value)
            save()
        }

    var resourceNaming: ResourceNaming
        get() = ResourceNaming.of(props.getProperty(KEY_RESOURCE_NAMING))
        set(value) {
            props.setProperty(KEY_RESOURCE_NAMING, value.name)
            save()
        }

    /**
     * 「实例」页上钉住的那份游戏目录：新版本的 json 与客户端落在这里。
     * 空串表示没钉过，用内置的 `StateMind\.minecraft`。
     * 存路径而不是序号 —— 用户自加的目录会被移除，序号会错位。
     */
    var downloadDir: String
        get() = props.getProperty(KEY_DOWNLOAD_DIR).orEmpty()
        set(value) {
            props.setProperty(KEY_DOWNLOAD_DIR, value)
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
    private const val KEY_RESOURCE_TARGET = "download.resourceTarget"
    private const val KEY_RESOURCE_DIR = "download.resourceDir"
    private const val KEY_RESOURCE_NAMING = "download.resourceNaming"
    private const val KEY_DOWNLOAD_DIR = "download.dir"
    private const val KEY_SHOW_BASE = "instance.showBase"
    private const val KEY_MEMORY_AUTO = "launch.memoryAuto"
    private const val KEY_MEMORY_MB = "launch.memoryMb"
    private const val KEY_GC = "launch.gc"

    /** 「无限制」时的实际线程上限：再高只会把磁盘和网络排队排死。 */
    const val THREADS_MAX = 64

    /** 限速滑条上限 100 MB/s —— 再高就没有限速的意义了。 */
    const val SPEED_MAX_KBPS = 102_400
}
