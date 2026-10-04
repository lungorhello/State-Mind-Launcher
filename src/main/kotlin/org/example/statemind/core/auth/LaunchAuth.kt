package org.example.statemind.core.auth

import org.example.statemind.core.Account
import org.example.statemind.core.AccountType
import org.example.statemind.core.Prefs

/**
 * 启动前的账号准备：把当前选中的账号翻译成启动游戏要用的东西。
 *
 * [prepare] **会联网**（第三方账号），必须在后台线程调用。
 */
object LaunchAuth {

    data class Credentials(
        val playerName: String,
        /** 第三方账号用皮肤站给的；null 表示按离线算法算。 */
        val uuid: String? = null,
        val accessToken: String = "0",
        val userType: String = "legacy",
        val extraJvmArgs: List<String> = emptyList(),
    )

    sealed interface Outcome {
        data class Ready(val credentials: Credentials) : Outcome

        /**
         * 令牌失效且刷不回来，得请用户重输密码 —— 界面上由 `ui.ThirdPartySignIn.promptRelogin`
         * 承接，成功后调用方重跑 [prepare]。
         */
        data object NeedsRelogin : Outcome

        /** message 直接给用户看。 */
        data class Failed(val message: String) : Outcome
    }

    fun prepare(account: Account, onProgress: (String) -> Unit = {}): Outcome {
        if (account.type != AccountType.THIRD_PARTY) {
            return Outcome.Ready(Credentials(account.name))
        }

        // 账号清单里有、令牌仓库里没有：多半是账号文件被手工改过，重新登一次最省事
        val entry = AuthStore.find(account.id) ?: return Outcome.NeedsRelogin

        onProgress("校验登录状态…")
        val live = try {
            when (val status = AuthStore.ensureUsable(entry, Prefs.yggdrasilClientToken)) {
                is AuthStore.TokenStatus.Valid -> status.entry
                AuthStore.TokenStatus.NeedsRelogin -> return Outcome.NeedsRelogin
            }
        } catch (e: AuthException) {
            return Outcome.Failed(e.userText.ifBlank { "连不上皮肤站服务器，检查一下网络。" })
        }

        val jar = try {
            AuthlibInjector.ensure(onProgress)
        } catch (e: AuthException) {
            return Outcome.Failed(e.userText.ifBlank { "准备 authlib-injector 失败。" })
        }

        onProgress("准备皮肤站数据…")
        val metadata = AuthlibInjector.prefetchMetadata(live.toServer())

        return Outcome.Ready(
            Credentials(
                playerName = live.playerName,
                uuid = live.profileId,
                accessToken = live.accessToken,
                userType = "mojang",
                extraJvmArgs = AuthlibInjector.launchArgs(jar, live.serverApiRoot, metadata),
            )
        )
    }
}
