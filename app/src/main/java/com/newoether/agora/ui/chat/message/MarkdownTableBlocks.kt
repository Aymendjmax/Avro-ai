package com.newoether.agora.ui.chat.message

/**
 * The GFM parser used for chat Markdown cannot interrupt a paragraph with a table, so a table whose
 * header row sits directly under a text line - the shape models produce most often after a bold
 * heading or a sentence - rendered as literal `|` characters for the rest of the message.
 *
 * [openTableBlocks] gives every table a block of its own before parsing. A line only counts as a
 * table header once the delimiter row beneath it proves it is a table, so prose that merely
 * contains a pipe is untouched, and fenced code keeps its content verbatim.
 */
internal fun String.openTableBlocks(): String {
    if (!contains('|') || !contains('\n')) return this
    val insertions = ArrayList<Int>(2)
    var lineStart = 0
    var previousLineStart = -1
    var previousLine = ""
    var fenceMarker: Char? = null
    var index = 0
    while (index <= length) {
        if (index != length && this[index] != '\n') {
            index++
            continue
        }
        val line = substring(lineStart, index).removeSuffix("\r")
        var indent = 0
        while (indent < line.length && line[indent] == ' ') indent++
        val marker = if (indent < line.length) line[indent] else null
        val fenceRun = marker != null && (marker == '`' || marker == '~') &&
            line.startsWith("$marker$marker$marker", indent)
        when {
            fenceMarker == null && fenceRun -> fenceMarker = marker
            fenceMarker != null && fenceRun -> fenceMarker = null
            fenceMarker == null && previousLineStart > 0 &&
                previousLine.contains('|') && !isTableDelimiterRow(previousLine) &&
                isTableDelimiterRow(line) -> insertions += previousLineStart
        }
        previousLine = line
        previousLineStart = lineStart
        lineStart = index + 1
        index++
    }
    if (insertions.isEmpty()) return this
    val original = this
    val total = original.length
    return buildString(total + insertions.size) {
        var cursor = 0
        insertions.forEach { headerStart ->
            append(original, cursor, headerStart)
            append('\n')
            cursor = headerStart
        }
        append(original, cursor, total)
    }
}

/** `|---|:--:|` and friends: the row that turns the line above it into a table header. */
private fun isTableDelimiterRow(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('|') || !trimmed.contains('-')) return false
    return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}
