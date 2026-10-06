package org.example.statemind.core

import java.io.File

/**
 * 版本 json 的读法与平台判定：继承合并、库的本地路径、rules 是否放行、natives 该取哪个包。
 *
 * 启动和下载必须用**同一套**判断 —— 下载器按这套决定「下哪些文件、落到哪个路径」，
 * 启动器按同一套决定「去哪个路径找」，两边各写一份的话迟早对不上。
 */
object VersionJson {

    val osName: String = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") || it.contains("darwin") -> "osx"
            else -> "linux"
        }
    }

    class Loaded(
        val flat: Map<String, Any?>,
        val clientJar: File?,
        val parents: List<String>
    )

    /** 读版本 json；遇到 inheritsFrom 就把父版本合并进来，子层优先（Forge / OptiFine 都靠这个）。 */
    fun load(versionRoot: File, id: String): Loaded {
        val chain = ArrayList<Map<*, *>>()      // 子 → 父
        val parents = ArrayList<String>()
        var clientJar: File? = null
        var cur = id
        var guard = 0
        while (true) {
            if (guard++ > 8) throw IllegalStateException("版本继承层数过多：$id")
            val f = File(versionRoot, "versions/$cur/$cur.json")
            require(f.isFile) { "找不到版本文件：${f.absolutePath}" }
            if (clientJar == null) {
                File(versionRoot, "versions/$cur/$cur.jar").takeIf { it.isFile }?.let { clientJar = it }
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
    fun merge(chainChildFirst: List<Map<*, *>>): Map<String, Any?> {
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

    // ---------- 依赖库 ----------

    fun libraryPath(lib: Map<*, *>): String? {
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
    fun nativesJarPath(lib: Map<*, *>): String? {
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
    fun classifierAllowed(classifier: String): Boolean {
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
    fun rulesAllow(rules: Any?): Boolean {
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
}
