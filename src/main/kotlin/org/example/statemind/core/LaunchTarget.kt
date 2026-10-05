package org.example.statemind.core

import java.io.File

/**
 * 「要启动哪一个版本」。
 *
 * 抽出来是因为**同一个版本 id 可能同时存在于多个游戏目录里**，光有 id 不足以启动；而且
 * 「按游戏版本匹配 Java」「校验 Java 版本」都要读该版本的 json，也得知道去哪个目录找。
 */
data class LaunchTarget(
    /** `versions/` 下的文件夹名，也就是版本 json 的 id。 */
    val versionId: String,
    /** `versions/` 所在目录（版本 json、客户端 jar、natives 都在这儿）。 */
    val versionRoot: File,
    /** `libraries/` 与 `assets/` 所在目录。multi 系在目录根，多个实例共享。 */
    val sharedRoot: File,
    /** 来自哪个游戏目录（显示用）。 */
    val sourceName: String
)

/**
 * 首页当前选中的启动目标，全局唯一一份。
 *
 * 「设置 · 启动」要按它报出「将使用 Java x.y.z」—— 而那句话必须在**选定版本**上算，
 * 不能只存在首页那个下拉里。
 */
object LaunchSelection {
    var current: LaunchTarget? = null
}
