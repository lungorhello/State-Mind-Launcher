package org.example.statemind.core

import java.io.File

object BackendUtil {

    val minecraftDir: File get() = GameDir.defaultLocation()

    fun getGameCores(): List<String> {
        val versionsDir = File(minecraftDir, "versions")
        if (!versionsDir.isDirectory) return emptyList()
        return versionsDir.listFiles { f -> f.isDirectory }
            ?.filter { dir -> File(dir, "${dir.name}.json").isFile }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
    }

    data class JavaInfo(val version: String, val path: String) {
        override fun toString(): String =
            "Java $version" + if (path.isBlank()) "" else "    $path"
    }

    private val searchRoots: List<File> by lazy {
        val programFiles = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(X86)"),
            System.getenv("ProgramW6432")
        ).map { File(it) }
        val vendors = listOf("Java", "Eclipse Adoptium", "Amazon Corretto",
            "Microsoft", "Zulu", "BellSoft", "Semeru", "Temurin")
        programFiles.flatMap { pf -> vendors.map { File(pf, it) } }
    }

    fun getJavas(): List<JavaInfo> {
        val result = LinkedHashMap<String, JavaInfo>()
        System.getenv("JAVA_HOME")?.let { addJava(File(it), result) }
        for (root in searchRoots) scanForJava(root, result, 3)
        scanForJava(File(minecraftDir, "runtime"), result, 3)
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.forEach { dir ->
            val exe = File(dir, "java.exe")
            if (exe.isFile) addJava(exe.parentFile.parentFile, result)
        }
        return result.values.sortedByDescending { it.version }
    }

    private fun addJava(home: File, result: LinkedHashMap<String, JavaInfo>) {
        if (!File(home, "bin/java.exe").isFile) return
        result[home.absolutePath] = JavaInfo(readVersion(home), home.absolutePath)
    }

    private fun readVersion(home: File): String {
        val release = File(home, "release")
        if (release.isFile) {
            release.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("JAVA_VERSION=")) {
                        return line.substringAfter("JAVA_VERSION=").trim('"')
                    }
                }
            }
        }
        return home.name
    }

    private fun scanForJava(dir: File, result: LinkedHashMap<String, JavaInfo>, depth: Int) {
        if (depth < 0 || !dir.isDirectory) return
        addJava(dir, result)
        dir.listFiles { f -> f.isDirectory }?.forEach { sub ->
            scanForJava(sub, result, depth - 1)
        }
    }
}
