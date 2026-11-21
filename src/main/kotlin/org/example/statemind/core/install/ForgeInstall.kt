package org.example.statemind.core.install

import java.io.File
import java.io.IOException
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.example.statemind.core.MiniJson

/**
 * 用官方安装器装 Forge / NeoForge —— 不解包 GUI，只照它的 `install_profile.json` 办事。
 *
 * 安装器里那份 profile 说三件事：装哪个版本 json、下哪些库、跑哪些 processor。
 * 1.13 之后必须跑 processor（下 mojmap、拆客户端 jar、打二进补丁）；1.12.2 是 spec 0，
 * 只有一份本体要放进库目录；再往前的版本把本体直接摆在安装器根目录。
 *
 * 三条路都在这里收口，返回的 [Prepared.steps] 交给下载器在文件落盘之后执行。
 */
internal object ForgeInstall {

    class Prepared(
        /** 写进 `versions/<id>/<id>.json` 的正文。 */
        val versionText: String,
        /** 要额外下载的库（安装器的 profile 与版本 json 两处合并）。 */
        val libraries: List<Map<*, *>>,
        val steps: List<InstallStep>,
        /** 库目录下的相对路径：安装器自带、或由安装步骤写出来的，不进下载列表。 */
        val localPaths: Set<String>,
        /** 安装器说要下、但不由下载器管的零散文件（目前只有 mojmap）。 */
        val extraFiles: List<InstallFile>
    )

    /**
     * 用官方安装器装 Forge / NeoForge。
     *
     * @param vanilla 原版版本 json（用来取 mojmap 的下载地址，装配器自己那份连不上 Mojang）
     * @param vanillaJar 客户端本体在哪。共享形态下它不叫 `versions/<版本号>/<版本号>.jar`，
     *   processor 得拿到真实路径，否则装到一半报找不到客户端。
     */
    fun prepare(
        installer: File,
        root: File,
        mcVersion: String,
        targetId: String,
        requiredJava: Int?,
        mavenRoot: String,
        vanilla: Map<*, *>,
        vanillaJar: File? = null
    ): Prepared {
        ZipFile(installer).use { zip ->
            val profile = readText(zip, "install_profile.json")?.let { MiniJson.parse(it) as? Map<*, *> }
                ?: throw IOException("安装器里没有 install_profile.json")

            // 安装器自带 maven/ 目录的版本：里面那份本体就是别处下不到的（1.12.2 的
            // 版本 json 把它写成空 url）。直接铺进库目录，省一次下载也避开空地址。
            val local = copyMaven(zip, File(root, "libraries"))

            return if (profile.containsKey("json")) {
                modern(
                    zip, profile, installer, root, mcVersion, targetId, requiredJava,
                    mavenRoot, vanilla, local, vanillaJar
                )
            } else {
                legacy(zip, profile, root, mcVersion, targetId, mavenRoot, local)
            }
        }
    }

    // ---------- 1.12.2 及之后：带 version.json ----------

