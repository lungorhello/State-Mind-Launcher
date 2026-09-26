import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

// ═════════════════════════════════════════════════════════════════════════════
//  打 包 —— 出一个 MSI 安装版 + 一个 ZIP 便携版
//
//  这个文件只负责「打包」，跟日常开发没有关系：
//    · 平时写功能不用打开它；gradlew build / gradlew run 也不会碰它
//    · 要出包时才跑：gradlew packageDist
//
//  由 build.gradle.kts 最后一行 apply(from = "packaging/package.gradle.kts") 引进来。
//  想改版本号、改产品名，改下面那几个 dist* 常量即可。
// ═════════════════════════════════════════════════════════════════════════════

// ---------- 出包时才会用到的几个值 ----------

/** 产品名。会变成开始菜单项、安装目录名、exe 文件名，改它等于换名字。 */
val distAppName = "State Mind Launcher"

/**
 * 发布版本号。**必须是 x.y.z 三段数字**（MSI 的硬要求，不能带 -SNAPSHOT）。
 * 与 build.gradle.kts 里的 `version` 无关：那个只是 Gradle 内部版本，这个才是发布号。
 */
val distAppVersion = "0.2.6.0"

val distMainClass = "org.example.statemind.MainKt"

/**
 * MSI 的 UpgradeCode —— 「装新版时自动替换掉已装旧版」靠的就是它。
 *
 * 这个值**必须和已发布版本完全一致**，否则新旧版会被 Windows 当成两个不同软件并存。
 * 当前这串是从已装的 0.1.0 MSI 里读出来的（`wix msi decompile` 看 Package/@UpgradeCode）。
 *
 * ⚠️ 以后所有版本都沿用这一个值，永远不要改。换名字/换厂商时才需要新申请一个。
 */
val distUpgradeUuid = "9F82C439-E78F-3D2B-B7BA-B5529B585253"

// ---------- 路径 ----------

val distPackagingDir = layout.projectDirectory.dir("packaging")
val distInputDir = layout.buildDirectory.dir("jpackage/input")
val distOutDir = layout.buildDirectory.dir("jpackage/out")
val distTmpDir = layout.buildDirectory.dir("jpackage/tmp")
val distAppImageDir = layout.buildDirectory.dir("jpackage/app-image")
val distPortableDir = layout.buildDirectory.dir("jpackage/portable")

/** jpackage 要 JDK 25。用 toolchain 拿，不依赖本机默认 JDK 是哪个。 */
val distJava25: Provider<JavaLauncher> =
    extensions.getByType(JavaToolchainService::class.java)
        .launcherFor { languageVersion = JavaLanguageVersion.of(25) }

fun distJpackageExe(): String =
    File(distJava25.get().metadata.installationPath.asFile, "bin/jpackage.exe").absolutePath

// ---------- 准备输入 ----------

/** jpackage 要求「一个扁平目录放主 jar + 全部依赖 jar」。 */
val distInput = tasks.register<Sync>("distInput") {
    group = "distribution"
    description = "把主 jar 与全部依赖 jar 摊平到一个目录，供 jpackage 使用"
    from(tasks.named("jar"))
    // 注意：这个文件是被 apply(from=) 引进来的「脚本插件」，Kotlin DSL 的
    // 类型安全访问器（configurations.runtimeClasspath 那种）在这里不会生成，
    // 必须按名字查。
    from(configurations.named("runtimeClasspath"))
    into(distInputDir)
    include("*.jar")
}

val distMainJarName: Provider<String> =
    tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { it.asFile.name }

/** 两种包共用的 jpackage 参数。 */
val distCommonArgs: Provider<List<String>> =
    distInputDir.zip(distMainJarName) { dir, jarName ->
        listOf(
            "--name", distAppName,
            "--app-version", distAppVersion,
            "--vendor", "State Mind",
            "--description", distAppName,
            "--input", dir.asFile.absolutePath,
            "--main-jar", jarName,
            "--main-class", distMainClass,
            "--java-options", "--enable-native-access=ALL-UNNAMED"
        )
    }

// ═════════════════════════════════════════════════════════════════════════════
//  便携版（ZIP）：免安装目录 → 加便携标记 → 打 zip
//
//  便携版的原理见 src/.../core/Portable.kt：解压目录里带一个 portable.txt，
//  程序启动时从自己所在位置往上找到它，就把数据写进包内的 data\ 而不是 %APPDATA%。
// ═════════════════════════════════════════════════════════════════════════════

