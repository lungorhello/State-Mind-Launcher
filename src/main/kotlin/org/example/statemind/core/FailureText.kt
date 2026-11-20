package org.example.statemind.core

/**
 * 把失败原因压成**一句人话**，外加一个粗分类用来汇总。
 *
 * 界面上要给人看的不是「SocketTimeoutException: Read timed out」，
 * 而是「网络连接不稳定」。同一个分类下的失败算一件事，
 * 弹窗里只报最主要的那一条，不把技术细节一股脑倒给用户。
 */
object FailureText {

    /** 一次失败：粗分类 + 人话。 */
    data class Reason(val category: String, val text: String)

    /** 粗分类。归并同类，才好说「主要原因是哪一类」。 */
    const val CAT_NETWORK = "网络"
    const val CAT_SERVER = "下载源"
    const val CAT_CHECK = "文件"
    const val CAT_UNKNOWN = "其他"

    fun of(error: Throwable): Reason {
        // 先看整条因果链：真正的原因往往裹在 IOException 里面
        var current: Throwable? = error
        while (current != null) {
            if (current is java.net.UnknownHostException ||
                current is java.net.ConnectException ||
                current is java.net.http.HttpTimeoutException ||
                current is java.net.SocketTimeoutException ||
                current is javax.net.ssl.SSLException
            ) {
                return Reason(CAT_NETWORK, "网络连接不稳定")
            }
            current = current.cause
        }

        val message = error.message.orEmpty()
        return when {
            message.contains("校验和", ignoreCase = true) ->
                Reason(CAT_CHECK, "文件校验不通过")

            message.contains("服务器上没有这个文件") ->
                Reason(CAT_SERVER, "下载源上已经没有这个文件")

            message.contains("服务器拒绝访问") || message.contains("请求被服务器拒绝") ->
                Reason(CAT_SERVER, "下载源拒绝了这次请求")

            message.contains("服务器暂时不可用") || message.contains("服务器返回 HTTP 5") ->
                Reason(CAT_SERVER, "下载源暂时不可用")

            message.contains("服务器响应超时") ->
                Reason(CAT_NETWORK, "网络连接不稳定")

            message.contains("无法从") || message.contains("获取列表") ->
                Reason(CAT_SERVER, "下载源没有给出这个文件的地址")

            else -> Reason(CAT_UNKNOWN, "下载过程中出现问题")
        }
    }
}
