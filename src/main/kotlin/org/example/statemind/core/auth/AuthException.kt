package org.example.statemind.core.auth

/**
 * 认证失败的类别。界面只需要看这个给提示，不用去认服务器返回的 error 码。
 */
enum class AuthError {
    /** 连不上 / 超时 / 地址根本不是个 Yggdrasil 服务器。 */
    UNREACHABLE,

    /** 账号或密码不对（令牌失效也归这里）。 */
    BAD_CREDENTIALS,

    /** 登录过了，但这个账号一个角色都没有。 */
    NO_PROFILE,

    /** 服务器返回的东西看不懂（多半不是 Yggdrasil 服务器）。 */
    MALFORMED,

    /** 服务器明确拒绝了请求，且不在上面的情况里。 */
    REJECTED,
}

/**
 * 认证异常。
 *
 * @param error         失败类别，界面按它选语气（info / warn / error）
 * @param userText      直接给用户看的中文说明
 * @param serverError   服务器原样返回的 `error` 字段（排查用，界面上不必显示）
 * @param serverMessage 服务器原样返回的 `errorMessage`
 */
class AuthException(
    val error: AuthError,
    val userText: String,
    val serverError: String? = null,
    val serverMessage: String? = null,
    cause: Throwable? = null,
) : Exception(userText, cause)
