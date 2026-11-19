package org.example.statemind.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本次运行里最近装好的那个版本。
 *
 * 社区内容的版本筛选要先跟着它走 —— 刚下完 1.21.1，去找模组却按首页那个老版本筛，
 * 看到的全是「没有匹配」。只活在本进程里：重开启动器就该回到「首页选的是哪个就是哪个」。
 */
object RecentInstall {

    /** `versions/` 下那个文件夹的名字，首页据此把下拉选中到刚装好的这一项。 */
    @Volatile
    var versionId: String? = null
        private set

    @Volatile
    var mcVersion: String? = null
        private set

    /** 装的是加载器版本时，加载器名（`Fabric` 之类）；原版为 null。 */
    @Volatile
    var loaderLabel: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun remember(versionId: String, mc: String, loader: String?) {
        if (versionId.isBlank() || mc.isBlank()) return
        this.versionId = versionId
        mcVersion = mc
        loaderLabel = loader
        listeners.forEach { it() }
    }

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }
}
