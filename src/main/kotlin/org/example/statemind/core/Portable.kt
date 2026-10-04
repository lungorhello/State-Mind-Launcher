package org.example.statemind.core

import java.io.File

/**
 * 便携模式：解压目录里带 [MARKER] 就用包内 [DATA_DIR]，否则数据写 `%APPDATA%\StateMind`。
 *
 * 从程序自身位置往上找标记文件，不读注册表和环境变量；只有打包后才可能命中，
 * IDEA / `gradlew run` 里永远走安装版路径。
 */
object Portable {

    /** 放解压根目录（与 exe 同级）；删掉即退回安装版。 */
    const val MARKER = "portable.txt"

    const val DATA_DIR = "data"

    /** 带标记的解压根目录，没有则 null（每次重探，不缓存）。 */
    fun rootOrNull(): File? = candidateRoots().firstOrNull { File(it, MARKER).isFile }

    fun dataRootOrNull(): File? = rootOrNull()?.let { File(it, DATA_DIR) }

    private fun candidateRoots(): List<File> {
        val out = LinkedHashSet<File>()

        // ① 打包后运行：jpackage 会写下这个属性 = 启动器 exe 的绝对路径
        System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let { exe -> exeAndParents(File(exe).absoluteFile.parentFile, 1).forEach(out::add) }

        // ② 从本类被加载的位置反推。app-image 里是 <根>\app\<主 jar>
        runCatching {
            val cs = File(Portable::class.java.protectionDomain.codeSource?.location?.toURI() ?: return@runCatching)
            val dir = if (cs.isFile) cs.parentFile else cs
            exeAndParents(dir, 3).forEach(out::add)
        }

        // ③ 当前工作目录（快捷方式可以指定「起始位置」）
        System.getProperty("user.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { out.add(File(it).absoluteFile) }

        return out.toList()
    }

    private fun exeAndParents(start: File?, up: Int): List<File> {
        val out = ArrayList<File>()
        var cur = start
        var i = 0
        while (cur != null && i <= up) {
            out.add(cur)
            cur = cur.parentFile
            i++
        }
        return out
    }
}
