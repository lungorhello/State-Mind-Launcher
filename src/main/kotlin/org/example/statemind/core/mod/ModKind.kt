package org.example.statemind.core.mod

/**
 * 下载页上能浏览的社区内容。`projectType` 是 Modrinth 的筛选值，
 * [dir] 是装进游戏目录时用的子目录。
 *
 * [installable] 为 false 的只有整合包：它是 mrpack（zip 里带安装清单与覆盖文件），
 * 要解包、按清单下依赖再合并覆盖，没法像单个文件那样直接落盘。
 * 数据包虽然也不能直接放进游戏目录，但可以放进某个存档的 `datapacks/` —— 选存档那步在界面上。
 *
 * [minGameVersion] 是这一类内容最早能用的游戏版本：比它更老的版本选出来永远是零结果，
 * 留在筛选里只会让人白点。界限来自内容格式本身的引入时间（资源包 1.6.1、数据包 1.13、
 * 模组与光影实际可用的起点 1.7.10），不是对着某个加载器版本特判。
 */
enum class ModKind(
    val label: String,
    val projectType: String,
    val dir: String,
    val installable: Boolean,
    val filterByLoader: Boolean,
    val minGameVersion: String
) {
    MOD("模组", "mod", "mods", true, true, "1.7.10"),
    RESOURCE_PACK("资源包", "resourcepack", "resourcepacks", true, false, "1.6.1"),
    SHADER("光影包", "shader", "shaderpacks", true, false, "1.7.10"),
    MODPACK("整合包", "modpack", "modpacks", false, false, "1.7.10"),
    DATAPACK("数据包", "datapack", "datapacks", true, false, "1.13");

    /** 装到当前游戏目录里还需要再选一层（存档）的。 */
    val needsWorld: Boolean get() = this == DATAPACK
}
