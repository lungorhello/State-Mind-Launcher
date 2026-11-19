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

/** 认证用的最简 HTTP 封装，跟随重定向（ALI 地址解析要靠它）。 */
internal object Http {

    class Response(
        val status: Int,
        val body: String,
        val uri: String,
        private val headers: Map<String, List<String>>,
    ) {
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
        get(url, emptyMap(), timeoutSeconds)

    /**
     * 取一小段二进制（图标这类）。失败返回 null —— 少一张图不该把整页炸掉。
     *
     * 共用 [client]：同一批图标复用同一条连接，省掉每个图标一次 TLS 握手；
     * 那一步在跨国网络上往往比传图本身还慢。
     */
    fun getBytes(url: String, timeoutSeconds: Long = 20): ByteArray? = runCatching {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (res.statusCode() in 200..299) res.body() else null
    }.getOrNull()

    /** 带自定义请求头的 GET（CurseForge 的 `x-api-key` 就走这条）。 */
    fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long = 15): Response {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
        // 用 setHeader 而不是 header：自定义头里带 User-Agent 时要替换掉默认那条，
        // 否则请求会带两个 UA（Modrinth 这类服务会按它限流）。
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        return send(builder.build())
    }

    fun postJson(url: String, json: String, timeoutSeconds: Long = 15): Response =
        send(HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, Charsets.UTF_8))
            .build())

    /**
     * 下载二进制文件。先写 `.part` 再改名：中途断线不会留下半个文件骗过完整性校验。
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
