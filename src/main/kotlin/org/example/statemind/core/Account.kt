package org.example.statemind.core

/**
 * 账号类型。
 *
 * 目前只有 [OFFLINE] 能真正添加（离线登录，只填名字、零注册）；
 * [MICROSOFT] 与 [THIRD_PARTY] 是计划中的可选模块，界面上先占位。
 */
enum class AccountType(val display: String) {
    OFFLINE("离线登录"),
    MICROSOFT("微软登录"),
    THIRD_PARTY("第三方登录");

    companion object {
        /** 从存档里的字符串还原，认不出就当离线。 */
        fun fromName(name: String?): AccountType =
            entries.firstOrNull { it.name == name } ?: OFFLINE
    }
}

/**
 * 一个账号。
 *
 * @param id   本地标识（用时间戳生成），只用于选中与存档，不参与游戏启动
 * @param name 玩家名（离线模式下就是进游戏显示的名字）
 * @param type 账号类型，决定徽标文字与将来的认证方式
 */
data class Account(
    val id: String,
    val name: String,
    val type: AccountType = AccountType.OFFLINE
)
