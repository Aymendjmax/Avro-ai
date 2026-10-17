package com.newoether.agora.sandbox

import com.newoether.agora.util.PortableGlobMatcher

internal fun normalizeVirtualPath(path: String): String {
    val raw = path.trim().replace('\\', '/')
    val absolute = if (raw.isBlank()) "/" else if (raw.startsWith("/")) raw else "/$raw"
    val collapsed = absolute.replace(Regex("/+"), "/")
    return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
}

internal fun globMatch(files: List<String>, pattern: String): List<String> {
    val cleanPattern = pattern.trim().replace('\\', '/')
    val adjusted = when {
        cleanPattern.isBlank() -> "/**"
        cleanPattern.startsWith("/") -> cleanPattern
        cleanPattern.contains("/") -> "/$cleanPattern"
        else -> "/**/$cleanPattern"
    }
    return files.filter { file -> PortableGlobMatcher.matches(adjusted, file) }
}
