package org.example.statemind.core.download

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.example.statemind.core.FileSource
import org.example.statemind.core.HttpText
import org.example.statemind.core.MiniJson
import org.example.statemind.core.VersionEntry
import org.example.statemind.core.VersionJson
import org.example.statemind.core.auth.Http
import org.example.statemind.core.install.ForgeInstall
import org.example.statemind.core.install.InstallJava
import org.example.statemind.core.install.InstallStep
import org.example.statemind.core.install.OptiFineInstall
import org.example.statemind.core.loader.ForgeSource
import org.example.statemind.core.loader.LiteLoaderSource
import org.example.statemind.core.loader.LoaderChoice
import org.example.statemind.core.loader.LoaderKind
import org.example.statemind.core.loader.OptiFineSource
import org.example.statemind.core.mod.FabricApi
import org.example.statemind.core.mod.ModKind

/** 一个待下载的文件：落到哪儿、按什么顺序试哪些地址、多大、校验值。 */
class DownloadItem(
    val target: File,
    val urls: List<String>,
    val size: Long,
    val sha1: String?
)

/**
 * 任务里的一段工作。
 *
 * 下载**仍然是并行的** —— 能一起下的就一起下，不为了显示好看去拆成串行。
 * 分段只是为了界面：几百个文件一起下的时候，只给一根总进度条看上去就像卡住了，
 * 按段列出来才知道「现在在干哪一段」。
 */
class DownloadStage(
    val title: String,
    val files: List<DownloadItem>,
    /** 这一段的文件下完之后要跑的本地步骤（安装器、补丁器）。 */
    val steps: List<InstallStep> = emptyList()
)

/** 阶段标题。跟 PCL 的说法对齐，用户看着不用猜。 */
object DownloadStages {
    const val VANILLA_VERSION = "下载原版版本信息"
    const val VANILLA_CLIENT = "下载原版客户端"
    const val VANILLA_LIBS = "下载原版支持库"
    const val VANILLA_ASSETS = "下载原版资源"

    /** 加载器自己的主文件（Forge / NeoForge 的安装器、OptiFine 的 jar）。 */
    fun main(label: String) = "下载$label 主文件"

    /** 读安装器、算出它要哪些库。 */
    fun analyze(label: String) = "分析$label 支持库"

    /** 加载器那份支持库。 */
    fun libs(label: String) = "获取$label 支持库"

    /** 跑安装器 / 补丁器那一步。 */
    fun install(label: String) = "安装$label"

    /** 加载器本体（Fabric / Quilt / LiteLoader 没有单独的安装器，它们的库就是本体）。 */
    fun loader(label: String) = "下载$label"

    const val FABRIC_API = "下载 Fabric API"

    /** 最后一步：铺资源、生成版本目录。 */
    const val INSTALL_GAME = "安装游戏版本"
}

/**
 * 加载器这一段要做的事。
 *
 * 分两种情况：
 * - Fabric / Quilt / LiteLoader：库表就在它自己的 profile json 里，plan 阶段就读得到，
 *   所以它们的文件能跟原版文件**一起并行下**。
 * - Forge / NeoForge / OptiFine：库清单**藏在安装器里**，必须先下安装器 → 读它 → 才知道要下哪些库。
 *   这段跟原版没法完全并行 —— 不是不想，是依赖关系决定的。
 */
class LoaderPlan(
    val label: String,
    /** 先下这个（安装器）。已经下好了就是 null。 */
    val installer: DownloadItem?,
    /** 立刻就能并行下的阶段（Fabric 系的库）。 */
    val stages: List<DownloadStage>,
    /** 安装器就位之后才知道要下哪些库；不需要这一步就是 null。 */
    val prepare: (() -> LoaderPrepared)?
)

/** 加载器解析完的结果：后续的阶段，以及没有地址只能跳过的库。 */
class LoaderPrepared(
    val stages: List<DownloadStage>,
    val skipped: List<String>
)

/**
 * 下载计划 —— **先把要下什么全算出来，再交给 [DownloadRunner] 执行**。
 *
 * 与启动那条 `LaunchUtil.plan()` 同一个思路：算与执行分开，才能先告诉用户「这次要下多少」，
 * 也才能在真正动手前就把「哪些库没有地址」这类问题抖出来。
 */
