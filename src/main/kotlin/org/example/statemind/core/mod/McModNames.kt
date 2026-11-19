package org.example.statemind.core.mod

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import org.example.statemind.core.GameDir
import org.example.statemind.core.auth.Http

/**
 * 项目的中文名，查 MC 百科。
 *
 * Modrinth 只给英文标题，国内玩家的叫法（Sodium → 钠）基本都来自 MC 百科的条目名；
 * 启动器自己带一张小表只能覆盖最热的那几十个。这里改成按英文标题现查一次搜索结果，
 * 命中的结果写进本地缓存文件，下次点开同一个项目就不再联网。
 *
 * 只认「括号里的英文名跟标题完全一致」的那一条：搜 Sodium 会顺带出「钠 · 扩展」
 * 「钠/Embeddium：动态光源」，名字里带附加说明的一律不要 —— 认错比查不到更糟。
 */
object McModNames {

    private const val SEARCH = "https://search.mcmod.cn/s?key="

    /** 默认 UA 会被挡，这里是照着浏览器的样子写的。 */
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    /** 搜索结果里每条项目都是一个指向 `class/<id>.html` 的链接。 */
    private val ITEM = Regex("""href="https://www\.mcmod\.cn/class/\d+\.html">(.*?)</a>""")
    private val TAG = Regex("<[^>]+>")

    /** 空串 = 查过、确实没有。这样同一个项目不会每次点开都再问一遍。 */
    private val cache = ConcurrentHashMap<String, String>()

    private var loaded = false

    /** 缓存文件。放在启动器自己的目录里，跟设置同级。 */
    private fun file(): File = File(GameDir.appRoot(), "modnames.txt")

    /**
     * 查中文名；查不到、或者结果不像同一件东西时返回 null。
     *
     * 会联网，只能在后台线程调。
     */
    fun lookup(title: String): String? {
        val key = title.trim()
        if (key.isEmpty()) return null
        load()

        cache[key]?.let { return it.ifEmpty { null } }

        val encoded = URLEncoder.encode(key, StandardCharsets.UTF_8)
        val body = runCatching {
            Http.get(SEARCH + encoded, mapOf("User-Agent" to UA), 12L).body
        }.getOrNull() ?: return null

        val found = parse(body, key)
        remember(key, found)
        return found
    }

    private fun parse(html: String, title: String): String? {
        for (match in ITEM.findAll(html)) {
            val plain = match.groupValues[1].replace(TAG, "").trim()
            val cut = plain.indexOf('(')
            if (cut <= 0) continue
            val zh = plain.substring(0, cut).trim()
            val en = plain.substring(cut + 1).trim().trimEnd(')').trim()
            if (!en.equals(title, ignoreCase = true)) continue
            if (zh.isEmpty() || zh.none { it.code >= 0x2E80 }) continue
            // 「钠 · 扩展」「钠/Embeddium：动态光源」这类不是本体
            if (zh.any { it in "·/：:" }) continue
            return zh
        }
        return null
    }

    private fun remember(title: String, zh: String?) {
        cache[title] = zh.orEmpty()
        runCatching { file().appendText("$title\t${zh.orEmpty()}\n", Charsets.UTF_8) }
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val f = file()
        if (!f.isFile) return
        runCatching {
            f.forEachLine { line ->
                val cut = line.indexOf('\t')
                if (cut <= 0) return@forEachLine
                cache[line.substring(0, cut)] = line.substring(cut + 1).trim()
            }
        }
    }
}
