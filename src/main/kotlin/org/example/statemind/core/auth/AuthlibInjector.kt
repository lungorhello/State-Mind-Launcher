package org.example.statemind.core.auth

import org.example.statemind.core.GameDir
import org.example.statemind.core.MiniJson
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipFile

/**
 * authlib-injector：游戏到哪里验证账号是写死的（Mojang 官方地址），这个 jar 在 JVM 启动时
 * 把地址改写成皮肤站的，第三方账号的皮肤、披风和外置登录服务器才正常工作。
 */
object AuthlibInjector {

    private const val JAR_NAME = "authlib-injector.jar"

    private const val MIRROR_LATEST =
        "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json"

    private const val GITHUB_LATEST =
        "https://api.github.com/repos/yushijinhun/authlib-injector/releases/latest"

    val cachedJar: File by lazy { File(GameDir.appRoot(), JAR_NAME) }

    private val metadataCache = HashMap<String, String>()

    fun find(): File? = candidates().firstOrNull { usable(it) }

    /** @throws AuthException 下载失败，或下到的东西不是个正常 jar */
    fun ensure(onProgress: (String) -> Unit = {}): File {
        find()?.let { return it }

        onProgress("正在获取 authlib-injector…")
        val (url, sha256) = resolveDownload()
        try {
            Http.download(url, cachedJar, sha256)
        } catch (e: Exception) {
            cachedJar.delete()
            throw AuthException(
                AuthError.UNREACHABLE,
                "下载 authlib-injector 失败，检查一下网络再试一次。",
                cause = e
            )
        }
        if (!usable(cachedJar)) {
            cachedJar.delete()
            throw AuthException(AuthError.MALFORMED, "下载到的 authlib-injector 不完整，请再试一次。")
        }
        return cachedJar
    }

    /** prefetched 那条是省一次请求的优化：拿不到就不加，agent 自己去取。 */
    fun launchArgs(jar: File, serverApiRoot: String, metadataBase64: String?): List<String> = buildList {
        add("-javaagent:${jar.absolutePath}=$serverApiRoot")
        add("-Dauthlibinjector.side=client")
        if (!metadataBase64.isNullOrBlank()) {
            add("-Dauthlibinjector.yggdrasil.prefetched=$metadataBase64")
        }
    }

    fun prefetchMetadata(server: AuthServer): String? {
        synchronized(metadataCache) { metadataCache[server.apiRoot] }?.let { return it }
        return try {
            val body = Http.get(server.apiRoot).body
            if (body.isBlank()) return null
            Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
                .also { encoded -> synchronized(metadataCache) { metadataCache[server.apiRoot] = encoded } }
        } catch (_: Exception) {
            null
        }
    }

    /** 先找启动器所在目录 —— 打包时会内置一份，断网也能用；再退回数据目录缓存。 */
    private fun candidates(): List<File> {
        val dirs = ArrayList<File>(2)
        runCatching {
            AuthlibInjector::class.java.protectionDomain?.codeSource?.location?.toURI()
        }.getOrNull()?.let { uri -> runCatching { File(uri).parentFile }.getOrNull()?.let(dirs::add) }
        runCatching { System.getProperty("user.dir") }.getOrNull()?.let { dirs.add(File(it)) }
        return dirs.map { File(it, JAR_NAME) } + cachedJar
    }

    private fun usable(file: File): Boolean {
        if (!file.isFile || file.length() < 50_000L) return false
        return runCatching {
            ZipFile(file).use { it.getEntry("META-INF/MANIFEST.MF") != null }
        }.getOrDefault(false)
    }

    private fun resolveDownload(): Pair<String, String?> {
        runCatching {
            val root = MiniJson.parse(Http.get(MIRROR_LATEST).body) as? Map<*, *>
            val url = root?.get("download_url") as? String
            val sha = (root?.get("checksums") as? Map<*, *>)?.get("sha256") as? String
            if (!url.isNullOrBlank()) return url to sha
        }

        val assets = runCatching {
            val root = MiniJson.parse(Http.get(GITHUB_LATEST).body) as? Map<*, *>
            (root?.get("assets") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        }.getOrDefault(emptyList())

        val url = assets.mapNotNull { it["browser_download_url"] as? String }
            .firstOrNull { it.endsWith(".jar") }
            ?: throw IOException("两个下载源都没给出 authlib-injector 的地址")
        return url to null
    }
}
