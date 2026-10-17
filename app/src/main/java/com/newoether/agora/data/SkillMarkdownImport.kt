package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import com.newoether.agora.util.FileValidator
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private const val MAX_SKILL_IMPORT_BYTES = 1_048_576

internal fun readSkillMarkdown(
    context: Context,
    uri: Uri,
): Pair<String, String> {
    val reportedSize = FileValidator.resolveFileSize(context, uri)
    require(reportedSize == null || reportedSize <= MAX_SKILL_IMPORT_BYTES) {
        "Skill file must be 1 MB or smaller"
    }
    val sourceFileName = FileValidator.resolveFileName(context, uri)
        ?.takeIf(String::isNotBlank)
        ?: "skill-${System.currentTimeMillis()}.md"
    require(sourceFileName.endsWith(".md", ignoreCase = true)) {
        "Select a Markdown (.md) file"
    }
    val fileName = sourceFileName.dropLast(3) + ".md"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream(
            minOf(reportedSize?.toInt() ?: 8_192, MAX_SKILL_IMPORT_BYTES),
        )
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_SKILL_IMPORT_BYTES) {
                "Skill file must be 1 MB or smaller"
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: error("Unable to open skill file")
    val content = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    return fileName to content
}
