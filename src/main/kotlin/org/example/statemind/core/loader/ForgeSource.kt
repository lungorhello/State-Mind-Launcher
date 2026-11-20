package org.example.statemind.core.loader

import java.util.concurrent.ConcurrentHashMap
import org.example.statemind.core.MiniJson

/**
 * Forge / NeoForge 的版本清单与安装包地址。
 *
 * 这两家没有 Fabric 那种「现成的 profile json」：安装包是官方安装器，一个 jar 里同时装着
 * `install_profile.json`（要做什么）和 `version.json`（装完长什么样）。清单与安装器都走
 * BMCLAPI —— 它按游戏版本一次请求就能给出全部构建；files.minecraftforge.net 那个站点
 * 对非浏览器请求经常直接 403，maven.neoforged.net 在部分网络下更是不通。
 */
object ForgeSource {

    private const val BMCL = "https://bmclapi2.bangbang93.com"
    private const val FORGE_MAVEN = "https://maven.minecraftforge.net"
    private const val NEO_MAVEN = "https://maven.neoforged.net/releases"

    /** NeoForge 只在 1.20.1 用了 Forge 的坐标（net.neoforged:forge），再往后单独一个包。 */
    private const val NEO_LEGACY_MC = "1.20.1"

    private val cache = ConcurrentHashMap<String, List<LoaderVersion>>()

    /**
     * 解析清单时顺手记下安装器地址。
     *
     * 同一个接口里的版本号写法并不统一：NeoForge 1.20.1 有的条目叫 `47.1.5`、有的叫
     * `1.20.1-47.1.105`，老 Forge 还会把分支名接在文件名末尾。纯靠字符串拼地址迟早拼错，
     * 所以清单里给了 `installerPath` 就直接用；没给时才按坐标猜。
     */
    private val installers = ConcurrentHashMap<String, List<String>>()

    fun cached(kind: LoaderKind, mcVersion: String): List<LoaderVersion>? =
        cache[key(kind, mcVersion)]

    fun versions(kind: LoaderKind, mcVersion: String): List<LoaderVersion> {
        cache[key(kind, mcVersion)]?.let { return it }
        val list = when (kind) {
            LoaderKind.FORGE -> forge(mcVersion)
            LoaderKind.NEOFORGE -> neo(mcVersion)
            else -> emptyList()
        }
        cache[key(kind, mcVersion)] = list
        return list
    }

    /**
     * `[{version, mcversion, branch, files: [{category, format}]}]`。
     * 只留带 `installer/jar` 的构建 —— mdk 与 changelog 也在同一条里，不筛会混进下拉。
     */
    private fun forge(mcVersion: String): List<LoaderVersion> {
        val body = LoaderSource.fetchText("$BMCL/forge/minecraft/$mcVersion")
        val list = MiniJson.parse(body) as? List<*> ?: return emptyList()
        val parsed = list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val version = (map["version"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val hasInstaller = (map["files"] as? List<*>)
                ?.filterIsInstance<Map<*, *>>()
                ?.any { it["category"] == "installer" && it["format"] == "jar" }
                ?: false
            if (!hasInstaller) return@mapNotNull null
            val branch = (map["branch"] as? String).orEmpty()
            installers[installerKey(LoaderKind.FORGE, mcVersion, version)] =
                forgeInstallerUrls(mcVersion, version, branch)
            LoaderVersion(version, branch.isBlank() && !preRelease(version))
        }.distinctBy { it.id }
        return VersionOrder.newestFirst(parsed)
    }

    /** `[{rawVersion, version, mcversion, installerPath}]`；`version` 是下拉里显示的号。 */
    private fun neo(mcVersion: String): List<LoaderVersion> {
        val body = LoaderSource.fetchText("$BMCL/neoforge/list/$mcVersion")
        val list = MiniJson.parse(body) as? List<*> ?: return emptyList()
        val parsed = list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val raw = (map["version"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val version = normalizeNeo(mcVersion, raw)
            installers[installerKey(LoaderKind.NEOFORGE, mcVersion, version)] =
                neoInstallerUrls(mcVersion, version, map["installerPath"] as? String)
            LoaderVersion(version, !preRelease(version))
        }.distinctBy { it.id }
        return VersionOrder.newestFirst(parsed)
    }

    /** 1.20.1 那批的 `version` 有的带 `1.20.1-`、有的还多带 `forge-`，统一成纯加载器号。 */
    private fun normalizeNeo(mcVersion: String, version: String): String {
        if (!version.startsWith("$mcVersion-")) return version
        return version.removePrefix("$mcVersion-").removePrefix("forge-")
    }

    private fun preRelease(version: String): Boolean =
        listOf("alpha", "beta", "pre", "rc").any { version.contains(it, ignoreCase = true) }

    /**
     * 安装包候选地址，按「能用的排前面、猜的排后面」给。老版本（1.7.10 那一批）的目录名
     * 多带一段分支号、文件名又多带一段游戏版本号，所以三种拼法都要试；最后一条是 BMCLAPI
     * 自己的下载接口，它按参数现取文件，不依赖文件名怎么拼。
     */
    private fun forgeInstallerUrls(mcVersion: String, version: String, branch: String): List<String> {
        val classifier = "$mcVersion-$version" + if (branch.isBlank()) "" else "-$branch"
        return listOf(
            "$FORGE_MAVEN/net/minecraftforge/forge/$classifier/forge-$classifier-installer.jar",
            "$FORGE_MAVEN/net/minecraftforge/forge/$classifier-$mcVersion/forge-$classifier-$mcVersion-installer.jar",
            "$BMCL/forge/download?mcversion=$mcVersion&version=$version&branch=$branch" +
                "&category=installer&format=jar"
        )
    }

    private fun neoInstallerUrls(
        mcVersion: String,
        version: String,
        installerPath: String?
    ): List<String> {
        val legacy = mcVersion == NEO_LEGACY_MC
        val base = if (legacy) "$mcVersion-$version" else version
        val artifact = if (legacy) "forge" else "neoforge"
        return buildList {
            // 清单里给的是相对 BMCL 根的路径（/maven/net/neoforged/...），原样拼最省事也最准
            if (installerPath != null && installerPath.startsWith("/")) add("$BMCL$installerPath")
            add("$BMCL/maven/net/neoforged/$artifact/$base/$artifact-$base-installer.jar")
            add("$BMCL/neoforge/version/$base/download/installer.jar")
            add("$NEO_MAVEN/net/neoforged/$artifact/$base/$artifact-$base-installer.jar")
        }.distinct()
    }

    fun installerUrls(kind: LoaderKind, mcVersion: String, loaderVersion: String): List<String> {
        installers[installerKey(kind, mcVersion, loaderVersion)]?.let { if (it.isNotEmpty()) return it }
        return when (kind) {
            // 后台线程 / 重启后可能还没拉过清单，按坐标猜一份；猜错时 installerFile 会继续试下一条
            LoaderKind.FORGE -> forgeInstallerUrls(mcVersion, loaderVersion, "")
            LoaderKind.NEOFORGE -> neoInstallerUrls(mcVersion, loaderVersion, null)
            else -> emptyList()
        }
    }

    /** 安装器里那份 json 声明的库根；库条目缺地址时按它补。 */
    fun mavenRoot(kind: LoaderKind): String = when (kind) {
        LoaderKind.NEOFORGE -> NEO_MAVEN
        else -> FORGE_MAVEN
    }

    private fun key(kind: LoaderKind, mcVersion: String): String = "${kind.name}@$mcVersion"

    private fun installerKey(kind: LoaderKind, mcVersion: String, version: String): String =
        "${kind.name}@$mcVersion@$version"
}
