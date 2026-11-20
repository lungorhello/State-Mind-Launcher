package org.example.statemind.core.install

import java.io.File
import java.io.IOException
import org.example.statemind.core.BackendUtil
import org.example.statemind.core.JavaStore
import org.example.statemind.core.Prefs

/**
 * 下载之后还要在本机跑一遍的活：Forge 的 processor、OptiFine 的补丁器。
 *
 * 它们都是**外部 Java 程序**，只能在文件都落盘之后跑；跑失败就意味着这个版本不完整，
 * 所以下载器把这一步算进任务状态里，不单独开一个「安装失败」的弹窗。
 */
interface InstallStep {
    val title: String

    /** 会阻塞。[report] 用来更新任务页那句说明，[cancelled] 返回 true 时尽快退出。 */
    fun run(report: (String) -> Unit, cancelled: () -> Boolean)
}

internal class InstallCancelled : RuntimeException("已取消")

/** `group:artifact:version[:classifier][@ext]` → maven 布局下的相对路径。 */
internal object MavenPath {
    fun of(coords: String): String? {
        val ext = coords.substringAfter('@', "jar").ifBlank { "jar" }
        val parts = coords.substringBefore('@').split(':')
        if (parts.size < 3) return null
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        return buildString {
            append("$group/$artifact/$version/$artifact-$version")
            if (classifier != null) append("-$classifier")
            append(".$ext")
        }
    }
}

/**
 * 一个要下载到库目录、但不是「库条目」的文件。
 *
 * 目前只有 mojmap：官方映射表由安装器的 processor 去 Mojang 官方服务器下，
 * 那个地址在国内基本连不上，所以改成我们自己下，落到 processor 期望的那个路径上。
 */
internal data class InstallFile(
    /** 库目录下的相对路径。 */
    val path: String,
    val url: String,
    val size: Long,
    val sha1: String?
)

internal object InstallJava {
    /**
     * 跑安装器用哪份 Java。
     *
     * 不能直接用启动器自己的运行时：jpackage 打出来的 runtime 里没有 `java.exe`，
     * 只有 dll。所以从扫到的 Java 里按版本要求挑，实在没有才退回 `java.home`。
     */
    fun exe(major: Int?): String {
        val known = JavaStore.all.ifEmpty {
            runCatching { BackendUtil.getJavas() }.getOrDefault(emptyList())
        }
        val picked = JavaStore.choose(known, major)
        val home = picked?.path ?: Prefs.javaHome.ifBlank { System.getProperty("java.home") }
        val direct = File(home)
        if (direct.isFile) return direct.absolutePath
        for (name in listOf("java.exe", "java")) {
            val exe = File(direct, "bin/$name")
            if (exe.isFile) return exe.absolutePath
        }
        throw IOException("找不到可用的 Java，无法运行安装器")
    }

    /** 版本 json 里的 javaVersion；读不到就 null，交给挑选逻辑按「未知」处理。 */
    fun requiredMajor(version: Map<*, *>): Int? =
        when (val major = (version["javaVersion"] as? Map<*, *>)?.get("majorVersion")) {
            is Number -> major.toInt()
            is String -> major.trim().toIntOrNull()
            else -> null
        }
}
