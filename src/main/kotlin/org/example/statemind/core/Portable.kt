package org.example.statemind.core

import java.io.File

/**
 * 便携模式（Portable）判定。
 *
 * 启动器有两种运行形态，区别只在「数据放哪」：
 *
 *  - **安装版（MSI）**：程序装在 `%LOCALAPPDATA%\State Mind Launcher` 下，数据写 `%APPDATA%\StateMind\`。
 *    卸载启动器不会碰游戏数据（要清也是卸载时单独勾选）。
 *  - **便携版（ZIP）**：解压到哪数据就在哪。解压目录里带一个 [MARKER] 标记文件，
 *    游戏数据与账号/设置全部落在解压包内的 [DATA_DIR] 里 —— 整个文件夹拷进 U 盘，
 *    换台电脑插上就能接着玩，不在这台机器上留任何东西。
 *
 * 判定方式是「**从程序自己的位置往上找标记文件**」，不依赖任何注册表或环境变量，
 * 所以同一份 exe 放在哪、以什么方式启动都能正确判断：
 *  - 打包后运行（app-image / MSI）时可以拿到启动器 exe 的绝对路径；
 *  - 兜底按代码位置反推（app-image 里就是 `<根>\app\<主 jar>`）；
 *  - 再兜底看当前工作目录（用快捷方式指定「起始位置」启动的情况）。
 *
 * ⚠️ 只有在**打包后**运行才可能命中带标记的目录；在 IDEA / `gradlew run` 里跑永远不会命中，
 * 开发者本机因此总能走安装版路径，不会污染工程目录。
 */
object Portable {

    /**
     * 便携标记文件名。放在**解压根目录**（和启动器 exe 同级）。
     * ZIP 包自带；删掉它，这个解压目录就退回安装版行为（数据写 `%APPDATA%`）。
     */
    const val MARKER = "portable.txt"

    /**
     * 数据子目录名，位于解压根目录下，等价于安装版的 `%APPDATA%\StateMind`：
     * 里面有 `.minecraft/`（游戏数据）、`settings.properties`（启动器设置）、`accounts.txt`（账号）。
     */
    const val DATA_DIR = "data"

    /**
     * 找到带标记的「解压根目录」；没找到 = 非便携模式，返回 null。
     * 每次都重新探测（不缓存），方便测试直接构造临时目录来断言。
     */
    fun rootOrNull(): File? = candidateRoots().firstOrNull { File(it, MARKER).isFile }

    /** 便携模式下的数据根目录（`<解压根>/data`）；非便携模式返回 null。 */
    fun dataRootOrNull(): File? = rootOrNull()?.let { File(it, DATA_DIR) }

    /** 依次列出可能的应用根目录，越前面的越可信。 */
    private fun candidateRoots(): List<File> {
        val out = LinkedHashSet<File>()

        // ① 打包后运行：jpackage 启动器会写下这个属性 = 启动器 exe 的绝对路径
        System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let { exe -> exeAndParents(File(exe).absoluteFile.parentFile, 1).forEach(out::add) }

        // ② 兜底：从「本类被加载的位置」反推。app-image 里是 <根>\app\<主 jar>
        runCatching {
            val cs = File(Portable::class.java.protectionDomain.codeSource?.location?.toURI() ?: return@runCatching)
            val dir = if (cs.isFile) cs.parentFile else cs
            exeAndParents(dir, 3).forEach(out::add)
        }

        // ③ 再兜底：当前工作目录（快捷方式可以指定「起始位置」）
        System.getProperty("user.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { out.add(File(it).absoluteFile) }

        return out.toList()
    }

    /** start 自己，以及往上 [up] 层父目录。 */
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