class DownloadPlan(
    val versionId: String,
    /** 原版那几段。 */
    val vanilla: List<DownloadStage>,
    /** 加载器那几段；原版没有加载器就是 null。 */
    val loader: LoaderPlan?,
    /** 跟着版本一起装的前置（Fabric API）。 */
    val extra: List<DownloadStage>,
    val assetsDir: File,
    val gameDir: File,
    val assetIndexId: String,
    /** 老版本索引的标志位：资源要额外铺一份到 `assets/virtual/<索引>/`。 */
    val virtualAssets: Boolean,
    /** 老版本索引的标志位：资源要额外铺一份到游戏目录的 `resources/`。 */
    val mapToResources: Boolean,
    /** 没有下载地址、只能跳过的库（第三方 maven 那些）。 */
    val skipped: List<String>
)

object DownloadPlanner {

    private const val RESOURCES = "https://resources.download.minecraft.net"

    /**
     * 会阻塞（要读版本 json、资源索引；加载器还要读安装器 —— 但那部分挪到下载阶段了）。
     * 落点是**版本隔离**目录：游戏本体进 `versions/<id>/`，共享的 `libraries/` 与 `assets/` 进游戏目录根。
     *
     * [targetId] 是这次装出来的版本名（安装页上可以改）。原版的 json 与客户端 jar 仍然落在
     * `versions/<entry.id>/` —— 加载器版本靠 `inheritsFrom` 回来找它，同一份原版也可能被
     * 好几个加载器版本共用，不能按新名字再存一遍。
     */
    fun plan(
        entry: VersionEntry,
        root: File,
        source: FileSource,
        loader: LoaderChoice? = null,
        targetId: String = entry.id,
        fabricApiVersionId: String? = null,
        onNote: (String) -> Unit = {}
    ): DownloadPlan {
        val vanillaDir = File(root, "versions/${entry.id}")
        val versionDir = File(root, "versions/$targetId")
        val renamed = targetId != entry.id
        val libDir = File(root, "libraries")
        val assetsDir = File(root, "assets")

        // 同一个目标路径只留一条：库表里偶有重复条目，重复下没意义
        val seen = HashSet<String>()
        val versionFiles = ArrayList<DownloadItem>()
        val clientFiles = ArrayList<DownloadItem>()
        val libFiles = ArrayList<DownloadItem>()
        val assetFiles = ArrayList<DownloadItem>()
        val skipped = ArrayList<String>()

        fun add(target: File, url: String?, size: Long, sha1: String?, bucket: MutableList<DownloadItem>) {
            if (url.isNullOrBlank()) return
            if (!seen.add(target.absolutePath)) return
            bucket += DownloadItem(target, source.order(url), size, sha1?.takeIf { it.isNotBlank() })
        }

        /** 一条库记录拆成要下的文件；算不出地址的记进 [skipped]。 */
        fun libraryItems(lib: Map<*, *>): Pair<List<DownloadItem>, List<String>> {
            if (!VersionJson.rulesAllow(lib["rules"])) return emptyList<DownloadItem>() to emptyList<String>()
            val out = ArrayList<DownloadItem>()
            val miss = ArrayList<String>()
            val dl = lib["downloads"] as? Map<*, *>
            val artifact = dl?.get("artifact") as? Map<*, *>
            if (artifact != null) {
                val path = artifact["path"] as? String
                // Forge 的版本 json 把本体那条写成空字符串地址，按坐标补一个，否则会被当成「没有源」
                val url = (artifact["url"] as? String)?.takeIf { it.isNotBlank() } ?: VersionJson.mavenUrl(lib)
                if (path != null && url != null) {
                    out += DownloadItem(File(libDir, path), source.order(url), number(artifact["size"]), artifact["sha1"] as? String)
                } else {
                    (lib["name"] as? String)?.let { miss += it }
                }
            } else if (dl == null) {
                // 只给 maven 坐标、没有 downloads 的库（Fabric / Quilt 的库全是这种）：按坐标拼地址
                val url = VersionJson.mavenUrl(lib)
                val path = VersionJson.libraryPath(lib)
                if (url != null && path != null) {
                    // 大小只有服务端知道，先按未知处理：下载完以实际字节数为准
                    out += DownloadItem(File(libDir, path), source.order(url), 0L, null)
                } else {
                    (lib["name"] as? String)?.let { miss += it }
                }
            }
            // natives：新格式是一条带分类符的库，老格式另有一份包在 classifiers 里
            val nativeKey = (lib["natives"] as? Map<*, *>)?.get(VersionJson.osName) as? String
            if (nativeKey != null) {
                val classifier = (dl?.get("classifiers") as? Map<*, *>)?.get(nativeKey) as? Map<*, *>
                if (classifier != null) {
                    val path = classifier["path"] as? String
                    val url = (classifier["url"] as? String)?.takeIf { it.isNotBlank() }
                        ?: VersionJson.mavenUrl(lib)
                    if (path != null && url != null) {
                        out += DownloadItem(File(libDir, path), source.order(url), number(classifier["size"]), classifier["sha1"] as? String)
                    }
                }
            }
            return out to miss
        }

        onNote("读取版本信息…")
        val versionText = fetchText(entry.url, source)
        val json = MiniJson.parse(versionText) as? Map<*, *>
            ?: throw IOException("版本文件不是 JSON 对象")
        add(File(vanillaDir, "${entry.id}.json"), entry.url, entry.size, entry.sha1, versionFiles)

        // 原版改了名：新目录里补一份 json，`jar` 指回原版那份 —— 客户端本体不必再下一遍。
        // id 也要跟着改成新名字：PCL 认 id 与目录名一致，对不上就判成错误版本。
        if (renamed && loader == null) {
            versionDir.mkdirs()
            File(versionDir, "$targetId.json")
                .writeText(withId(withJar(versionText, entry.id), targetId), Charsets.UTF_8)
        }

        // ① 客户端本体
        val downloads = json["downloads"] as? Map<*, *>
        val client = downloads?.get("client") as? Map<*, *>
        if (client != null) {
            add(
                File(vanillaDir, "${entry.id}.jar"),
                client["url"] as? String,
                number(client["size"]),
                client["sha1"] as? String,
                clientFiles
            )
        }

        // ② 依赖库。新格式（1.19+）natives 本身就是一条带分类符的库，走 artifact；
        //    老格式（1.12 及以前）另有一份 natives 包，在 downloads.classifiers 里
        onNote("整理依赖库…")
        val libs = (json["libraries"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        for (lib in libs) {
            val (items, miss) = libraryItems(lib)
            libFiles += items
            skipped += miss
        }

        // ③ 资源索引与资源对象。资源对象只有拿到索引才知道有哪些，所以这里必须先把它读下来
        onNote("读取资源索引…")
        val index = json["assetIndex"] as? Map<*, *>
        val indexId = (index?.get("id") as? String) ?: (json["assets"] as? String) ?: "legacy"
        var virtual = false
        var mapToResources = false
        if (index != null) {
            val indexUrl = index["url"] as? String
            add(
                File(assetsDir, "indexes/$indexId.json"),
                indexUrl,
                number(index["size"]),
                index["sha1"] as? String,
                assetFiles
            )
            val body = fetchText(indexUrl, source)
            val parsed = MiniJson.parse(body) as? Map<*, *>
            val objects = parsed?.get("objects") as? Map<*, *>
            virtual = parsed?.get("virtual") == true
            mapToResources = parsed?.get("map_to_resources") == true
            for ((_, value) in objects.orEmpty()) {
                val obj = value as? Map<*, *> ?: continue
                val hash = obj["hash"] as? String ?: continue
                if (hash.length < 2) continue
                val sub = hash.substring(0, 2)
                add(
                    File(assetsDir, "objects/$sub/$hash"),
                    "$RESOURCES/$sub/$hash",
                    number(obj["size"]),
                    hash,
                    assetFiles
                )
            }
        }

        // ④ 日志配置（log4j2 的 xml，约 1 KB，缺了会刷一堆警告）
        val logFile = ((json["logging"] as? Map<*, *>)?.get("client") as? Map<*, *>)
            ?.get("file") as? Map<*, *>
        val logId = logFile?.get("id") as? String
        if (logFile != null && !logId.isNullOrBlank()) {
            add(
                File(assetsDir, "log_configs/$logId"),
                logFile["url"] as? String,
                number(logFile["size"]),
                logFile["sha1"] as? String,
                assetFiles
            )
        }

        // ⑤ 加载器
        val loaderPlan = loader?.let {
            buildLoaderPlan(it, entry, root, source, json, targetId, versionDir, libDir, onNote)
        }

        // ⑥ 跟着版本一起装的前置（Fabric API）。它下在版本自己的 mods/ 里，不进游戏根目录
        val extraStages = ArrayList<DownloadStage>()
        if (loader != null && (loader.kind == LoaderKind.FABRIC || loader.kind == LoaderKind.QUILT)) {
            fabricApiStage(fabricApiVersionId, entry.id, versionDir, source)?.let { extraStages += it }
        }

        val vanilla = ArrayList<DownloadStage>()
        if (versionFiles.isNotEmpty()) vanilla += DownloadStage(DownloadStages.VANILLA_VERSION, versionFiles)
        if (clientFiles.isNotEmpty()) vanilla += DownloadStage(DownloadStages.VANILLA_CLIENT, clientFiles)
        if (libFiles.isNotEmpty()) vanilla += DownloadStage(DownloadStages.VANILLA_LIBS, libFiles)
        if (assetFiles.isNotEmpty()) vanilla += DownloadStage(DownloadStages.VANILLA_ASSETS, assetFiles)

        return DownloadPlan(
            versionId = targetId,
            vanilla = vanilla,
            loader = loaderPlan,
            extra = extraStages,
            assetsDir = assetsDir,
            gameDir = versionDir,
            assetIndexId = indexId,
            virtualAssets = virtual,
            mapToResources = mapToResources,
            skipped = skipped
        )
    }

    /**
     * 加载器这一段怎么走。
     *
     * Forge / NeoForge / OptiFine 的库清单藏在安装器里，所以「下安装器」和「下它的库」
     * 是前后两段，不是一段。Fabric / Quilt / LiteLoader 没有安装器，库表在它自己的
     * profile json 里 —— plan 阶段读回来就行，它们的文件能跟原版并行下。
     */
    private fun buildLoaderPlan(
        loader: LoaderChoice,
        entry: VersionEntry,
        root: File,
        source: FileSource,
        json: Map<*, *>,
        targetId: String,
        versionDir: File,
        libDir: File,
        onNote: (String) -> Unit
    ): LoaderPlan {
        val label = loader.kind.label
        return when (loader.kind) {
            LoaderKind.FORGE, LoaderKind.NEOFORGE -> {
                val installer = installerItem(loader, source, root)
                LoaderPlan(label, installer, emptyList(), {
                    val file = installer?.target ?: installerTarget(loader, root)
                    if (!isZip(file)) throw IOException("${label}安装器下载不完整")
                    val prepared = ForgeInstall.prepare(
                        installer = file,
                        root = root,
                        mcVersion = entry.id,
                        targetId = targetId,
                        requiredJava = InstallJava.requiredMajor(json),
                        mavenRoot = ForgeSource.mavenRoot(loader.kind),
                        vanilla = json
                    )
                    versionDir.mkdirs()
                    File(versionDir, "$targetId.json").writeText(prepared.versionText, Charsets.UTF_8)

                    val libItems = ArrayList<DownloadItem>()
                    val miss = ArrayList<String>()
                    for (lib in prepared.libraries) {
                        if (!VersionJson.rulesAllow(lib["rules"])) continue
                        val artifact = (lib["downloads"] as? Map<*, *>)?.get("artifact") as? Map<*, *>
                        val localPath = artifact?.get("path") as? String
                        // 安装器自带、以及 processor 产物那几条已经在本地了，别再去下一遍
                        if (localPath != null && localPath in prepared.localPaths) continue
                        val path = localPath ?: VersionJson.libraryPath(lib)
                        val url = (artifact?.get("url") as? String)?.takeIf { it.isNotBlank() }
                            ?: VersionJson.mavenUrl(lib)
                        if (path == null || url == null) {
                            (lib["name"] as? String)?.let { miss += it }
                            continue
                        }
                        libItems += DownloadItem(
                            File(libDir, path),
                            source.order(url),
                            number(artifact?.get("size")),
                            artifact?.get("sha1") as? String
                        )
                    }
                    // 安装器说要下的零散文件（mojmap）：官方那份地址连不上，用我们自己下的
                    for (extra in prepared.extraFiles) {
                        libItems += DownloadItem(
                            File(libDir, extra.path),
                            source.order(extra.url),
                            extra.size,
                            extra.sha1
                        )
                    }
                    LoaderPrepared(
                        stages = listOf(
                            DownloadStage(DownloadStages.libs(label), libItems),
                            DownloadStage(DownloadStages.install(label), emptyList(), prepared.steps)
                        ),
                        skipped = miss
                    )
                })
            }

            LoaderKind.OPTIFINE -> {
                val installer = installerItem(loader, source, root)
                LoaderPlan(label, installer, emptyList(), {
                    val file = installer?.target ?: installerTarget(loader, root)
                    if (!isZip(file)) throw IOException("${label}安装器下载不完整")
                    val prepared = OptiFineInstall.prepare(
                        installer = file,
                        root = root,
                        mcVersion = entry.id,
                        loaderVersion = loader.loaderVersion,
                        targetId = targetId,
                        vanilla = json,
                        requiredJava = InstallJava.requiredMajor(json)
                    )
                    versionDir.mkdirs()
                    File(versionDir, "$targetId.json").writeText(prepared.versionText, Charsets.UTF_8)

                    val libItems = ArrayList<DownloadItem>()
                    val miss = ArrayList<String>()
                    for (lib in prepared.libraries) {
                        if (!VersionJson.rulesAllow(lib["rules"])) continue
                        val artifact = (lib["downloads"] as? Map<*, *>)?.get("artifact") as? Map<*, *>
                        val localPath = artifact?.get("path") as? String
                        // OptiFine 本体与它自带的 launchwrapper 由补丁步骤写在库目录里，
                        // 没有可下载的地址；交给下载器只会白试一轮 404
                        if (localPath != null && localPath in prepared.localPaths) continue
                        val path = localPath ?: VersionJson.libraryPath(lib)
                        val url = (artifact?.get("url") as? String)?.takeIf { it.isNotBlank() }
                            ?: VersionJson.mavenUrl(lib)
                        if (path == null || url == null) {
                            (lib["name"] as? String)?.let { miss += it }
                            continue
                        }
                        libItems += DownloadItem(
                            File(libDir, path),
                            source.order(url),
                            number(artifact?.get("size")),
                            artifact?.get("sha1") as? String
                        )
                    }
                    LoaderPrepared(
                        stages = listOf(
                            DownloadStage(DownloadStages.libs(label), libItems),
                            DownloadStage(DownloadStages.install(label), emptyList(), prepared.steps)
                        ),
                        skipped = miss
                    )
                })
            }

            else -> {
                // Fabric / Quilt / LiteLoader 没有安装器：库表在它自己的 profile json 里。
                // 这里就读回来，它们的文件能跟原版一起并行下。
                onNote("读取${label}配置…")
                val profileText = if (loader.kind == LoaderKind.LITELOADER) {
                    LiteLoaderSource.versionJson(
                        entry.id,
                        loader.loaderVersion,
                        targetId,
                        json["minecraftArguments"] as? String
                    )
                } else {
                    fetchText(loader.profileUrl, source)
                }
                versionDir.mkdirs()
                // 目录名就是版本 id：官方 profile json 里的 id 写的是 `fabric-loader-0.20.1-26.3`，
                // 跟用户改过的名字（`26.3-Fabric 0.20.1`）对不上。PCL 认 id 与目录名一致，
                // 对不上就把这个版本判成错误版本，还会自己补一个出来 —— 同一份游戏目录里两个版本。
                File(versionDir, "$targetId.json").writeText(withId(profileText, targetId), Charsets.UTF_8)
                val profile = MiniJson.parse(profileText) as? Map<*, *>
                    ?: throw IOException("加载器配置不是 JSON 对象")
                val loaderLibs = (profile["libraries"] as? List<*>)
                    ?.filterIsInstance<Map<*, *>>()
                    ?: emptyList()
                val libItems = ArrayList<DownloadItem>()
                val miss = ArrayList<String>()
                for (lib in loaderLibs) {
                    if (!VersionJson.rulesAllow(lib["rules"])) continue
                    val dl = lib["downloads"] as? Map<*, *>
                    val artifact = dl?.get("artifact") as? Map<*, *>
                    val path = artifact?.get("path") as? String ?: VersionJson.libraryPath(lib)
                    val url = (artifact?.get("url") as? String)?.takeIf { it.isNotBlank() }
                        ?: VersionJson.mavenUrl(lib)
                    if (path == null || url == null) {
                        (lib["name"] as? String)?.let { miss += it }
                        continue
                    }
                    libItems += DownloadItem(
                        File(libDir, path),
                        source.order(url),
                        number(artifact?.get("size")),
                        artifact?.get("sha1") as? String
                    )
                }
                LoaderPlan(
                    label = label,
                    installer = null,
                    stages = listOf(DownloadStage(DownloadStages.loader(label), libItems)),
                    prepare = null
                )
            }
        }
    }

    /**
     * Fabric API 那一步。
     *
     * 它落在版本自己的 `mods/` 里 —— 版本隔离开着，游戏只读这个目录。
     * 解析不出来就不装这一段：模组前置缺了不该把整个版本下载判成失败。
     */
    private fun fabricApiStage(
        apiVersionId: String?,
        mcVersion: String,
        versionDir: File,
        source: FileSource
    ): DownloadStage? {
        val resolved = runCatching { FabricApi.resolve(mcVersion, apiVersionId) }.getOrNull() ?: return null
        val dir = File(versionDir, ModKind.MOD.dir)
        val target = File(dir, resolved.targetName)
        return DownloadStage(
            DownloadStages.FABRIC_API,
            listOf(DownloadItem(target, source.order(resolved.url), resolved.size, resolved.sha1))
        )
    }

    /**
     * 安装器要下就下。已经下好了（并且还是完整的 zip）就是 null —— 不重复下。
     */
    private fun installerItem(loader: LoaderChoice, source: FileSource, root: File): DownloadItem? {
        val target = installerTarget(loader, root)
        if (isZip(target)) return null
        val urls = when (loader.kind) {
            LoaderKind.OPTIFINE -> listOf(OptiFineSource.downloadUrl(loader.mcVersion, loader.loaderVersion))
            else -> ForgeSource.installerUrls(loader.kind, loader.mcVersion, loader.loaderVersion)
        }
        val candidates = urls.flatMap { source.order(it) }
        if (candidates.isEmpty()) throw IOException("没有找到${loader.kind.label}安装器的下载地址")
        return DownloadItem(target, candidates, 0L, null)
    }

    /**
     * 安装器落哪儿。装完之后留在 `libraries/.sml-installers/` —— 重装或换名字时不必再下一遍，
     * 它也不参与启动，放在库目录里不会被人当成依赖。
     */
    private fun installerTarget(loader: LoaderChoice, root: File): File {
        val dir = File(root, "libraries/.sml-installers")
        val name = loader.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jar"
        return File(dir, name)
    }

    private fun isZip(file: File): Boolean =
        file.isFile && file.length() > 0L && runCatching {
            ZipFile(file).use { it.entries().hasMoreElements() }
        }.getOrDefault(false)

    /** 依次试 [source] 排好的地址，拿到第一个成功响应的正文。 */
    private fun fetchText(url: String?, source: FileSource): String {
        if (url.isNullOrBlank()) throw IOException("地址为空")
        var last: Exception? = null
        for (candidate in source.order(url)) {
            val response = runCatching { Http.get(candidate, 30) }
            if (response.isSuccess && response.getOrNull()?.status in 200..299) {
                return response.getOrThrow().body
            }
            val status = response.getOrNull()?.status
            last = response.exceptionOrNull() as? Exception
                ?: IOException(if (status != null) HttpText.of(status) else "读取失败")
        }
        val error = last
        throw IOException(if (error == null) "读取失败" else HttpText.of(error), error)
    }

    /**
     * 给原版 json 补一个 `jar` 字段，指向原版目录里的客户端本体。
     *
     * 版本改了名之后，`versions/<新名>/<新名>.jar` 是不存在的；`VersionJson.load` 认这个字段，
     * 于是能顺着它找回 `versions/<版本号>/<版本号>.jar`，不用把 41 MB 复制一份。
     * 键序在 json 里没有意义，直接插在开头最省事，也不碰正文里的任何字符。
     */
    private fun withJar(text: String, jar: String): String {
        if (text.contains("\"jar\"")) return text
        val brace = text.indexOf('{')
        if (brace < 0) return text
        val safe = jar.replace("\\", "\\\\").replace("\"", "\\\"")
        return text.substring(0, brace + 1) + "\"jar\": \"$safe\"," + text.substring(brace + 1)
    }

    /**
     * 把加载器 profile json 里的 `id` 换成这次装出来的版本名。
     *
     * 官方 profile json 的 id 是 `fabric-loader-0.20.1-26.3` 这种形状，跟目录名
     * （用户改过的 `26.3-Fabric 0.20.1`）对不上。PCL 认版本目录名必须与 json 里的 id 一致，
     * 对不上就把这个版本判成错误版本，自己另建一个能跑的 —— 同一份游戏目录里就出现两个版本。
     *
     * 只替换顶层那一个 id 的值，别的地方一个字符都不动（库条目里也有 name/id 之类的字段）。
     */
    private fun withId(text: String, id: String): String {
        val key = "\"id\""
        val at = text.indexOf(key)
        if (at < 0) return text
        val colon = text.indexOf(':', at + key.length)
        if (colon < 0) return text
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '"') return text
        val end = text.indexOf('"', i + 1)
        if (end < 0) return text
        val safe = id.replace("\\", "\\\\").replace("\"", "\\\"")
        return text.substring(0, i) + safe + text.substring(end + 1)
    }

    private fun number(value: Any?): Long = (value as? Number)?.toLong() ?: 0L
}
