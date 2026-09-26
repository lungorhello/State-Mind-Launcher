package org.example.statemind.core

import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 启动游戏：读版本 json（含 inheritsFrom 继承合并）→ 拼 classpath → 提 natives → 填参数 → 起 java 进程。
 */
object LaunchUtil {

    data class Config(
        val version: String,        // versions 下的文件夹名，如 mtrformango
        val javaHome: String,       // Java 目录（BackendUtil.JavaInfo.path），或直接给 java.exe 路径
        val playerName: String,     // 玩家名：离线 = 账号名，第三方 = 皮肤站上的角色名
        val maxMemoryMb: Int = 4096,
        /** 角色 UUID（第三方账号用皮肤站给的那个）；null 表示按离线算法算。 */
        val uuid: String? = null,
        /** 访问令牌：离线填 "0"，第三方填皮肤站发的 accessToken。 */
        val accessToken: String = "0",
        /** 离线 legacy；外置登录按官方模板填 mojang。 */
        val userType: String = "legacy",
        /** 额外的 JVM 参数（外置登录的 `-javaagent` 三件套），会插在主类之前。 */
        val extraJvmArgs: List<String> = emptyList(),
        /** `${user_properties}` 占位符的值，没有就空对象。 */
        val userProperties: String = "{}"
    )

    data class Plan(
        val command: List<String>,
        val workDir: File,
        val classpathCount: Int = 0,
        val missingLibs: List<String> = emptyList()
    )

    /** Windows 走 CreateProcessW，命令行上限 32767 字符，这里留点余量。 */
    private const val CMD_LIMIT = 32000

