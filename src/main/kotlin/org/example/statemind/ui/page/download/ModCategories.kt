package org.example.statemind.ui.page.download

/**
 * Modrinth 的分类是英文 slug（`optimization`、`library`…），界面上照抄一串英文看不懂。
 * 常见的翻一下，没收录的原样显示 —— 缺词最多难看，不会出错。
 */
internal object ModCategories {

    private val ZH = mapOf(
        "optimization" to "性能优化",
        "decoration" to "装饰",
        "library" to "支持库",
        "utility" to "实用",
        "technology" to "科技",
        "adventure" to "冒险",
        "worldgen" to "世界生成",
        "mobs" to "生物",
        "equipment" to "装备",
        "magic" to "魔法",
        "storage" to "存储",
        "food" to "食物",
        "game-mechanics" to "游戏机制",
        "management" to "管理",
        "minigame" to "小游戏",
        "transportation" to "交通",
        "social" to "社交",
        "combat" to "战斗",
        "cursed" to "整活",
        "gui" to "界面",
        "realistic" to "写实",
        "simplistic" to "简约",
        "themed" to "主题",
        "tweaks" to "微调",
        "audio" to "音效",
        "fonts" to "字体",
        "blocks" to "方块",
        "items" to "物品",
        "structures" to "结构",
        "biomes" to "生物群系",
        "core-shaders" to "核心",
        "atmosphere" to "氛围",
        "fantasy" to "奇幻"
    )

    fun zh(slug: String): String = ZH[slug] ?: slug

    /** 卡片上那一行分类，最多 [limit] 个。 */
    fun text(slugs: List<String>, limit: Int = 2): String =
        slugs.take(limit).joinToString(" ") { zh(it) }
}
