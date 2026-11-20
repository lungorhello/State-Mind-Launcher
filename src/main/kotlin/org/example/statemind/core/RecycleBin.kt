package org.example.statemind.core

import java.awt.Desktop
import java.io.File

/**
 * 删除用户的东西，能进回收站就别永久删。
 *
 * 删一个版本是几个 G 的库和存档配置，而入口只是一个按钮 —— 手滑一次就没了的代价太高。
 * [Desktop.moveToTrash] 在 Windows 上走 Shell，进的就是系统回收站，可以右键还原；
 * JDK 自带，不用为此引第三方库或拉外部进程。
 */
object RecycleBin {

    /**
     * 把 [target]（文件或整个目录）移到回收站。
     *
     * 返回 false 表示**没有删掉**：文件被占用，或当前系统/会话不支持回收站。
     * 这时候不要退回去永久删除 —— 用户以为还能还原，实际已经没了。
     */
    fun moveToTrash(target: File): Boolean {
        if (!target.exists()) return true
        return runCatching {
            val desktop = Desktop.getDesktop()
            desktop.isSupported(Desktop.Action.MOVE_TO_TRASH) && desktop.moveToTrash(target)
        }.getOrDefault(false)
    }
}
