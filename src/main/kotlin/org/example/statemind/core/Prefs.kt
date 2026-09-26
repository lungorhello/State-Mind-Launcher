package org.example.statemind.core

import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * 启动器的轻量偏好存储。
 *
 * 存在**数据根目录**下的 `settings.properties` —— 和账号文件同一个目录（都在 .minecraft 之外），
 * 用标准的 properties 格式，可以直接用记事本改。只放「开关」这类零散设置，
 * 账号相关的数据仍然归 [AccountStore] 管。
 *
 * 数据根目录见 [GameDir.appRoot]：安装版 = `%APPDATA%\StateMind`，便携版 = `<解压目录>\data`。
 */
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

    /** 删除账号前是否还要弹确认框。用户在确认框里勾了「不再提醒」就变 false。 */
    var confirmDelete: Boolean
        get() = readFlag(KEY_CONFIRM_DELETE, true)
        set(value) = writeFlag(KEY_CONFIRM_DELETE, value)

    /**
     * Java 是否自动选择（全局）。默认开 —— 新手不用管，老手可以在「设置 · 启动」里关掉自己指定。
     * 读取与写入都收在 [JavaStore] 里，这里只负责落盘。
     */
    var autoJava: Boolean
        get() = readFlag(KEY_AUTO_JAVA, true)
        set(value) = writeFlag(KEY_AUTO_JAVA, value)

    /** 手动指定的 Java 目录（全局）。空 = 还没选过。 */
    var javaHome: String
        get() = props.getProperty(KEY_JAVA_HOME).orEmpty()
        set(value) {
            props.setProperty(KEY_JAVA_HOME, value)
            save()
        }

    /**
     * Yggdrasil 的 clientToken —— 代表「这个启动器实例」的随机标识，
     * 生成一次后长期复用：**同一份** clientToken 发出去的令牌才能被 refresh。
     * 第三方账号的令牌本体存在 auth.json（AuthStore），这里只放这一个标识。
     */
    val yggdrasilClientToken: String
        get() {
            props.getProperty(KEY_YGGDRASIL_CLIENT)?.let { if (it.isNotBlank()) return it }
            val token = UUID.randomUUID().toString()
            props.setProperty(KEY_YGGDRASIL_CLIENT, token)
            save()
            return token
        }

    /** 偏好文件位置，给需要展示路径的地方用。 */
    val location: File get() = file

    // ---------- 内部 ----------

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
}
