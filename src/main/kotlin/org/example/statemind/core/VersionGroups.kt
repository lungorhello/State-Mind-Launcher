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
}
