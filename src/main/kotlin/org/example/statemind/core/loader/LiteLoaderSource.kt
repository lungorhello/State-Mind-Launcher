package org.example.statemind.core.loader

import java.io.IOException
import org.example.statemind.core.HttpText
import org.example.statemind.core.MiniJson
import org.example.statemind.core.auth.Http

/**
 * LiteLoader 的版本清单与安装。
 *
 * 它跟 Fabric / Quilt 不一样：没有「一份现成的 profile json」可以照抄。
 * 官方仓库 repo.mumfrey.com 已经下线（直接 404），官方 dl 站点会把请求 302 到那个死地址，
 * 所以 jar 只能走 BMCLAPI 的 maven 镜像。装法是**自己拼一份版本 json**：
 * `inheritsFrom` 指回原版、`mainClass` 用原版的 launchwrapper、启动参数尾巴加 `--tweakClass`。
 *
 * 只支持到 1.12.2 —— LiteLoader 本身就没再往后做。
 */
object LiteLoaderSource {

    /** 元数据：每个游戏版本一条，带 tweakClass 与它依赖的库。 */
    private const val LIST_URL = "https://bmclapi2.bangbang93.com/liteloader/list"

    private const val MAVEN = "https://bmclapi2.bangbang93.com/maven/"

    /** 只有 release 的目录名是游戏版本号，snapshot 是「版本-SNAPSHOT」；file 也按这个分。 */
    data class Build(
        val mc: String,
        /** maven 目录名，也是坐标里的版本。 */
        val dir: String,
        val file: String,
        /** 界面上显示的版本号。 */
        val display: String,
        val tweakClass: String,
        val libraries: List<Map<*, *>>
    ) {
        val path: String get() = "com/mumfrey/liteloader/$dir/$file"
    }

    @Volatile
    private var cached: List<Build>? = null

    @Synchronized
    private fun all(): List<Build> {
        cached?.let { return it }
        val body = get(LIST_URL)
        val parsed = MiniJson.parse(body) as? List<*> ?: emptyList<Any?>()
        val list = parsed.mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val mc = (entry["mcversion"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val build = entry["build"] as? Map<*, *> ?: return@mapNotNull null
            val file = (build["file"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val version = (build["version"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val tweak = (build["tweakClass"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val release = (build["stream"] as? String)?.uppercase() == "RELEASE"
            val libs = (build["libraries"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
            Build(
                mc = mc,
                dir = if (release) mc else version,
                file = file,
                display = if (release) mc else version,
                tweakClass = tweak,
                libraries = libs
            )
        }
        cached = list
        return list
    }

    /** 这个游戏版本有没有 LiteLoader；没有就是空表。 */
    fun versions(mcVersion: String): List<LoaderVersion> =
        all().filter { it.mc == mcVersion }.map { LoaderVersion(it.display, true) }

    fun build(mcVersion: String, display: String): Build? =
        all().firstOrNull { it.mc == mcVersion && it.display == display }

    /**
     * 拼出 `versions/<targetId>/<targetId>.json` 的正文。
     *
     * [parentArgs] 是原版 json 的 `minecraftArguments` —— 子层的同名字段会整条覆盖父层，
     * 所以必须把原版那串原样带上，只在末尾加 tweak。1.13 之后改用 `arguments` 数组，
     * 但 LiteLoader 到 1.12.2 为止，碰不到那种情况。
     */
    fun versionJson(mcVersion: String, display: String, targetId: String, parentArgs: String?): String {
        val build = build(mcVersion, display)
            ?: throw IOException("这个版本没有 LiteLoader 构建：$mcVersion")
        val args = (parentArgs ?: "").trimEnd()
        val game = if (args.isEmpty()) "--tweakClass ${build.tweakClass}"
        else "$args --tweakClass ${build.tweakClass}"

        val libs = ArrayList<String>(build.libraries.size + 1)
        // 这个 jar 的文件名不总等于「artifactId-版本号」，所以路径写死，不交给坐标去推
        libs += "{\"name\":\"com.mumfrey:liteloader:${build.dir}\"," +
                "\"downloads\":{\"artifact\":{\"path\":\"${build.path}\"," +
                "\"url\":\"$MAVEN${build.path}\"}}}"
        for (lib in build.libraries) {
            val name = (lib["name"] as? String)?.takeIf { it.isNotBlank() } ?: continue
            val url = repoOf(name)
            libs += if (url == null) "{\"name\":\"$name\"}" else "{\"name\":\"$name\",\"url\":\"$url\"}"
        }

        return buildString {
            append("{\n")
            append("  \"id\": \"${escape(targetId)}\",\n")
            append("  \"inheritsFrom\": \"${escape(mcVersion)}\",\n")
            append("  \"type\": \"release\",\n")
            append("  \"mainClass\": \"net.minecraft.launchwrapper.Launch\",\n")
            append("  \"minecraftArguments\": \"${escape(game)}\",\n")
            append("  \"libraries\": [\n    ")
            append(libs.joinToString(",\n    "))
            append("\n  ]\n}\n")
        }
    }

    /**
     * 这两家不在 Maven Central：launchwrapper 只在 Mojang 的库站，asm 在中央仓库。
     * 其余的按坐标推，推不出来就交给 Maven Central 兜底。
     */
    private fun repoOf(name: String): String? = when {
        name.startsWith("net.minecraft:launchwrapper") -> "https://libraries.minecraft.net/"
        name.startsWith("org.ow2.asm") -> "https://repo1.maven.org/maven2/"
        else -> null
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun get(url: String): String {
        val response = Http.get(url, 20)
        if (response.status !in 200..299) throw IOException(HttpText.of(response.status))
        return response.body
    }
}