    private fun modern(
        zip: ZipFile,
        profile: Map<*, *>,
        installer: File,
        root: File,
        mcVersion: String,
        targetId: String,
        requiredJava: Int?,
        mavenRoot: String,
        vanilla: Map<*, *>,
        local: Set<String>,
        vanillaJar: File?
    ): Prepared {
        val jsonPath = (profile["json"] as? String)?.trimStart('/')
            ?: throw IOException("安装器没写版本 json 的路径")
        val version = readText(zip, jsonPath)?.let { MiniJson.parse(it) as? Map<*, *> }
            ?: throw IOException("安装器里没有 $jsonPath")

        val profileLibs = (profile["libraries"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val versionLibs = (version["libraries"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

        // profile 里那份库表也得进版本 json：Forge 本体（universal jar）只写在 profile 里，
        // 少了它启动时 classpath 里根本没有 fml 那些东西
        val versionText = retarget(version, mcVersion, targetId, mergeLibraries(versionLibs, profileLibs))

        val data = profile["data"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val mojmap = Mojmap.of(data, vanilla)
        // profile 里声明了官方映射、但我们没拿到下载地址：这时不能放行 DOWNLOAD_MOJMAPS
        // 那个 processor —— 它会去连 Mojang 官方服务器，国内基本连不上，
        // 表现出来就是装到一半卡住然后失败。宁可现在就报错。
        if (mojmap == null && hasMojmapData(data)) {
            throw IOException("这个版本需要官方映射文件，但没找到下载地址")
        }

        val processors = (profile["processors"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?.filter(::clientSide)
            // mojmap 我们自己下（官方那份地址在国内连不上），这个 processor 一律别跑
            ?.filterNot { it.isMojmapTask() }
            ?: emptyList()

        val steps = ArrayList<InstallStep>()
        if (processors.isNotEmpty()) {
            steps += ProcessorStep(
                processors = processors,
                data = data,
                installer = installer,
                root = root,
                libDir = File(root, "libraries"),
                mcJar = vanillaJar ?: File(root, "versions/$mcVersion/$mcVersion.jar"),
                mcVersion = mcVersion,
                requiredJava = requiredJava
            )
        }
        // 1.17 之后的客户端本体是 processor 打出来的，官方装法是放到版本目录里当本体用；
        // 放在库目录里启动器找不到（它按 versions/<id>/<id>.jar 找客户端）。
        val patched = data["PATCHED"] as? Map<*, *>
        val patchedCoords = (patched?.get("client") as? String)?.trim('[', ']')
        val patchedRel = patchedCoords?.let { MavenPath.of(it) }
        if (patchedRel != null) {
            steps += PlaceClientJarStep(
                source = File(root, "libraries/$patchedRel"),
                target = File(root, "versions/$targetId/$targetId.jar")
            )
        }

        val libraries = normalize(mergeLibraries(versionLibs, profileLibs), mavenRoot)
        val extra = mojmap?.let { listOf(InstallFile(it.path, it.url, it.size, it.sha1)) } ?: emptyList()
        val localPaths = local + (patchedRel?.let { setOf(it) } ?: emptySet())
        return Prepared(versionText, libraries, steps, localPaths, extra)
    }

    // ---------- 更老的版本：install + versionInfo ----------

    private fun legacy(
        zip: ZipFile,
        profile: Map<*, *>,
        root: File,
        mcVersion: String,
        targetId: String,
        mavenRoot: String,
        local: Set<String>
    ): Prepared {
        val install = profile["install"] as? Map<*, *>
            ?: throw IOException("安装器格式无法识别")
        val info = profile["versionInfo"] as? Map<*, *>
            ?: throw IOException("安装器里没有 versionInfo")
        val filePath = (install["filePath"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IOException("安装器没写本体文件名")

        // 通用包直接摆在安装器根目录（不是 maven/ 下），按版本 json 里那条库记录的路径铺到库目录
        val libs = (info["libraries"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val fromInstaller = HashSet(local)
        val target = install["path"] as? String
        val rel = libs.firstOrNull { it["name"] == target }
            ?.let { artifactPath(it) }
            ?: target?.let { MavenPath.of(it) }
        if (rel != null) {
            val dest = File(root, "libraries/$rel")
            if (!dest.isFile) {
                copyEntry(zip, filePath, dest)
                fromInstaller += rel
            }
        }

        return Prepared(
            retarget(info, mcVersion, targetId, emptyList()),
            normalize(libs, mavenRoot),
            emptyList(),
            fromInstaller,
            emptyList()
        )
    }
}

/**
 * mojmap（官方 obf ↔ 官方名 的映射表）由一个专门的 processor 去 Mojang 官方服务器下，
 * 那个地址在国内基本连不上。改成从原版版本 json 的 `client_mappings` 自己下。
 */
private class Mojmap(val path: String, val url: String, val size: Long, val sha1: String?) {
    companion object {
        fun of(data: Map<*, *>, vanilla: Map<*, *>): Mojmap? {
            val raw = (data["MOJMAPS"] as? Map<*, *>)?.get("client") as? String ?: return null
            if (!raw.startsWith("[") || !raw.endsWith("]")) return null
            val path = MavenPath.of(raw.substring(1, raw.length - 1)) ?: return null
            val download = (vanilla["downloads"] as? Map<*, *>)?.get("client_mappings") as? Map<*, *> ?: return null
            val url = (download["url"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val size = (download["size"] as? Number)?.toLong() ?: 0L
            return Mojmap(path, url, size, (download["sha1"] as? String)?.takeIf { it.isNotBlank() })
        }
    }
}

/** 换掉 id；`inheritsFrom` 缺了按这次要装的游戏版本补上；profile 的库表并进版本 json。 */
private fun retarget(
    version: Map<*, *>,
    mcVersion: String,
    targetId: String,
    extraLibraries: List<Map<*, *>>
): String {
    val copy = LinkedHashMap<String, Any?>()
    for ((key, value) in version) {
        val name = key as? String ?: continue
        copy[name] = value
    }
    copy["id"] = targetId
    if ((copy["inheritsFrom"] as? String).isNullOrBlank()) copy["inheritsFrom"] = mcVersion
    if (extraLibraries.isNotEmpty()) {
        val base = (copy["libraries"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        copy["libraries"] = mergeLibraries(base, extraLibraries)
    }
    return MiniJson.write(copy)
}

/** 按 name 去重合并两份库表，版本 json 里原有的排前面。 */
private fun mergeLibraries(base: List<Map<*, *>>, extra: List<Map<*, *>>): List<Map<*, *>> {
    val out = ArrayList<Map<*, *>>(base.size + extra.size)
    val seen = HashSet<String>()
    for (lib in base + extra) {
        val name = lib["name"] as? String ?: continue
        if (seen.add(name)) out += lib
    }
    return out
}

/**
 * 把每条库补成「有地址」的样子。
 *
 * Forge 的版本 json 里本体那条写的是 `"url": ""`，profile 里也有只给坐标的库；
 * 下载器只认非空地址，不补的话这些文件会被当成「没有下载源」跳过，处理器随即报缺件。
 */
private fun normalize(libraries: List<Map<*, *>>, mavenRoot: String): List<Map<*, *>> =
    libraries.map { lib ->
        val out = LinkedHashMap<Any?, Any?>(lib)
        val downloads = lib["downloads"] as? Map<*, *>
        val artifact = downloads?.get("artifact") as? Map<*, *>
        // 只有「本来就有本体」或「压根没写下载信息」的库才补地址：natives 那类只声明
        // classifiers 的条目没有本体 jar，硬补一条会下 404
        val path = artifactPath(lib) ?: (lib["name"] as? String)?.takeIf { downloads == null }?.let { MavenPath.of(it) }
        if (path != null) {
            val url = (artifact?.get("url") as? String)?.takeIf { it.isNotBlank() }
                ?: (lib["url"] as? String)?.takeIf { it.isNotBlank() }
                ?: "${mavenRoot.trimEnd('/')}/$path"
            val merged = LinkedHashMap<Any?, Any?>(artifact ?: emptyMap<Any?, Any?>())
            merged["path"] = path
            merged["url"] = url
            val downloadMap = LinkedHashMap<Any?, Any?>(downloads ?: emptyMap<Any?, Any?>())
            downloadMap["artifact"] = merged
            out["downloads"] = downloadMap
        }
        out
    }

/** 库里那条记录的落盘路径（相对库目录）。 */
private fun artifactPath(lib: Map<*, *>): String? {
    val artifact = (lib["downloads"] as? Map<*, *>)?.get("artifact") as? Map<*, *>
    return (artifact?.get("path") as? String)?.takeIf { it.isNotBlank() }
}

/** 安装器里 `maven/` 下带着的库，原样铺进 `libraries/`。 */
private fun copyMaven(zip: ZipFile, libDir: File): Set<String> {
    val copied = HashSet<String>()
    for (entry in zip.entries()) {
        val name = entry.name
        if (entry.isDirectory || !name.startsWith("maven/")) continue
        val rel = name.removePrefix("maven/")
        if (rel.isBlank()) continue
        val dest = File(libDir, rel)
        if (!dest.isFile) copyEntry(zip, name, dest)
        copied += rel
    }
    return copied
}

private fun copyEntry(zip: ZipFile, name: String, dest: File) {
    val entry = zip.getEntry(name.trimStart('/')) ?: throw IOException("安装器里没有 $name")
    dest.parentFile?.mkdirs()
    zip.getInputStream(entry).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
}

private fun readText(zip: ZipFile, name: String): String? {
    val entry = zip.getEntry(name.trimStart('/')) ?: return null
    return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
}

private fun clientSide(processor: Map<*, *>): Boolean {
    val sides = processor["sides"] as? List<*> ?: return true
    return sides.any { (it as? String) == "client" }
}

/** processor 的参数里 `--task DOWNLOAD_MOJMAPS` 那个就是下 mojmap 的。 */
private fun Map<*, *>.isMojmapTask(): Boolean {
    val args = this["args"] as? List<*> ?: return false
    val values = args.filterIsInstance<String>()
    for (index in values.indices) {
        if (values[index] != "--task") continue
        if (values.getOrNull(index + 1) == "DOWNLOAD_MOJMAPS") return true
    }
    return false
}

/** profile 的 data 里是不是声明了官方映射（client 那一侧）。 */
private fun hasMojmapData(data: Map<*, *>): Boolean {
    val entry = data["MOJMAPS"] as? Map<*, *> ?: return false
    return !(entry["client"] as? String).isNullOrBlank()
}

/**
 * 1.17 之后的客户端本体由 processor 打出来，落在库目录里。
 * 启动器按 `versions/<id>/<id>.jar` 找客户端本体，所以要把它挪到版本目录。
 */
private class PlaceClientJarStep(
    private val source: File,
    private val target: File
) : InstallStep {

    override val title = "整理客户端本体"

    override fun run(report: (String) -> Unit, cancelled: () -> Boolean) {
        if (!source.isFile) throw IOException("安装程序没有产出客户端本体")
        if (cancelled()) throw InstallCancelled()
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        report(title)
    }
}

/**
 * 一个 processor 就是一段 `java -cp ... 主类 参数`。参数里的占位符有三种写法：
 * `{KEY}` 取 data 里的值、`[坐标]` 取库里那个文件的路径、`'字面量'` 原样保留。
 */
private class ProcessorStep(
    private val processors: List<Map<*, *>>,
    private val data: Map<*, *>,
    private val installer: File,
    private val root: File,
    private val libDir: File,
    private val mcJar: File,
    private val mcVersion: String,
    private val requiredJava: Int?
) : InstallStep {

    override val title = "运行安装程序"

    override fun run(report: (String) -> Unit, cancelled: () -> Boolean) {
        val temp = File.createTempFile("statemind-install", "").apply {
            delete()
            mkdirs()
        }
        try {
            if (!mcJar.isFile) throw IOException("缺少原版客户端 ${mcJar.name}")
            // processor 只读原版 jar，但版本之间写法不一；复制一份进临时目录，
            // 万一哪个版本就地改文件，坏掉的是副本而不是用户已经装好的原版
            val isolated = File(temp, "minecraft.jar")
            mcJar.copyTo(isolated, overwrite = true)

            val vars = HashMap<String, String>()
            for ((key, value) in data) {
                val name = key as? String ?: continue
                val raw = (value as? Map<*, *>)?.get("client") as? String ?: continue
                vars[name] = dataValue(raw, temp, vars)
            }
            vars["SIDE"] = "client"
            vars["MINECRAFT_JAR"] = isolated.absolutePath
            vars["MINECRAFT_VERSION"] = mcVersion
            vars["ROOT"] = root.absolutePath
            vars["INSTALLER"] = installer.absolutePath
            vars["LIBRARY_DIR"] = libDir.absolutePath

            val java = InstallJava.exe(requiredJava)
            processors.forEachIndexed { index, processor ->
                if (cancelled()) throw InstallCancelled()
                report("$title（${index + 1}/${processors.size}）")
                runProcessor(java, processor, vars, cancelled)
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    /** data 里的一条：库坐标 / 字面量 / 安装器内的文件（要解到临时目录）。 */
    private fun dataValue(raw: String, temp: File, vars: Map<String, String>): String {
        val text = raw.trim()
        // 服务端专用条目在 client 那边是空串，不是路径，别拿去安装器里找文件
        if (text.isEmpty()) return ""
        if (text.startsWith("'") && text.endsWith("'")) return text.substring(1, text.length - 1)
        if (text.startsWith("[") && text.endsWith("]")) {
            val rel = MavenPath.of(text.substring(1, text.length - 1))
                ?: throw IOException("装不出库路径：$text")
            return File(libDir, rel).absolutePath
        }
        // 引用别的 data 项
        if (text.startsWith("{") && text.endsWith("}")) {
            val key = text.substring(1, text.length - 1)
            return vars[key] ?: throw IOException("安装配置引用了未知的项 $key")
        }
        // 安装器里的相对路径（`/data/client.lzma`）：processor 读不到 jar 内部，只能先落盘
        val name = text.trimStart('/')
        val dest = File(temp, name.replace('/', '_'))
        if (!dest.isFile) ZipFile(installer).use { zip -> copyEntry(zip, name, dest) }
        return dest.absolutePath
    }

    private fun runProcessor(java: String, processor: Map<*, *>, vars: Map<String, String>, cancelled: () -> Boolean) {
        val jarCoords = processor["jar"] as? String ?: throw IOException("安装步骤没写处理器")
        val jarRel = MavenPath.of(jarCoords) ?: throw IOException("处理器坐标不对：$jarCoords")
        val jar = File(libDir, jarRel)
        if (!jar.isFile) throw IOException("缺少处理器 $jarCoords，请重试下载")

        val classpath = ((processor["classpath"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
            .mapNotNull { coords ->
                val rel = MavenPath.of(coords) ?: return@mapNotNull null
                File(libDir, rel)
            }
        classpath.firstOrNull { !it.isFile }?.let {
            throw IOException("缺少安装器依赖 ${it.name}，请重试下载")
        }
        val mainClass = JarFile(jar).use { it.manifest?.mainAttributes?.getValue("Main-Class") }
        if (mainClass.isNullOrBlank()) throw IOException("${jar.name} 里没有主类")

        val args = ((processor["args"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
            .map { literal(it, vars) ?: throw IOException("安装配置解析失败：$it") }
        val command = ArrayList<String>(classpath.size + args.size + 5)
        command += java
        command += "-cp"
        command += (classpath + jar).joinToString(File.pathSeparator) { it.absolutePath }
        command += mainClass
        command += args

        val process = ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val tail = ArrayDeque<String>()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(tail) {
                        tail.addLast(line)
                        if (tail.size > 12) tail.removeFirst()
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
        val code = process.exitValue()
        if (code != 0) {
            val reason = synchronized(tail) { tail.lastOrNull() }.orEmpty()
            throw IOException("${jar.name} 退出码 $code" + if (reason.isBlank()) "" else "：$reason")
        }
        checkOutputs(processor, vars)
    }

    /**
     * processor 声明了产物（`outputs`）就必须真的产出。
     *
     * 有几版 processor 遇到已有文件会直接跳过、什么都不做，退出码还是 0；不核对产物的话，
     * 任务页会显示「安装完成」，等到启动时才报缺件。
     */
    private fun checkOutputs(processor: Map<*, *>, vars: Map<String, String>) {
        val outputs = processor["outputs"] as? Map<*, *> ?: return
        for ((key, _) in outputs) {
            val raw = key as? String ?: continue
            val path = literal(raw, vars) ?: continue
            if (!File(path).isFile) {
                throw IOException("安装程序没有产出 ${File(path).name}")
            }
        }
    }

    private fun literal(arg: String, vars: Map<String, String>): String? {
        if (arg.startsWith("[") && arg.endsWith("]")) {
            val rel = MavenPath.of(arg.substring(1, arg.length - 1)) ?: return null
            return File(libDir, rel).absolutePath
        }
        if (arg.startsWith("'") && arg.endsWith("'")) return arg.substring(1, arg.length - 1)
        return replaceTokens(arg, vars)
    }

    private fun replaceTokens(text: String, vars: Map<String, String>): String? {
        val out = StringBuilder(text.length + 32)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c == '{') {
                val end = text.indexOf('}', index + 1)
                if (end < 0) return null
                val key = text.substring(index + 1, end)
                out.append(vars[key] ?: return null)
                index = end + 1
            } else {
                out.append(c)
                index++
            }
        }
        return out.toString()
    }
}
