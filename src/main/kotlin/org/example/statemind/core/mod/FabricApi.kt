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

    private class Request(val mcVersion: String, val gameDir: File)

    private val pending = ConcurrentHashMap<Long, Request>()
    private val hooked = AtomicBoolean(false)

    /** [jobId] 对应那次版本下载；它成功之后才去取 Fabric API。 */
    fun afterVersionInstall(jobId: Long, mcVersion: String, gameDir: File) {
        installHook()
        pending[jobId] = Request(mcVersion, gameDir)
    }

    private fun installHook() {
        if (!hooked.compareAndSet(false, true)) return
        DownloadCenter.onJobFinished { job ->
            val request = pending.remove(job.id) ?: return@onJobFinished
            Thread({ runCatching { install(request) } }, "fabric-api").apply { isDaemon = true }.start()
        }
    }

    private fun install(request: Request) {
        val version = Modrinth.versions(PROJECT).firstOrNull { candidate ->
            request.mcVersion in candidate.gameVersions && candidate.loaders.any { it in LOADERS }
        } ?: return
        val file = version.files.firstOrNull() ?: return

        val dir = File(request.gameDir, ModKind.MOD.dir)
        val name = fileNameOf(Prefs.resourceNaming, "Fabric API", file.fileName)
        val entry = InstallLog.begin("Fabric API", ModKind.MOD, name, File(dir, name), file.size)
        ModInstaller.install(dir, file, name, entry)
    }
}