tasks.register<Exec>("packageAppImage") {
    group = "distribution"
    description = "打出免安装目录（便携版的基础）"
    dependsOn(distInput)
    val outDir = distAppImageDir
    doFirst {
        val out = outDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        commandLine(
            buildList {
                add(distJpackageExe())
                addAll(listOf("--type", "app-image", "--dest", out.absolutePath))
                addAll(distCommonArgs.get())
            }
        )
    }
}

/** 摆成「解压即用」的结构：app-image + 便携标记 + 使用说明。 */
val distStagePortable = tasks.register<Sync>("stagePortable") {
    group = "distribution"
    dependsOn("packageAppImage")
    from(distAppImageDir.map { File(it.asFile, distAppName) })
    from(distPackagingDir.file("portable.txt"))
    from(distPackagingDir.file("README-便携版.txt"))
    into(distPortableDir)

    // README-便携版.txt 里的版本号写成 @VERSION@ 占位符，拷进来之后替换成真实值。
    // 以前这里写死版本号，发版时忘了改，包里的说明就比实际版本旧一号。
    doLast {
        val readme = File(distPortableDir.get().asFile, "README-便携版.txt")
        if (readme.isFile) {
            readme.writeText(readme.readText().replace("@VERSION@", distAppVersion))
        }
    }
}

tasks.register<Zip>("packagePortableZip") {
    group = "distribution"
    description = "打出便携 ZIP（解压即用，数据留在包内的 data\\）"
    dependsOn(distStagePortable)
    archiveFileName.set("State-Mind-Launcher-$distAppVersion-portable.zip")
    destinationDirectory.set(distOutDir)
    // 套一层同名目录，避免解压时把几百个文件直接撒在桌面上
    from(distPortableDir) { into("State-Mind-Launcher-$distAppVersion") }
}

// ═════════════════════════════════════════════════════════════════════════════
//  安装版（MSI）
//
//  前置：WiX 5（dotnet tool 装的）+ Util/UI 两个扩展。
//  自定义的 wix/main.wxs 会覆盖 jpackage 默认模板，给卸载流程插一个
//  「是否同时删除游戏数据」的勾选框。
// ═════════════════════════════════════════════════════════════════════════════

tasks.register<Exec>("packageMsi") {
    group = "distribution"
    description = "打出 MSI 安装包（沿用同一 UpgradeCode，装新版自动替换旧版）"
    dependsOn(distInput)

    val outDir = distOutDir
    val tmpDir = distTmpDir
    val wixResourceDir = distPackagingDir.dir("wix")
    val dotnetTools = File(System.getProperty("user.home"), ".dotnet/tools")

    doFirst {
        val out = outDir.get().asFile.apply { mkdirs() }
        // jpackage 要求 --temp 必须是**空目录**，否则直接报错退出。上次中断留下的文件要先清掉。
        val tmp = tmpDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        // jpackage 找不到 wix.exe 时只会含糊地说 "exited with ... code"，务必显式补 PATH。
        environment("PATH", System.getenv("PATH") + File.pathSeparator + dotnetTools.absolutePath)
        commandLine(
            buildList {
                add(distJpackageExe())
                addAll(
                    listOf(
                        "--type", "msi",
                        "--dest", out.absolutePath,
                        "--temp", tmp.absolutePath,
                        "--win-shortcut",
                        "--win-menu",
                        "--win-menu-group", distAppName,
                        "--win-per-user-install",
                        "--win-dir-chooser",
                        "--win-upgrade-uuid", distUpgradeUuid
                    )
                )
                val wix = wixResourceDir.asFile
                if (wix.isDirectory && wix.listFiles()?.isNotEmpty() == true) {
                    addAll(listOf("--resource-dir", wix.absolutePath))
                }
                addAll(distCommonArgs.get())
            }
        )
    }
}

// ---------- 一次出齐 ----------

tasks.register("packageDist") {
    group = "distribution"
    description = "一次打出 MSI 安装版 + ZIP 便携版（产物在 build/jpackage/out/）"
    dependsOn("packageMsi", "packagePortableZip")
}