    private val osName: String = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") || it.contains("darwin") -> "osx"
            else -> "linux"
        }
    }

    fun launch(cfg: Config, onProgress: (String) -> Unit = {}): Process {
        val plan = plan(cfg, onProgress)
        onProgress("启动中…")
        val pb = ProcessBuilder(plan.command)
        pb.directory(plan.workDir)
        pb.redirectErrorStream(true)
        return pb.start()
    }

    fun plan(cfg: Config, onProgress: (String) -> Unit = {}): Plan {
        val mcDir = BackendUtil.minecraftDir
        GameDir.ensure(mcDir)   // 首次使用生成骨架，缺项补齐
        val versionDir = File(mcDir, "versions/${cfg.version}")
        val jsonFile = File(versionDir, "${cfg.version}.json")
        require(jsonFile.isFile) { "找不到版本文件：${jsonFile.absolutePath}" }

        onProgress("读取版本信息…")
        val loaded = loadVersion(mcDir, cfg.version)
        val root = loaded.flat
        if (loaded.parents.isNotEmpty()) {
            onProgress("已合并父版本 ${loaded.parents.joinToString(" → ")}")
        }

        // 默认开启版本隔离：游戏目录指到 versions/<版本>，各版本 mods/saves 独立互不干扰
        GameDir.ensureVersion(versionDir)
        val gameDir = versionDir

        val assetsIndex = when (val a = root["assets"]) {
            is String -> a
            is Map<*, *> -> (a["id"] as? String) ?: "legacy"
            else -> "legacy"
        }
        if (!File(mcDir, "assets/indexes/$assetsIndex.json").isFile) {
            onProgress("提示：缺少资源索引 $assetsIndex.json，游戏资源可能加载不全")
        }

        onProgress("收集依赖库…")
        val libDir = File(mcDir, "libraries")
        val libraries = (root["libraries"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

        val jars = ArrayList<String>(libraries.size + 1)
        val missing = ArrayList<String>()
        for (lib in libraries) {
            if (!rulesAllow(lib["rules"])) continue
            val rel = libraryPath(lib) ?: continue
            val jar = File(libDir, rel)
            if (jar.isFile) jars.add(jar.absolutePath) else missing.add(rel)
        }
        val classpath = buildString {
            if (loaded.clientJar != null) append(loaded.clientJar.absolutePath)
            for (j in jars) {
                if (isNotEmpty()) append(File.pathSeparator)
                append(j)
            }
        }
        if (missing.isNotEmpty()) {
            onProgress("提示：${missing.size} 个依赖库不在本地，可能启动失败")
        }

        onProgress("提取本地库…")
        val nativesDir = File(versionDir, "${cfg.version}-natives")
        val extracted = extractNatives(libraries, libDir, nativesDir)

        val vars = mapOf(
            "natives_directory" to nativesDir.absolutePath,
            "classpath" to classpath,
            "launcher_name" to "StateMindLauncher",
            "launcher_version" to "0.1",
            "auth_player_name" to cfg.playerName,
            "version_name" to cfg.version,
            "game_directory" to gameDir.absolutePath,
            "assets_root" to File(mcDir, "assets").absolutePath,
            "assets_index_name" to assetsIndex,
            // 官方模板里 UUID 不带横线；第三方用皮肤站给的角色 UUID，离线按名字算
            "auth_uuid" to (cfg.uuid?.replace("-", "") ?: offlineUuid(cfg.playerName)),
            "auth_access_token" to cfg.accessToken,
            "auth_session" to cfg.accessToken,
            "clientid" to "",
            "auth_xuid" to "",
            "user_type" to cfg.userType,
            "user_properties" to cfg.userProperties,
            "version_type" to (root["type"] as? String ?: "release")
        )

        val mainClass = root["mainClass"] as? String
            ?: throw IllegalStateException("版本文件里没有 mainClass")

        val jvmArgs = collectArgs(root, "jvm", vars).toMutableList()
        if (jvmArgs.none { it == "-cp" || it == "-classpath" } && classpath.isNotEmpty()) {
            // 版本 json 没自己带 classpath（老版本 json、部分加载器），补到最前面。
            // classpath 为空时不补：补一条空的 -cp 会盖掉 JVM 默认值，反而更糟。
            jvmArgs.add(0, "-cp")
            jvmArgs.add(1, classpath)
        }
        // java.library.path 只有 json 没写时才补，避免和 json 里那条重复
        if (jvmArgs.none { it.startsWith("-Djava.library.path=") }) {
            jvmArgs.add(0, "-Djava.library.path=${nativesDir.absolutePath}")
        }

        val cmd = buildList {
            add(resolveJavaExe(cfg.javaHome))
            add("-Xmx${cfg.maxMemoryMb}M")
            add("-Dfile.encoding=UTF-8")
            addAll(cfg.extraJvmArgs)   // 外置登录的 -javaagent 等，必须在主类之前
            addAll(jvmArgs)
            add(mainClass)
            addAll(collectArgs(root, "game", vars))
        }

        val cmdLength = cmd.sumOf { it.length + 1 }
        if (cmdLength > CMD_LIMIT) {
            onProgress("警告：命令行长度 $cmdLength 超过系统上限 $CMD_LIMIT，可能启动失败")
        }

        onProgress("依赖就绪（${jars.size} 个库 / $extracted 个本地文件）")
        return Plan(cmd, gameDir, jars.size, missing)
    }

    // ---------- 版本 json（含继承合并） ----------

    private class Loaded(
        val flat: Map<String, Any?>,
        val clientJar: File?,
        val parents: List<String>
    )

    /** 读版本 json；遇到 inheritsFrom 就把父版本合并进来，子层优先（Forge / OptiFine 都靠这个）。 */
    private fun loadVersion(mcDir: File, id: String): Loaded {
        val chain = ArrayList<Map<*, *>>()      // 子 → 父
        val parents = ArrayList<String>()
        var clientJar: File? = null
        var cur = id
        var guard = 0
        while (true) {
            if (guard++ > 8) throw IllegalStateException("版本继承层数过多：$id")
            val f = File(mcDir, "versions/$cur/$cur.json")
            require(f.isFile) { "找不到版本文件：${f.absolutePath}" }
            if (clientJar == null) {
                File(mcDir, "versions/$cur/$cur.jar").takeIf { it.isFile }?.let { clientJar = it }
            }
            val m = MiniJson.parse(f.readText()) as? Map<*, *>
                ?: throw IllegalStateException("版本文件格式不对：${f.absolutePath}")
            chain.add(m)
            val parent = m["inheritsFrom"] as? String ?: break
            if (parent == cur || parents.contains(parent)) break
            parents.add(parent)
            cur = parent
        }
        return Loaded(merge(chain), clientJar, parents)
    }

    /** 从最父往最子逐层覆盖合并。 */
    private fun merge(chainChildFirst: List<Map<*, *>>): Map<String, Any?> {
        val flat = LinkedHashMap<String, Any?>()
        for (m in chainChildFirst.asReversed()) {
            for (e in m.entries) {
                val key = e.key as? String ?: continue
                val value = e.value
                when (key) {
                    "inheritsFrom" -> {}
                    "libraries" -> {
                        val merged = ArrayList<Any?>()
                        (value as? List<*>)?.let { merged.addAll(it) }        // 更子的一层排前面，同名库优先
                        (flat["libraries"] as? List<*>)?.let { merged.addAll(it) }
                        flat["libraries"] = dedupeLibs(merged)
                    }
                    "arguments" -> {
                        val add = value as? Map<*, *> ?: continue
                        val old = flat["arguments"] as? Map<*, *>
                        flat["arguments"] = if (old == null) add else mergeArgs(old, add)
                    }
                    else -> flat[key] = value
                }
            }
        }
        return flat
    }

    /**
     * 同 maven 坐标只留一条（保留先出现的位置，也就是更子层优先），
     * 但把后出现那条里多出来的字段补进来 —— natives / downloads 往往只有一边写全，
     * 直接丢掉会让 dll 提不出来。不同版本的同一库（asm:9.6 与 asm:9.9）不去重，让先出现的赢。
     */
    private fun dedupeLibs(list: List<Any?>): List<Any?> {
        val index = HashMap<String, Int>()
        val out = ArrayList<Any?>(list.size)
        for (item in list) {
            val lib = item as? Map<*, *> ?: run { out.add(item); continue }
            val name = lib["name"] as? String ?: run { out.add(item); continue }
            val at = index[name]
            if (at == null) {
                index[name] = out.size
                out.add(lib)
            } else {
                val base = out[at]
                val merged = LinkedHashMap<Any?, Any?>()
                if (base is Map<*, *>) for ((k, v) in base) merged[k] = v
                for ((k, v) in lib) if (!merged.containsKey(k)) merged[k] = v
                out[at] = merged
            }
        }
        return out
    }

    /** jvm / game 参数都是「父在前、子在后」拼接，跟官方 launcher 一致。 */
    private fun mergeArgs(parent: Map<*, *>, child: Map<*, *>): Map<String, Any?> {
        val keys = LinkedHashSet<String>()
        parent.keys.forEach { (it as? String)?.let(keys::add) }
        child.keys.forEach { (it as? String)?.let(keys::add) }
        val out = LinkedHashMap<String, Any?>()
        for (k in keys) {
            val p = parent[k]
            val c = child[k]
            out[k] = if (p is List<*> && c is List<*>) p + c else (c ?: p)
        }
        return out
    }

    // ---------- java 可执行文件 ----------

    private fun resolveJavaExe(path: String): String {
        val f = File(path)
        if (f.isFile) return f.absolutePath
        val names = if (osName == "windows") listOf("javaw.exe", "java.exe") else listOf("java")
        for (n in names) {
            val exe = File(f, "bin/$n")
            if (exe.isFile) return exe.absolutePath
        }
        return File(f, "bin/${names.first()}").absolutePath
    }

    // ---------- 依赖库 ----------

    private fun libraryPath(lib: Map<*, *>): String? {
        val artifact = (lib["downloads"] as? Map<*, *>)?.get("artifact") as? Map<*, *>
        ((artifact?.get("path") as? String)?.takeIf { it.isNotBlank() })?.let { return it }
        // Fabric 的写法只给 maven 坐标，路径得自己推：group:artifact:version[:classifier][@ext]
        val name = lib["name"] as? String ?: return null
        val p = name.split(":")
        if (p.size < 3) return null
        val group = p[0].replace('.', '/')
        val artifactId = p[1]
        val version = p[2].substringBefore('@')
        val classifier = p.getOrNull(3)?.substringBefore('@')?.takeIf { it.isNotBlank() }
        return buildString {
            append("$group/$artifactId/$version/$artifactId-$version")
            if (classifier != null) append("-$classifier")
            append(".jar")
        }
    }

    /**
     * 取 natives 压缩包路径。
     * 老格式（1.12 及以前）：靠 natives 字段挑系统，真实包在 downloads.classifiers 里；
     * 新格式（1.19+ / Fabric）：分类符直接写进 name，如 org.lwjgl:lwjgl:3.3.2:natives-windows。
     */
    private fun nativesJarPath(lib: Map<*, *>): String? {
        val natives = lib["natives"] as? Map<*, *>
        if (natives != null) {
            val key = natives[osName] as? String ?: return null
            if (!classifierAllowed(key)) return null
            val classifiers = (lib["downloads"] as? Map<*, *>)?.get("classifiers") as? Map<*, *>
            val fromJson = (classifiers?.get(key) as? Map<*, *>)?.get("path") as? String
            if (!fromJson.isNullOrBlank()) return fromJson
            val p = (lib["name"] as? String)?.split(":") ?: return null
            if (p.size < 3) return null
            val group = p[0].replace('.', '/')
            val artifactId = p[1]
            val version = p[2].substringBefore('@')
            return "$group/$artifactId/$version/$artifactId-$version-$key.jar"
        }
        val classifier = (lib["name"] as? String)?.split(":")?.getOrNull(3) ?: return null
        if (!classifier.startsWith("natives")) return null
        if (!classifierAllowed(classifier)) return null
        return libraryPath(lib)
    }

    /**
     * natives 分类符必须对得上本机平台和架构。
     * json 里会同时列出 windows / windows-arm64 / windows-x86 / linux / macos 各一套，
     * 不筛的话会互相覆盖同名 dll（只能靠 json 里的先后顺序碰运气）。
     */
    private fun classifierAllowed(classifier: String): Boolean {
        val parts = classifier.split("-")            // natives / windows / arm64
        if (parts.firstOrNull() != "natives") return false
        val want = when (osName) {
            "windows" -> "windows"
            "osx" -> "macos"
            else -> "linux"
        }
        if (parts.getOrNull(1) != want) return false
        val archToken = parts.getOrNull(2) ?: return true   // 不写架构 = 通用版（64 位）
        val a = System.getProperty("os.arch").lowercase()
        val isArm64 = a.contains("aarch64") || a.contains("arm64")
        val isX64 = a.contains("64") && !isArm64
        val isX86 = a == "x86" || a == "i386" || a == "i686"
        return when (archToken) {
            "arm64" -> isArm64
            "arm32" -> a.contains("arm") && !isArm64
            "x86" -> isX86
            "x64" -> isX64
            else -> true
        }
    }

    /** 对应 json 里的 rules：不匹配任何规则就是不允许。 */
    private fun rulesAllow(rules: Any?): Boolean {
        val list = rules as? List<*> ?: return true
        if (list.isEmpty()) return true
        var allow = false
        for (item in list) {
            val rule = item as? Map<*, *> ?: continue
            if (!ruleMatches(rule)) continue
            allow = rule["action"] == "allow"
        }
        return allow
    }

    private fun ruleMatches(rule: Map<*, *>): Boolean {
        // 我们不开 demo、自定义分辨率那些特性，带 features 的规则一律不算匹配
        if (rule["features"] != null) return false
        val os = rule["os"] as? Map<*, *> ?: return true
        (os["name"] as? String)?.let { if (it != osName) return false }
        (os["arch"] as? String)?.let { if (!archMatches(it)) return false }
        return true
    }

    private fun archMatches(ruleArch: String): Boolean {
        val a = System.getProperty("os.arch").lowercase()
        return when (ruleArch) {
            "x86" -> a == "x86" || a == "i386" || a == "i686"
            "x86_64" -> a.contains("64") && !a.contains("arm")
            "arm64" -> a.contains("aarch64") || a.contains("arm64")
            else -> false
        }
    }

    /** 把平台相关的动态库从 natives 包里提到 natives 目录，返回提取的文件数。 */
    private fun extractNatives(libraries: List<Map<*, *>>, libDir: File, nativesDir: File): Int {
        nativesDir.mkdirs()
        var count = 0
        for (lib in libraries) {
            if (!rulesAllow(lib["rules"])) continue
            val rel = nativesJarPath(lib) ?: continue
            val jar = File(libDir, rel)
            if (!jar.isFile) continue
            val exclude = ((lib["extract"] as? Map<*, *>)?.get("exclude") as? List<*>)
                ?.filterIsInstance<String>() ?: emptyList()
            try {
                ZipFile(jar).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        val n = e.name
                        if (n.startsWith("META-INF/")) continue
                        if (exclude.any { n.startsWith(it) }) continue
                        if (!n.endsWith(".dll") && !n.endsWith(".so") && !n.endsWith(".dylib")) continue
                        val target = File(nativesDir, n.substringAfterLast('/'))
                        if (target.isFile && target.length() > 0L) continue
                        zip.getInputStream(e).use { input ->
                            target.outputStream().use { out -> input.copyTo(out) }
                        }
                        count++
                    }
                }
            } catch (_: Exception) {
                // 单个库解压失败不影响其它库
            }
        }
        return count
    }

    // ---------- 参数 ----------

    private fun collectArgs(root: Map<*, *>, key: String, vars: Map<String, String>): List<String> {
        val arguments = root["arguments"] as? Map<*, *>
        if (arguments == null) {
            // 1.12 及以前：jvm 参数要自己拼，游戏参数是整条字符串
            return if (key == "jvm") {
                buildList {
                    add("-Djava.library.path=" + vars.getValue("natives_directory"))
                    val cp = vars.getValue("classpath")
                    if (cp.isNotEmpty()) {
                        add("-cp")
                        add(cp)
                    }
                }
            } else {
                (root["minecraftArguments"] as? String)
                    ?.split(" ")?.filter { it.isNotBlank() }
                    ?.map { replace(it, vars) }
                    ?: emptyList()
            }
        }
        val list = arguments[key] as? List<*> ?: return emptyList()
        val out = ArrayList<String>()
        for (item in list) {
            when (item) {
                is String -> out.add(replace(item, vars))
                is Map<*, *> -> {
                    if (!rulesAllow(item["rules"])) continue
                    when (val v = item["value"]) {
                        is String -> out.add(replace(v, vars))
                        is List<*> -> v.forEach { if (it is String) out.add(replace(it, vars)) }
                    }
                }
            }
        }
        return out
    }

    private fun replace(s: String, vars: Map<String, String>): String {
        var r = s
        for ((k, v) in vars) r = r.replace("\${" + k + "}", v)
        return r
    }

    /** 离线模式 UUID：官方就是 "OfflinePlayer:名字" 的 MD5 再摆弄两个字节，JDK 正有现成实现。 */
    private fun offlineUuid(name: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString()

    // ---------- Java 版本校验 ----------

    /** 校验结果：blocked = true 是硬拦（根本跑不起来），false 是提醒（能起但可能崩）。 */
    data class JavaCheck(val blocked: Boolean, val message: String)

    /** 读版本要求的 Java 大版本（继承链上子层优先），json 里没写就返回 null。 */
    fun requiredJavaMajor(version: String): Int? {
        return try {
            val root = loadVersion(BackendUtil.minecraftDir, version).flat
            val jv = root["javaVersion"] as? Map<*, *> ?: return null
            asInt(jv["majorVersion"])
        } catch (_: Exception) {
            null
        }
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /**
     * 比对选中的 Java 和版本要求的 Java，没问题返回 null。
     * 低了必崩（blocked）；高一点没关系，高出太多才提醒（Mixin / ASM 版本对不上会崩）。
     */
    fun checkJava(version: String, javaVersionText: String): JavaCheck? {
        val required = requiredJavaMajor(version) ?: return null
        val actual = javaMajor(javaVersionText) ?: return null
        return when {
            actual == required -> null
            actual < required -> JavaCheck(
                true,
                "「$version」要求的 Java 版本为 $required，当前选择的是 Java $actual。\n" +
                        "所选版本低于该版本的最低要求，无法启动。\n" +
                        "请将 Java 版本改选为 Java $required。"
            )
            actual - required <= 4 -> null        // 高几个大版本一般没事，不打扰
            else -> JavaCheck(
                false,
                "「$version」要求的 Java 版本为 $required，当前选择的是 Java $actual。\n" +
                        "所选版本高于该版本的推荐值，可能因 Mixin / ASM 兼容性问题导致游戏崩溃。\n" +
                        "建议将 Java 版本改选为 Java $required。"
            )
        }
    }

    /**
     * "17.0.18" → 17；"1.8.0_392" → 8；认不出来（如 "zulu-17"）返回 null。
     * 公开出来是因为 [JavaStore] 自动挑 Java 时也要按大版本比对。
     */
    fun javaMajor(text: String): Int? {
        val parts = text.trim().trim('"').split('.', '_', '-', '+')
        val first = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
    }

}
