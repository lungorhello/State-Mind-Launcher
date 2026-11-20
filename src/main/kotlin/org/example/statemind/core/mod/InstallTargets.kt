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

    /**
     * 模组 / 光影 / 资源包该放进哪一份游戏目录。
     *
     * 启动时默认开版本隔离（见 `LaunchUtil`），游戏读的是 `versions/<版本>/mods`；
     * 放回 `.minecraft/mods` 的话文件下得再对也不会被加载。所以优先用首页选中
     * 那个版本的隔离目录，目录不存在（别的启动器装的、没建过）才退回版本根。
     */
    fun gameDir(): File {
        LaunchSelection.current?.let { target ->
            val isolated = File(target.versionRoot, "versions/${target.versionId}")
            if (isolated.isDirectory) return isolated
            if (target.versionRoot.isDirectory) return target.versionRoot
        }
        return BackendUtil.minecraftDir
    }

    /**
     * 某一类内容的落点。
     *
     * 数据包也一样落在游戏目录的 `datapacks/` 下 —— 跟 PCL 等启动器一致，
     * 不再让人先选存档。要让某个存档用上，把文件拖进它的 `datapacks/` 即可。
     */
    fun dirFor(kind: ModKind, gameDir: File): File = File(gameDir, kind.dir)
}
