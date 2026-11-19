package org.example.statemind.core.mod

import java.io.File
import org.example.statemind.core.BackendUtil
import org.example.statemind.core.LaunchSelection

/**
 * 社区资源最终落到哪一层目录。
 *
 * [gameDir] 是「当前选中的那份游戏目录」—— 它带着自己所属的版本根
 * （[org.example.statemind.core.LaunchTarget.versionRoot]），多目录并存时不用猜；
 * 没选或选的那份被删了才退回内置目录。
 *
 * 「每次下载时选择」那一档不由这里决定：目录是下载当时从系统的选择框里拿到的，
 * 见 `ModDetailView.destination()`。
 */
object InstallTargets {

    fun gameDir(): File {
        LaunchSelection.current?.versionRoot?.let { if (it.isDirectory) return it }
        return BackendUtil.minecraftDir
    }

    /** 存档列表，最近动过的在前 —— 装数据包时默认选中的那个。 */
    fun worlds(gameDir: File): List<File> {
        val dirs = File(gameDir, "saves").listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { it.lastModified() }
    }

    /**
     * 某一类内容的落点。数据包没有「全局」位置，必须挂在某个存档的 `datapacks/` 下，
     * 所以 [world] 对它是必需的。
     */
    fun dirFor(kind: ModKind, gameDir: File, world: String?): File = when (kind) {
        ModKind.DATAPACK -> File(File(File(gameDir, "saves"), world.orEmpty()), kind.dir)
        else -> File(gameDir, kind.dir)
    }
}
