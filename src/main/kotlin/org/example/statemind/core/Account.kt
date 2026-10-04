package org.example.statemind.core

enum class AccountType(val display: String) {
    OFFLINE("离线登录"),
    MICROSOFT("微软登录"),
    THIRD_PARTY("第三方登录");

    companion object {
        fun fromName(name: String?): AccountType =
            entries.firstOrNull { it.name == name } ?: OFFLINE
    }
}

/** @param id 本地标识，只用于选中与存档，不参与启动 */
data class Account(
    val id: String,
    val name: String,
    val type: AccountType = AccountType.OFFLINE
)
