package com.newoether.agora.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.util.regex.Pattern

/**
 * Checks if a Char belongs to Arabic Unicode script blocks.
 */
fun Char.isArabicScript(): Boolean {
    val code = this.code
    return (code in 0x0600..0x06FF) || // Arabic
           (code in 0x0750..0x077F) || // Arabic Supplement
           (code in 0x08A0..0x08FF) || // Arabic Extended-A
           (code in 0xFB50..0xFDFF) || // Arabic Presentation Forms-A
           (code in 0xFE70..0xFEFF)    // Arabic Presentation Forms-B
}

/**
 * Returns true if this string contains any Arabic characters.
 */
fun CharSequence.containsArabic(): Boolean {
    for (i in 0 until length) {
        if (this[i].isArabicScript()) return true
    }
    return false
}

/**
 * Returns true if this string contains only Latin/ASCII characters, digits, spaces, and punctuation.
 */
fun CharSequence.isPurelyLatin(): Boolean {
    for (i in 0 until length) {
        val c = this[i]
        if (c.isArabicScript()) return false
    }
    return true
}

/**
 * Formats a text into a bilingual [AnnotatedString] that smartly assigns fonts:
 * - Arabic script segments use [NotoArabicFamily] with letterSpacing = 0.sp (as per Avro Brand Guide).
 * - Latin / English script segments use [latinFamily] (defaults to [OutfitFamily] so English brand names,
 *   model names like "Claude 3.5 Sonnet", "Gemini", "Avro", code words, and URLs preserve their signature
 *   geometric Outfit styling even when running in Arabic mode).
 */
fun formatBilingualText(
    text: CharSequence,
    isArabicMode: Boolean = true,
    latinFamily: FontFamily = OutfitFamily,
    arabicFamily: FontFamily = NotoArabicFamily
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")

    // If not in Arabic mode and text has no Arabic, keep standard text rendering
    if (!isArabicMode && !text.containsArabic()) {
        return AnnotatedString(text.toString())
    }

    // If in Arabic mode but text is purely Latin/English (e.g. "Avro", "API Key", "Claude 3.5 Sonnet"),
    // apply OutfitFamily to the entire string so Latin words never look generic
    if (text.isPurelyLatin()) {
        return buildAnnotatedString {
            append(text.toString())
            addStyle(
                style = SpanStyle(
                    fontFamily = latinFamily
                ),
                start = 0,
                end = text.length
            )
        }
    }

    // Mixed content: Segment Latin/English tokens vs Arabic tokens
    // Matches Latin words, alphanumeric tokens, model identifiers (e.g. "GPT-4o", "Claude 3.5 Sonnet")
    val latinPattern = Pattern.compile("[A-Za-z0-9]+(?:[-_.:/][A-Za-z0-9]+)*")
    val matcher = latinPattern.matcher(text)

    val latinRanges = mutableListOf<IntRange>()
    while (matcher.find()) {
        latinRanges.add(matcher.start() until matcher.end())
    }

    return buildAnnotatedString {
        append(text.toString())

        // Default base style for Arabic text: Noto Sans Arabic with letterSpacing = 0.sp
        addStyle(
            style = SpanStyle(
                fontFamily = arabicFamily,
                letterSpacing = 0.sp
            ),
            start = 0,
            end = text.length
        )

        // Overlay Latin segments with OutfitFamily (or selected English font)
        for (range in latinRanges) {
            addStyle(
                style = SpanStyle(
                    fontFamily = latinFamily,
                    letterSpacing = 0.sp
                ),
                start = range.first,
                end = range.last + 1
            )
        }
    }
}

/**
 * Convenient Composable Text component that automatically handles bilingual typography.
 */
@Composable
fun BilingualText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current
) {
    val isArabic = LocalIsArabic.current
    val annotated = remember(text, isArabic, fontFamily) {
        formatBilingualText(
            text = text,
            isArabicMode = isArabic,
            latinFamily = fontFamily ?: OutfitFamily,
            arabicFamily = NotoArabicFamily
        )
    }

    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = if (isArabic) 0.sp else letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout ?: {},
        style = style
    )
}
