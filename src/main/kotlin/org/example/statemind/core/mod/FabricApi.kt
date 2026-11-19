package org.example.statemind.core.mod

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.example.statemind.core.Prefs
import org.example.statemind.core.download.DownloadCenter
import org.example.statemind.core.fileNameOf

/**
 * 装完 Fabric / Quilt 版本之后，顺手把 Fabric API 放进 `mods/`。
 *
 * 它不是一个「可选的模组」，而是 Fabric 生态里几乎所有模组的前置依赖；
 * 装了加载器却没有它，用户下一分钟就会在启动器里看到一片模组加载失败。
 * 下载任务成功落地时才会触发 —— 版本本身都装失败了，再往里塞模组没有意义。
 */
object FabricApi {

    /** Modrinth 上这个项目的 slug 就是 `fabric-api`。 */
    private const val PROJECT = "fabric-api"

    /** Quilt 能直接吃 Fabric 的模组，Fabric API 同样可装。 */
    private val LOADERS = setOf("fabric", "quilt")

    private class Request(val mcVersion: String, val gameDir: File, val apiVersionId: String?)

    private val pending = ConcurrentHashMap<Long, Request>()
    private val hooked = AtomicBoolean(false)

    /**
     * 某个游戏版本能用的 Fabric API 版本，新在前。会阻塞，调用方扔后台线程。
     *
     * 界面要在「选了哪个加载器版本」之后把这张卡片显示出来，所以查询是公开的；
     * 真正安装时再按 id 找一次 —— 中间可能过了几分钟，列表未必还是那份。
     */
    fun versionsFor(mcVersion: String): List<Modrinth.Version> =
        Modrinth.versions(PROJECT).filter { version ->
            mcVersion in version.gameVersions && version.loaders.any { it in LOADERS }
        }

    /** [jobId] 对应那次版本下载；它成功之后才去取 Fabric API。[apiVersionId] 为空就不装。 */
    fun afterVersionInstall(jobId: Long, mcVersion: String, gameDir: File, apiVersionId: String?) {
        if (apiVersionId.isNullOrBlank()) return
        installHook()
        pending[jobId] = Request(mcVersion, gameDir, apiVersionId)
    }

    private fun installHook() {
        if (!hooked.compareAndSet(false, true)) return
        DownloadCenter.onJobFinished { job ->
            val request = pending.remove(job.id) ?: return@onJobFinished
            Thread({ runCatching { install(request) } }, "fabric-api").apply { isDaemon = true }.start()
        }
    }

    private fun install(request: Request) {
        val candidates = Modrinth.versions(PROJECT)
        val version = candidates.firstOrNull { it.id == request.apiVersionId }
            ?: candidates.firstOrNull { candidate ->
                request.mcVersion in candidate.gameVersions && candidate.loaders.any { it in LOADERS }
            }
            ?: return
        val file = version.files.firstOrNull() ?: return

        val dir = File(request.gameDir, ModKind.MOD.dir)
        val name = fileNameOf(Prefs.resourceNaming, "Fabric API", file.fileName)
        val entry = InstallLog.begin("Fabric API", ModKind.MOD, name, File(dir, name), file.size)
        ModInstaller.install(dir, file, name, entry)
    }
}
