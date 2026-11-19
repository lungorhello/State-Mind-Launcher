package org.example.statemind.core

import java.io.File

/**
 * 「这个版本还没装完」的标记。
 *
 * 光看 `versions/<id>/<id>.json` 判断不了 —— 我们的下载流程会先把 json 落盘，再拉几百个
 * 资源文件；中途失败或取消时，json 已经在，首页下拉就会把一个残缺的版本当成能启动的。
 *
 * 只在**本次新建**的版本目录里留这个文件：别的启动器装好的版本没有它，不受影响。
 * 下载成功时删掉，失败 / 取消时留着，下次重下沿用同一片目录。
 */
object InstallMarker {

    private const val NAME = ".sml-incomplete"

    /** 目录不存在时先建出来。 */
    fun mark(versionDir: File) {
        runCatching {
            versionDir.mkdirs()
            File(versionDir, NAME).writeText("")
        }
    }

    fun clear(versionDir: File) {
        runCatching { File(versionDir, NAME).delete() }
    }

    fun isIncomplete(versionDir: File): Boolean = File(versionDir, NAME).isFile
}
