package org.example.statemind.core.loader

import java.util.concurrent.ConcurrentHashMap
import org.example.statemind.core.MiniJson

/**
 * OptiFine 的版本清单。
 *
 * 官网的下载链接要经过一次跳转页拿临时地址，第三方启动器没法直接拼；BMCLAPI 按
 * `游戏版本/type/patch` 直接把 jar 端出来，路径稳定。
 */
object OptiFineSource {

    private const val BASE = "https://bmclapi2.bangbang93.com/optifine"

    private val cache = ConcurrentHashMap<String, List<LoaderVersion>>()

    /** 1.8 的条目记在 1.8.0 名下，请求时得换回官方的写法。 */
    private fun lookup(mcVersion: String): String = when (mcVersion) {
        "1.8" -> "1.8.0"
        "1.9" -> "1.9.0"
        else -> mcVersion
    }

    fun versions(mcVersion: String): List<LoaderVersion> {
        // 1.13 起 OptiFine 改成了 Forge 模组，jar 里没有补丁器，脱离 Forge 装不出来。
        // 这一行在这些版本上直接整行隐藏，别给出一个装到一半必然失败的选项。
        if (!standalone(mcVersion)) return emptyList()
        cache[mcVersion]?.let { return it }
        val body = LoaderSource.fetchText("$BASE/${lookup(mcVersion)}")
        val list = MiniJson.parse(body) as? List<*> ?: return emptyList()
        val parsed = list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val patch = (map["patch"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = (map["type"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val stable = !patch.startsWith("pre", true) && !patch.startsWith("alpha", true)
            LoaderVersion("${type}_$patch", stable)
        }.distinctBy { it.id }
        val result = VersionOrder.newestFirst(parsed)
        cache[mcVersion] = result
        return result
    }

    /** 只有 1.12.2 及更早的安装包带 `optifine.Patcher`，能补进原版客户端自己启动。 */
    private fun standalone(mcVersion: String): Boolean {
        val parts = mcVersion.split('.').map { it.toIntOrNull() ?: return false }
        val minor = parts.getOrNull(1) ?: return false
        return parts[0] == 1 && minor < 13
    }

    /** 下拉里的 `HD_U_I6` 拆回 type + patch，拼下载地址。 */
    fun downloadUrl(mcVersion: String, loaderVersion: String): String {
        val separator = loaderVersion.lastIndexOf('_')
        val type = if (separator > 0) loaderVersion.substring(0, separator) else loaderVersion
        val patch = if (separator > 0) loaderVersion.substring(separator + 1) else ""
        return "$BASE/${lookup(mcVersion)}/$type/$patch"
    }
}
