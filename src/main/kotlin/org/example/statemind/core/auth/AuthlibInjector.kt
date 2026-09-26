package org.example.statemind.core.auth

import org.example.statemind.core.GameDir
import org.example.statemind.core.MiniJson
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipFile

/**
 * authlib-injector —— 挂在 `-javaagent` 上的那个 jar。
 *
 * 游戏里「去哪儿验证账号」是写死的（Mojang 官方地址），第三方账号（LittleSkin 这类皮肤站）
 * 没法直接进。这个 jar 由 JVM 在启动时先加载，把那些地址改写成皮肤站的地址，
 * 皮肤、披风、开了外置登录验证的服务器才能正常工作。
 *
 * 本类只管两件事：
 *  1. [ensure]：把 jar 弄到手 —— 先找现成的（打包内置 / 数据目录缓存），没有才下载；
 *  2. [launchArgs]：产出启动时要加的 JVM 参数三件套。
 *
 * 具体走什么协议、令牌怎么来，是 [YggdrasilApi] 的事，这里不掺和。
 */
object AuthlibInjector {

    private const val JAR_NAME = "authlib-injector.jar"

    /** BMCLAPI 镜像的版本清单（国内直连，比 GitHub 快）。 */
    private const val MIRROR_LATEST =
        "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json"

    /** 兜底：GitHub 官方 releases（镜像抽风时还能下）。 */
    private const val GITHUB_LATEST =
        "https://api.github.com/repos/yushijinhun/authlib-injector/releases/latest"

    /** 下载到数据根目录：安装版 `%APPDATA%\StateMind`，便携版 `<解压目录>\data`（自动跟着走）。 */
    val cachedJar: File by lazy { File(GameDir.appRoot(), JAR_NAME) }

    /** 内存里的 metadata 预取缓存（同一服务器只抓一次）。 */
    private val metadataCache = HashMap<String, String>()

    /**
     * 手头已经能用的 jar，没有就 null。
     * 顺序：启动器自己所在目录（打包时能内置一份，离线也能用）→ 数据目录缓存。
     */
    fun find(): File? = candidates().firstOrNull { usable(it) }

    /**
     * 保证 jar 可用：有现成的直接用，没有就下载一份。
     *
     * @throws AuthException 下载失败或下到的东西不是个正常 jar
     */
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

    /**
     * 启动参数三件套（加在主类前面）：
     *  - `-javaagent:<jar>=<apiRoot>`：把 agent 挂上去，并告诉它用哪个认证服务器；
     *  - `-Dauthlibinjector.side=client`：声明是客户端；
     *  - `-Dauthlibinjector.yggdrasil.prefetched=<Base64>`：把服务器 metadata 直接喂给它，
     *    省掉游戏启动时再请求一次（拿不到就不加，agent 会自己去取）。
     */
    fun launchArgs(jar: File, serverApiRoot: String, metadataBase64: String?): List<String> = buildList {
        add("-javaagent:${jar.absolutePath}=$serverApiRoot")
        add("-Dauthlibinjector.side=client")
        if (!metadataBase64.isNullOrBlank()) {
            add("-Dauthlibinjector.yggdrasil.prefetched=$metadataBase64")
        }
    }

    /**
     * 抓服务器 metadata 并 Base64 编码，给 [launchArgs] 用。
     * 抓不到就返回 null（不影响启动，只是 agent 得多发一次请求）。
     */
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

    // ---------- 内部 ----------

    /** jar 可能待的地方：启动器自己所在目录（两个取法）+ 数据目录缓存。 */
    private fun candidates(): List<File> {
        val dirs = ArrayList<File>(2)
        runCatching {
            AuthlibInjector::class.java.protectionDomain?.codeSource?.location?.toURI()
        }.getOrNull()?.let { uri -> runCatching { File(uri).parentFile }.getOrNull()?.let(dirs::add) }
        runCatching { System.getProperty("user.dir") }.getOrNull()?.let { dirs.add(File(it)) }
        return dirs.map { File(it, JAR_NAME) } + cachedJar
    }

    /** 能算数的 jar：是个真文件、体积够、而且能当压缩包打开。 */
    private fun usable(file: File): Boolean {
        if (!file.isFile || file.length() < 50_000L) return false
        return runCatching {
            ZipFile(file).use { it.getEntry("META-INF/MANIFEST.MF") != null }
        }.getOrDefault(false)
    }

    /** 拿下载地址：先问 BMCLAPI 镜像，失败再问 GitHub。 */
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
