package org.example.statemind.core.install

import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 装 OptiFine。
 *
 * 它的 jar 自带一个补丁器（`optifine.Patcher`）：把原版客户端里的类改名、塞进 OptiFine 的
 * 实现，产出一份新的库 jar。启动时走 launchwrapper + `optifine.OptiFineTweaker`，
 * 所以主类要换掉、还要额外挂一份 launchwrapper。
 *
 * 只做「客户端补丁」这一条路：官方安装器的图形界面与广告页不碰。
 */
internal object OptiFineInstall {

    private const val TWEAKER = "optifine.OptiFineTweaker"

    class Prepared(
        val versionText: String,
        /** 版本 json 里要出现的库；其中 [localPaths] 那几条由补丁步骤自己产出。 */
        val libraries: List<Map<*, *>>,
        val steps: List<InstallStep>,
        /** 库目录下的相对路径，补丁器会自己写出来，别当成下载项去 404。 */
        val localPaths: Set<String>
    )

    fun prepare(
        installer: File,
        root: File,
        mcVersion: String,
        loaderVersion: String,
        targetId: String,
        vanilla: Map<*, *>,
        requiredJava: Int?,
        /** 客户端本体在哪：共享形态下它不在 `versions/<版本号>/<版本号>.jar`。 */
        vanillaJar: File? = null
    ): Prepared {
        val mavenVersion = "${mcVersion}_$loaderVersion"
        val outRel = "optifine/OptiFine/$mavenVersion/OptiFine-$mavenVersion.jar"

        val hasPatcher = ZipFile(installer).use { it.getEntry("optifine/Patcher.class") != null }
        val wrapper = launchWrapper(installer)

        val libraries = ArrayList<Map<*, *>>()
        val wrapperJson = if (wrapper != null) {
            """{"name":"${wrapper.coords}","downloads":{"artifact":{"path":"${wrapper.rel}"}}}"""
        } else {
            val rel = "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
            libraries += mapOf(
                "name" to "net.minecraft:launchwrapper:1.12",
                "downloads" to mapOf(
                    "artifact" to mapOf(
                        "path" to rel,
                        "url" to "https://libraries.minecraft.net/$rel"
                    )
                )
            )
            """{"name":"net.minecraft:launchwrapper:1.12","downloads":{"artifact":{"path":"$rel","url":"https://libraries.minecraft.net/$rel"}}}"""
        }

        val optifineJson =
            """{"name":"optifine:OptiFine:$mavenVersion","downloads":{"artifact":{"path":"$outRel"}}}"""
        val libsJson = listOf(optifineJson, wrapperJson)

        val parentArgs = vanilla["minecraftArguments"] as? String
        val text = if (parentArgs != null) {
            val args = (parentArgs.trimEnd() + " --tweakClass $TWEAKER").trim()
            versionText(targetId, mcVersion, "\"minecraftArguments\": \"${escape(args)}\",", libsJson)
        } else {
            versionText(
                targetId, mcVersion,
                "\"arguments\": {\"game\": [\"--tweakClass\", \"$TWEAKER\"]},",
                libsJson
            )
        }

        val step = PatchStep(
            installer = installer,
            mcJar = vanillaJar ?: File(root, "versions/$mcVersion/$mcVersion.jar"),
            outJar = File(root, "libraries/$outRel"),
            hasPatcher = hasPatcher,
            wrapperEntry = wrapper?.entry,
            wrapperJar = wrapper?.let { File(root, "libraries/${it.rel}") },
            requiredJava = requiredJava
        )
        val localPaths = buildSet {
            add(outRel)
            wrapper?.let { add(it.rel) }
        }
        return Prepared(text, libraries, listOf(step), localPaths)
    }

    // ---------- launchwrapper ----------

    private class Wrapper(
        val coords: String,
        val rel: String,
        /** 安装器里的条目名。 */
        val entry: String
    )

