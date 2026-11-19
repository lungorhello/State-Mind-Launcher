package org.example.statemind.core

/**
 * HTTP 状态码 → 界面上能读的一句话。
 *
 * 任务页原样显示「HTTP 404」时，用户既不知道是哪个环节，也不知道该改什么。
 * 这里只翻译状态，具体是谁、哪一步由调用方补在前面。
 */
object HttpText {

    fun of(code: Int): String = when (code) {
        400 -> "请求被服务器拒绝"
        401, 403 -> "服务器拒绝访问"
        404 -> "服务器上没有这个文件"
        405 -> "服务器不接受这种请求"
        408 -> "服务器响应超时"
        429 -> "请求过于频繁，稍后再试"
        in 500..599 -> "服务器暂时不可用"
        else -> "服务器返回 HTTP $code"
    }

    /** 连接层面的异常。JDK 自带的 message 是英文，直接摆到界面上等于没说。 */
    fun of(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "找不到服务器，请检查网络连接"
        is java.net.ConnectException -> "无法连接服务器"
        is java.net.http.HttpTimeoutException -> "服务器响应超时"
        is java.net.SocketTimeoutException -> "服务器响应超时"
        is javax.net.ssl.SSLException -> "与服务器的安全连接失败"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "网络错误"
    }
}
