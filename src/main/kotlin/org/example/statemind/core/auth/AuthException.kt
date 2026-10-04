package org.example.statemind.core.auth

/** 界面按它选提示文案，不认服务器返回的 error 码。 */
enum class AuthError {
    /** 连不上、超时，或地址根本不是 Yggdrasil 服务器。 */
    UNREACHABLE,

    /** 账号或密码不对。令牌失效也归这里。 */
    BAD_CREDENTIALS,

    NO_PROFILE,

    /** 多半不是 Yggdrasil 服务器。 */
    MALFORMED,

    REJECTED,
}

/**
 * @param userText    给用户看的中文说明
 * @param serverError 服务器原样返回的 `error`，排查用
 */
class AuthException(
    val error: AuthError,
    val userText: String,
    val serverError: String? = null,
    val serverMessage: String? = null,
    cause: Throwable? = null,
) : Exception(userText, cause)
