plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "org.example"
version = "0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

// ── JavaFX ───────────────────────────────────────────────────────────────────
// 没有用 org.openjfx.javafxplugin：该插件 0.1.0 停留在 Gradle 8 时代，
// 在 Gradle 9 上会踩到已移除的旧 API。改为手动声明带「平台分类符」的依赖。
val javafxVersion = "25"
val javafxPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arch.contains("aarch64")) "mac-aarch64" else "mac"
        else -> if (arch.contains("aarch64")) "linux-aarch64" else "linux"
    }
}

dependencies {
    // 四个模块都关掉传递依赖，避免把「无分类符」的空壳 jar 也拉进来，
    // 否则运行时会报 "Error initializing QuantumRenderer: no suitable pipeline found"。
    listOf("javafx-base", "javafx-graphics", "javafx-controls", "javafx-fxml").forEach { module ->
        implementation("org.openjfx:$module:$javafxVersion:$javafxPlatform") { isTransitive = false }
    }
    // ── UI 组件库 ────────────────────────────────────────────────────────────
    // Atlantafx 主题（JavaFX 25 友好，未引入 javafxplugin 冲突）
    implementation("io.github.mkpaz:atlantafx-base:2.1.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("org.example.statemind.MainKt")
    // JDK 24+ 起，未命名模块调用本地库会告警，这条消掉噪音。
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
}

// ── 打包（出分发包时才用）────────────────────────────────────────────────────
// 全部逻辑在 packaging/ 里，日常开发完全碰不到。
// 要出包：gradlew packageDist  →  build/jpackage/out/ 下的 .msi 与 .zip
apply(from = "packaging/package.gradle.kts")
