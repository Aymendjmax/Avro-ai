package com.newoether.agora.ui

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File

internal abstract class UiSourceContractFixture {
    protected fun source(root: File, path: String): String =
        File(root, path).readText().replace("\r\n", "\n")

    protected fun sourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate source root")
    }

    protected fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) {
                return if (candidate.name == "strings.xml") {
                    candidate.readLocaleStringResourceSources()
                } else candidate.readText()
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
