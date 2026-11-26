package org.example.statemind.core.download

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.example.statemind.core.FileSource
import org.example.statemind.core.GameDir
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
     * [targetId] 是这次装出来的版本名（安装页上可以改）。落成几个文件夹看 [GameDir.installShapeOf]：
     * 共享形态下原版的材料落在 `versions/<版本号>-resources/`（见 [GameDir.SharedVanilla]），
     * 原版与加载器版本都只留一层壳靠 `inheritsFrom` 回来找；自包含形态下所有文件都进
     * `versions/<targetId>/`，不另建原版目录。
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
        val versionDir = File(root, "versions/$targetId")
        // 内置目录与 multi 系共享原版，其余传统目录装成自包含（一个文件夹一个完整版本）
        val selfContained = GameDir.installShapeOf(root) == GameDir.InstallShape.SELF_CONTAINED
        // 共享形态下原版和加载器都只做薄壳，json 与 client.jar 只在材料目录里存一份。
        // 单下原版也走这条路：否则同一个版本会被下两遍，玩家自己装的版本还会被当成别人的材料。
        val shared = if (selfContained) null else GameDir.SharedVanilla.of(root, entry.id)
        val libDir = File(root, "libraries")
        val assetsDir = File(root, "assets")

        // 同一个目标路径只留一条：库表里偶有重复条目，重复下没意义
        val seen = HashSet<String>()
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
        // 共享材料由我们自己写盘，不走下载：官方 json 里的 `id` 是版本号，跟 `-resources`
        // 这个目录名对不上，PCL 认 id 与目录名一致，会把它判成错误版本。已有的那份不动。
        if (shared != null && !shared.json.isFile) {
            shared.dir.mkdirs()
            shared.json.writeText(withId(versionText, shared.id), Charsets.UTF_8)
        }
        if (loader == null) {
            versionDir.mkdirs()
            val text = if (shared == null) {
                // 自包含：官方 json 本身就是全量，换个 id 直接写进自己的目录，jar 也下在这儿
                withId(versionText, targetId)
            } else {
                // 共享形态：自己只留一层壳，全量 json 与 jar 都在材料目录里，不再存第二份
                MiniJson.write(linkedMapOf<String, Any?>("id" to targetId, "inheritsFrom" to shared.id))
            }
            File(versionDir, "$targetId.json").writeText(text, Charsets.UTF_8)
        }

        // ① 客户端本体
        val downloads = json["downloads"] as? Map<*, *>
        val client = downloads?.get("client") as? Map<*, *>
        if (client != null) {
            add(
                // 共享形态落在材料目录里；已有且校验得过的会被跳过，不会重复下
                shared?.jar ?: File(versionDir, "$targetId.jar"),
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
            buildLoaderPlan(
                it, entry, root, source, json, targetId, versionDir, libDir,
                selfContained, shared, onNote
            )
        }

        // ⑥ 跟着版本一起装的前置（Fabric API）。它下在版本自己的 mods/ 里，不进游戏根目录
        val extraStages = ArrayList<DownloadStage>()
        if (loader != null && (loader.kind == LoaderKind.FABRIC || loader.kind == LoaderKind.QUILT)) {
            fabricApiStage(fabricApiVersionId, entry.id, versionDir, source)?.let { extraStages += it }
        }

        val vanilla = ArrayList<DownloadStage>()
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
        selfContained: Boolean,
        shared: GameDir.SharedVanilla.Shared?,
        onNote: (String) -> Unit
    ): LoaderPlan {
        val label = loader.kind.label
        // 安装器按路径找客户端本体：共享形态在材料目录里，自包含形态在自己目录里
        val clientJar = shared?.jar ?: File(versionDir, "$targetId.jar")
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
                        vanilla = json,
                        vanillaJar = clientJar
                    )
                    versionDir.mkdirs()
                    File(versionDir, "$targetId.json")
                        .writeText(versionJsonText(prepared.versionText, targetId, json, selfContained, shared?.id), Charsets.UTF_8)

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
                        requiredJava = InstallJava.requiredMajor(json),
                        vanillaJar = clientJar
                    )
                    versionDir.mkdirs()
                    File(versionDir, "$targetId.json")
                        .writeText(versionJsonText(prepared.versionText, targetId, json, selfContained, shared?.id), Charsets.UTF_8)

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
                File(versionDir, "$targetId.json")
                    .writeText(versionJsonText(profileText, targetId, json, selfContained, shared?.id), Charsets.UTF_8)
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
     * 把加载器 profile json 里的 `id` 换成这次装出来的版本名。
     *
     * 官方 profile json 的 id 是 `fabric-loader-0.20.1-26.3` 这种形状，跟目录名
     * （用户改过的 `26.3-Fabric 0.20.1`）对不上。PCL 认版本目录名必须与 json 里的 id 一致，
     * 对不上就把这个版本判成错误版本，自己另建一个能跑的 —— 同一份游戏目录里就出现两个版本。
     *
     * 只替换顶层那一个 id 的值，别的地方一个字符都不动（库条目里也有 name/id 之类的字段）。
     */
    /**
     * 版本 json 的最终文本。
     *
     * 共享形态原样落盘（只改 id）：加载器那份只写自己的库，原版材料靠 `inheritsFrom` 回父版本找。
     * 自包含形态把父版本合并进来摊成一份全量 json —— 之后这个目录不依赖任何别的版本，
     * 按「一个文件夹 = 一个版本」理解它的工具（PCL、备份、直接拷走）才不会判它缺文件。
     */
    private fun versionJsonText(
        loaderText: String,
        targetId: String,
        parent: Map<*, *>,
        selfContained: Boolean,
        parentId: String?
    ): String {
        if (!selfContained) {
            // 共享形态：id 换成目录名，inheritsFrom 指向材料目录（新命名是 `<版本号>-resources`）
            val renamed = withId(loaderText, targetId)
            return parentId?.let { withParent(renamed, it) } ?: renamed
        }
        val child = MiniJson.parse(loaderText) as? Map<*, *> ?: return withId(loaderText, targetId)
        val flat = LinkedHashMap<String, Any?>(VersionJson.merge(listOf(child, parent)))
        flat["id"] = targetId
        return MiniJson.write(flat)
    }

    private fun withId(text: String, id: String): String = withString(text, "\"id\"", id)

    /**
     * 改 `inheritsFrom` 的指向。
     *
     * 共享材料目录现在叫 `<版本号>-resources`，而官方 profile 和各家安装器写出来的还是 `<版本号>`。
     * 不改的话加载器版本指到一个不存在的目录 —— 我们起不来，PCL 还会判「缺少 xxx」并自己补一份。
     */
    private fun withParent(text: String, parentId: String): String {
        if (text.contains("\"inheritsFrom\"")) return withString(text, "\"inheritsFrom\"", parentId)

        // 没有这个字段就补在开头：加载器版本必须有父版本，缺了就是个不知道从哪拿原版材料的空壳
        val brace = text.indexOf('{')
        if (brace < 0) return text
        val safe = parentId.replace("\\", "\\\\").replace("\"", "\\\"")
        return text.substring(0, brace + 1) + "\"inheritsFrom\": \"$safe\"," + text.substring(brace + 1)
    }

    private fun withString(text: String, key: String, value: String): String {
        val at = text.indexOf(key)
        if (at < 0) return text
        val colon = text.indexOf(':', at + key.length)
        if (colon < 0) return text
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '"') return text
        val end = text.indexOf('"', i + 1)
        if (end < 0) return text
        val safe = value.replace("\\", "\\\\").replace("\"", "\\\"")
        // 切在引号内侧：i 是开引号、end 是闭引号，两边都要留着，否则值变成裸字面量，
        // 解析到第一个 '-' 就报「期望 '.' 或 '}'」。
        return text.substring(0, i + 1) + safe + text.substring(end)
    }

    private fun number(value: Any?): Long = (value as? Number)?.toLong() ?: 0L
}
