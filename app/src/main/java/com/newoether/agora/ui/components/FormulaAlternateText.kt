package com.newoether.agora.ui.components

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard

/*
 * A rendered formula is an inline placeholder whose alternate text is the formula's source, so a
 * selection copies that source. A placeholder must sit on one line, and Android cannot lay out or
 * draw one whose alternate text contains a line break, so line breaks in a formula's source are
 * stored as private-use characters. FormulaSourceClipboard turns them back into line breaks when
 * a selection is copied, so the copied text equals the original message text.
 */

private const val LINE_FEED_MARK = '\uE000'
private const val CARRIAGE_RETURN_MARK = '\uE001'

/** Placeholder alternate text for a formula: its source with line breaks stored as marks. */
internal fun formulaAlternateText(source: String): String =
    source.replace('\n', LINE_FEED_MARK).replace('\r', CARRIAGE_RETURN_MARK)

/** Undoes [formulaAlternateText] anywhere in [text]. */
internal fun restoreFormulaLineBreaks(text: String): String =
    if (text.none { it == LINE_FEED_MARK || it == CARRIAGE_RETURN_MARK }) {
        text
    } else {
        text.replace(LINE_FEED_MARK, '\n').replace(CARRIAGE_RETURN_MARK, '\r')
    }

/** Clipboard for selection hosts: text copied from a selection gets its formula line breaks back. */
internal class FormulaSourceClipboard(private val base: Clipboard) : Clipboard {
    override val nativeClipboard: NativeClipboard get() = base.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = base.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        base.setClipEntry(clipEntry?.let(::restoreFormulaLineBreaks))
    }
}

private fun restoreFormulaLineBreaks(entry: ClipEntry): ClipEntry {
    val clip = entry.clipData
    val texts = (0 until clip.itemCount).map { clip.getItemAt(it).text?.toString() }
    if (texts.none { it != null && restoreFormulaLineBreaks(it) != it }) return entry
    val items = texts.map { ClipData.Item(it?.let(::restoreFormulaLineBreaks)) }
    val restored = ClipData(clip.description, items.first())
    items.drop(1).forEach(restored::addItem)
    return ClipEntry(restored)
}
