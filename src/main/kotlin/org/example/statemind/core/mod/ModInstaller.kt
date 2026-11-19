package org.example.statemind.core.mod

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import org.example.statemind.core.HttpText

/**
 * 把一个文件下到指定目录里。会阻塞，调用方负责扔后台线程。
 *
 * 落点由调用方算好（[InstallTargets]）：模组、资源包、光影、数据包的目录规则不一样，
 * 数据包还依赖用户选的存档，这些判断不适合塞进这里。
 */
object ModInstaller {

    private const val BUF = 64 * 1024
    private const val UA = "StateMindLauncher/0.3 (github.com/lungorhello/State-Mind-Launcher)"

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    /** [entry] 给了就顺手把进度写进安装流水账（任务页据此显示）。返回落盘后的文件。 */
    fun install(
        dir: File,
        file: Modrinth.File,
        targetName: String,
        entry: InstallLog.Entry? = null,
        cancelled: () -> Boolean = { false }
    ): File {
        if (file.url.isBlank()) throw IOException("这个文件没有下载地址")

        dir.mkdirs()
        val target = File(dir, targetName)
        // 同名同大小就当作已装好：文件名里通常带版本号，重名基本意味着同一个文件
        if (target.isFile && (file.size <= 0L || target.length() == file.size)) {
            entry?.let {
                it.doneBytes = target.length()
                it.state = InstallLog.State.DONE
            }
            return target
        }

        val part = File(dir, "$targetName.part")
        part.delete()

        try {
            val request = HttpRequest.newBuilder(URI.create(file.url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw IOException("文件下载失败：${HttpText.of(response.statusCode())}")
            }

            val digest = MessageDigest.getInstance("SHA-1")
            var read = 0L
            var lastPublish = 0L
            response.body().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUF)
                    while (true) {
                        if (cancelled()) throw IOException("已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        read += count
                        // 每个 64KB 块都回写一次会让界面线程忙死，按时间间隔节流
                        val now = System.currentTimeMillis()
                        if (entry != null && now - lastPublish >= 200L) {
                            lastPublish = now
                            entry.doneBytes = read
                            InstallLog.notifyChanged()
                        }
                    }
                }
            }

            if (file.sha1.isNotBlank() && !file.sha1.equals(hex(digest.digest()), ignoreCase = true)) {
                throw IOException("文件校验不通过，可能没下完整")
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            entry?.let {
                it.doneBytes = target.length()
                it.state = InstallLog.State.DONE
            }
            return target
        } catch (e: Exception) {
            part.delete()
            entry?.let {
                it.state = InstallLog.State.FAILED
                it.message = e.message ?: e.toString()
            }
            throw e
        } finally {
            InstallLog.notifyChanged()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
