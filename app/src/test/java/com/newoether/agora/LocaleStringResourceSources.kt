package com.newoether.agora

import java.io.File

/** Reads every XML source in the locale containing this string-resource anchor file. */
internal fun File.readLocaleStringResourceSources(): String {
    check(isFile) { "Missing string-resource anchor: $path" }
    val directory = checkNotNull(parentFile)
    check(directory.name == "values" || directory.name.startsWith("values-")) {
        "Expected a values resource directory: ${directory.path}"
    }
    val sources = checkNotNull(directory.listFiles { file ->
        file.isFile && file.extension == "xml"
    }) { "Unable to list locale resources: ${directory.path}" }
    check(sources.isNotEmpty()) { "No locale resource sources: ${directory.path}" }
    return sources.sortedBy(File::getName).joinToString("\n") { it.readText() }
}
