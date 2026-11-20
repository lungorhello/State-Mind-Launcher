package org.example.statemind.core.download

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import org.example.statemind.core.HttpText

/** 下载被用户取消。用它和「真的失败」区分开：取消不重试、也不报错。 */
internal class CancelledException : RuntimeException("已取消")

/**
 * 单个文件的流式下载：边读边写 `.part`、边报字节数（推进度 / 喂限速器），
 * 校验和过了才改名成正式文件 —— 中断不会留下半个文件骗过下一次的「已存在」判断。
 */
internal object Fetcher {

    private const val BUF = 64 * 1024
    private const val USER_AGENT = "StateMindLauncher"

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** 会阻塞。按 [DownloadItem.urls] 顺序试；全都失败才抛。 */
    fun fetch(item: DownloadItem, onBytes: (Int) -> Unit, cancelled: () -> Boolean) {
        var last: Exception? = null
        for (url in item.urls) {
            if (cancelled()) throw CancelledException()
            val outcome = runCatching { once(url, item, onBytes, cancelled) }
            if (outcome.isSuccess) return
            val error = outcome.exceptionOrNull()
            if (error is CancelledException) throw error
            last = error as? Exception ?: Exception(error)
        }
        val error = last
        throw IOException(if (error == null) "下载失败" else HttpText.of(error), error)
    }

    private fun once(url: String, item: DownloadItem, onBytes: (Int) -> Unit, cancelled: () -> Boolean) {
        item.target.parentFile?.mkdirs()
        val part = File(item.target.parentFile, item.target.name + ".part")
        part.delete()

        // 这里的 timeout 只管「响应头多久回来」：正文由 ofInputStream 慢慢读，
        // 用户把限速拉到 100 KB/s 时一个 25 MB 的客户端能下好几分钟，不能被请求超时掐断。
        val response = client.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream()
        )
        if (response.statusCode() !in 200..299) {
            part.delete()
            throw IOException(HttpText.of(response.statusCode()))
        }

        val digest = MessageDigest.getInstance("SHA-1")
        try {
            response.body().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUF)
                    while (true) {
                        if (cancelled()) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        onBytes(read)
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }

        if (item.sha1 != null && !item.sha1.equals(hex(digest.digest()), ignoreCase = true)) {
            part.delete()
            throw IOException("校验和不一致")
        }
        item.target.delete()
        if (!part.renameTo(item.target)) {
            part.copyTo(item.target, overwrite = true)
            part.delete()
        }
    }

    /**
     * 本地已有的文件还能不能用。
     *
     * 只比大小是不够的：同名同尺寸但内容坏掉的文件（下载中断留下的、别的启动器下错的）
     * 会被当成「已经下好了」，装上去才发现用不了。
     * 有校验和时按校验和判断 —— 对不上就是坏的，交给上层重下。
     */
    fun usable(item: DownloadItem): Boolean {
        val file = item.target
        if (!file.isFile) return false
        if (item.size > 0L && file.length() != item.size) return false
        val sha1 = item.sha1 ?: return item.size > 0L
        return verify(file, sha1)
    }

    /** 文件的 SHA-1 是不是 [sha1]。读不动 / 读一半失败都算「不可用」。 */
    fun verify(file: File, sha1: String): Boolean =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUF)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            hex(digest.digest()).equals(sha1, ignoreCase = true)
        }.getOrDefault(false)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
