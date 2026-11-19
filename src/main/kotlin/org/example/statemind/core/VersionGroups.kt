package org.example.statemind.core

import java.time.ZoneOffset

object VersionGroups {

    enum class Group(val label: String) {
        RELEASE("正式版"),
        SNAPSHOT("预览版"),
        OLD("远古版"),
        APRIL_FOOLS("愚人节版")
    }

    val ORDER: List<Group> = listOf(Group.RELEASE, Group.SNAPSHOT, Group.OLD, Group.APRIL_FOOLS)

    fun classify(versions: List<VersionEntry>): Map<Group, List<VersionEntry>> =
        versions.groupBy { groupOf(it) }

    // 愚人节版本质也是快照，这个分支必须先于 SNAPSHOT
    fun groupOf(v: VersionEntry): Group = when {
        v.type == VersionEntry.Type.RELEASE -> Group.RELEASE
        isAprilFools(v) -> Group.APRIL_FOOLS
        v.type == VersionEntry.Type.SNAPSHOT -> Group.SNAPSHOT
        else -> Group.OLD
    }

    private fun isAprilFools(v: VersionEntry): Boolean {
        // 「快照」这个条件不能省：正式版 26.1.1 也是 4 月 1 日发布
        if (v.type != VersionEntry.Type.SNAPSHOT) return false
        if (v.id in APRIL_FOOLS_EXTRA) return true
        val utc = v.releaseTime?.atZoneSameInstant(ZoneOffset.UTC) ?: return false
        return utc.monthValue == 4 && utc.dayOfMonth == 1
    }

    // 跨时区差了一天、按日期判不出来的愚人节版本（1.RV-Pre1 的 UTC 是 3/31）
    private val APRIL_FOOLS_EXTRA: Set<String> = setOf("1.RV-Pre1")

    /**
     * 愚人节版本那一句玩笑。官方清单里没有描述字段，只能自己带一份 ——
     * 版本号本身看不出它是干嘛的（`23w13a_or_b` 念都念不出来）。
     */
    private val APRIL_FOOLS_BLURB: Map<String, String> = mapOf(
        "26w14a" to "为什么需要物品栏？让方块们跟着你走吧！",
        "25w14craftmine" to "你可以合成任何东西——包括合成你的世界！",
        "24w14potato" to "毒马铃薯一直都被大家忽视和低估，于是我们超级加强了它！",
        "23w13a_or_b" to "研究表明：玩家喜欢作出选择——越多越好！",
        "22w13oneblockatatime" to "一次一个方块更新！迎接全新的挖掘、合成与骑乘玩法吧！",
        "20w14infinite" to "我们加入了 20 亿个新的维度，让无限的想象变成了现实！",
        "3D Shareware v1.34" to "我们从地下室的废墟里找到了这个开发于 1994 年的杰作！",
        "1.RV-Pre1" to "是时候将现代科技带入 Minecraft 了！",
        "15w14a" to "作为一款全年龄向的游戏，我们需要和平，需要爱与拥抱。"
    )

    /** 愚人节版本的说明；不是愚人节版本、或表里没有的返回 null。 */
    fun blurbOf(id: String): String? = APRIL_FOOLS_BLURB[id]
}
