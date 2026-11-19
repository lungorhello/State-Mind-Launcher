package org.example.statemind.core.mod

/**
 * 项目的常用中文名。
 *
 * Modrinth 的接口不本地化项目标题（带 `Accept-Language` 也只回英文原名），
 * 所以在文件名里写「中文译名」这件事只能自己带一张表。表里没有的原样用英文标题 ——
 * 宁可保留原名，也不要机翻出一个谁也认不出来的名字。
 */
object ModNames {

    private val ZH = mapOf(
        // 性能 / 基础库
        "sodium" to "钠",
        "sodium-extra" to "钠 · 扩展",
        "lithium" to "锂",
        "iris" to "鸢尾花",
        "indium" to "铟",
        "fabric-api" to "Fabric API",
        "modmenu" to "模组菜单",
        "cloth-config" to "Cloth Config API",
        "architectury-api" to "Architectury API",
        "geckolib" to "GeckoLib",
        "entityculling" to "实体渲染优化",
        "ferritecore" to "铁氧体核心",
        "embeddium" to "Embeddium",
        // 内容类
        "create" to "机械动力",
        "jei" to "JEI 物品管理器",
        "rei" to "REI 物品管理器",
        "appleskin" to "苹果皮",
        "jade" to "Jade",
        "wthit" to "WTHIT",
        "curios" to "Curios API",
        "patchouli" to "帕秋莉手册",
        "botania" to "植物魔法",
        "twilightforest" to "暮色森林",
        "immersiveengineering" to "沉浸工程",
        "mekanism" to "通用机械",
        "tconstruct" to "匠魂",
        "ae2" to "应用能源 2",
        "refinedstorage" to "精致存储",
        "waystones" to "传送石碑",
        "farmers-delight" to "农夫乐事",
        "sophisticated-backpacks" to "精妙背包",
        // 地图
        "xaeros-minimap" to "Xaero 的小地图",
        "xaeros-world-map" to "Xaero 的世界地图",
        "journeymap" to "旅行地图"
    )

    /** 有译名就用译名，没有再退回接口给的标题。 */
    fun zhOf(slug: String, title: String): String = ZH[slug.lowercase()] ?: title
}