    /**
     * OptiFine 自带改过的 launchwrapper（`launchwrapper-of-x.y.jar`），优先用它 ——
     * 原版 1.12 的 launchwrapper 跑不动新版本 OptiFine 的类。
     */
    private fun launchWrapper(installer: File): Wrapper? = ZipFile(installer).use { zip ->
        if (zip.getEntry("launchwrapper-2.0.jar") != null) {
            return@use Wrapper(
                coords = "optifine:launchwrapper:2.0",
                rel = "optifine/launchwrapper/2.0/launchwrapper-2.0.jar",
                entry = "launchwrapper-2.0.jar"
            )
        }
        val versionEntry = zip.getEntry("launchwrapper-of.txt") ?: return@use null
        val version = zip.getInputStream(versionEntry).use { it.readBytes().toString(Charsets.UTF_8) }.trim()
        val entry = "launchwrapper-of-$version.jar"
        if (version.isBlank() || zip.getEntry(entry) == null) return@use null
        Wrapper(
            coords = "optifine:launchwrapper-of:$version",
            rel = "optifine/launchwrapper-of/$version/launchwrapper-of-$version.jar",
            entry = entry
        )
    }

    // ---------- 版本 json ----------

    private fun versionText(targetId: String, mcVersion: String, argsField: String, libraries: List<String>): String =
        buildString {
            append("{\n")
            append("  \"id\": \"${escape(targetId)}\",\n")
            append("  \"inheritsFrom\": \"${escape(mcVersion)}\",\n")
            append("  \"type\": \"release\",\n")
            append("  \"mainClass\": \"net.minecraft.launchwrapper.Launch\",\n")
            append("  $argsField\n")
            append("  \"libraries\": [\n    ")
            append(libraries.joinToString(",\n    "))
            append("\n  ]\n}\n")
        }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    // ---------- 补丁步骤 ----------

    private class PatchStep(
        private val installer: File,
        private val mcJar: File,
        private val outJar: File,
        private val hasPatcher: Boolean,
        private val wrapperEntry: String?,
        private val wrapperJar: File?,
        private val requiredJava: Int?
    ) : InstallStep {

        override val title = "应用 OptiFine 补丁"

        override fun run(report: (String) -> Unit, cancelled: () -> Boolean) {
            if (!mcJar.isFile) throw IOException("缺少原版客户端 ${mcJar.name}")
            outJar.parentFile?.mkdirs()
            report("$title…")

            if (hasPatcher) {
                val command = listOf(
                    InstallJava.exe(requiredJava), "-cp", installer.absolutePath,
                    "optifine.Patcher", mcJar.absolutePath, installer.absolutePath, outJar.absolutePath
                )
                val process = ProcessBuilder(command)
                    .directory(outJar.parentFile)
                    .redirectErrorStream(true)
                    .start()
                // 补丁器的输出不写给用户看，只留最后几行做失败说明
                val tail = ArrayDeque<String>()
                val reader = Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(tail) {
                                tail.addLast(line)
                                if (tail.size > 8) tail.removeFirst()
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                while (process.isAlive) {
                    if (cancelled()) {
                        process.destroy()
                        throw InstallCancelled()
                    }
                    Thread.sleep(150)
                }
                reader.join(1000)
                if (process.exitValue() != 0) {
                    val reason = synchronized(tail) { tail.lastOrNull() }.orEmpty()
                    throw IOException("OptiFine 补丁器退出码 ${process.exitValue()}" +
                            if (reason.isBlank()) "" else "：$reason")
                }
            } else {
                installer.copyTo(outJar, overwrite = true)
            }

            stripModsToml(outJar)

            if (wrapperEntry != null && wrapperJar != null) {
                wrapperJar.parentFile?.mkdirs()
                ZipFile(installer).use { zip ->
                    val entry = zip.getEntry(wrapperEntry) ?: throw IOException("安装器里没有 $wrapperEntry")
                    zip.getInputStream(entry).use { input ->
                        wrapperJar.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        /**
         * 去掉 `META-INF/mods.toml`：这份 jar 是当 launchwrapper 的库挂上去的，
         * 留着这个文件，装了 Forge 的实例会再把它当模组加载一遍。
         */
        private fun stripModsToml(jar: File) {
            if (!jar.isFile) return
            runCatching {
                FileSystems.newFileSystem(jar.toPath(), emptyMap<String, Any>()).use { fs ->
                    Files.deleteIfExists(fs.getPath("/META-INF/mods.toml"))
                }
            }
        }
    }
}
