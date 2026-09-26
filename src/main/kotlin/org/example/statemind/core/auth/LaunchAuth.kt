package org.example.statemind.core.auth

import org.example.statemind.core.Account
import org.example.statemind.core.AccountType
import org.example.statemind.core.Prefs

/**
 * 启动前的「账号准备」——把「当前选中的账号」翻译成启动游戏要用的东西。
 *
 * 三类账号的差别都在这里收口，[org.example.statemind.core.LaunchUtil] 只管拼命令行：
 *  - 离线：玩家名直接用账号名，UUID 由启动器按官方算法算；
 *  - 第三方：先校验令牌（过期就刷新，[AuthStore.ensureUsable]），再备好 authlib-injector，
 *    产出 `-javaagent` 三件套 + 角色 UUID，`user_type` 填 `mojang`（外置登录也走这套模板）；
 *  - 微软：还没实现，暂时按离线处理（界面上也还加不了这类账号）。
 *
 * 这个方法**会联网**（第三方账号），必须在后台线程调用。
 */
object LaunchAuth {

    /** 给启动器用的一份凭证。 */
    data class Credentials(
        val playerName: String,
        /** 角色 UUID（第三方账号用皮肤站给的）；null 表示按离线算法算。 */
        val uuid: String? = null,
        val accessToken: String = "0",
        val userType: String = "legacy",
        /** 额外的 JVM 参数（外置登录就是 `-javaagent` 那三条）。 */
        val extraJvmArgs: List<String> = emptyList(),
    )

    sealed interface Outcome {
        /** 可以启动了。 */
        data class Ready(val credentials: Credentials) : Outcome

        /**
         * 令牌彻底失效，刷新也救不回来 —— 得请用户**重新输一次密码**。
         * 界面上由 `ui.ThirdPartySignIn.promptRelogin` 弹窗承接，登录成功后调用方重跑一遍 [prepare]。
         */
        data object NeedsRelogin : Outcome

        /** 网络不通 / agent 下载失败 —— 附一句能直接给用户看的原因。 */
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
