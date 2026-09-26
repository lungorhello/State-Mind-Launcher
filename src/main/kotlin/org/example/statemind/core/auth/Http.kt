package org.example.statemind.core.auth

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * 认证用的最简 HTTP 封装。
 *
 * 用 JDK 自带的 [HttpClient]：跟随重定向（ALI 地址解析要靠它）、超时可控、不引依赖。
 * 这里只管发请求和收回响应，**不解释**任何业务错误——那是 [YggdrasilApi] 的事。
 */
internal object Http {

    /** 一次响应：状态码 + 正文 + 最终地址（重定向之后）+ 响应头。 */
    class Response(
        val status: Int,
        val body: String,
        val uri: String,
        private val headers: Map<String, List<String>>,
    ) {
        /** 按名字取响应头，大小写不敏感（ALI 头就是这个用法）。 */
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()
    }

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    fun get(url: String, timeoutSeconds: Long = 15): Response =
        send(HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build())

    fun postJson(url: String, json: String, timeoutSeconds: Long = 15): Response =
        send(HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, Charsets.UTF_8))
            .build())

    /**
     * 下载二进制文件（authlib-injector.jar 走这里）。
     *
     * 先写 `.part` 再改名：中途断线不会留下半个文件骗过后面的完整性校验。
     * 给了 [sha256] 就顺带核对，对不上直接抛异常。
     */
    fun download(url: String, target: File, sha256: String? = null, timeoutSeconds: Long = 120) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.delete()

        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        if (res.statusCode() !in 200..299) {
            tmp.delete()
            throw IOException("HTTP ${res.statusCode()}")
        }
        if (sha256 != null && !sha256.equals(sha256Of(tmp), ignoreCase = true)) {
            tmp.delete()
            throw IOException("下载内容校验和不一致")
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private fun send(req: HttpRequest): Response {
        val res = client.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return Response(res.statusCode(), res.body() ?: "", res.uri().toString(), res.headers().map())
    }

    private const val USER_AGENT = "StateMindLauncher"
}
