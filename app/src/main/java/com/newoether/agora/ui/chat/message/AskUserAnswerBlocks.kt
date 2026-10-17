package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import com.newoether.agora.model.MessageSource

/**
 * One text block of an ask_user bubble. [storedStart] is where [text] begins in the stored message
 * text, which is what conversation search indexes; the skipped-answer label has no stored
 * counterpart, so it is never highlighted.
 */
internal data class AskUserBlock(
    val text: String,
    val isQuestion: Boolean,
    val storedStart: Int?,
)

/** Question/answer blocks in order, with their offsets in [MessageSource.askUserReadableText]. */
internal fun askUserBlocks(source: MessageSource, unansweredLabel: String): List<List<AskUserBlock>> {
    var offset = 0
    return source.askUser.mapIndexed { index, item ->
        if (index > 0) offset += 2
        val question = AskUserBlock(item.question, isQuestion = true, storedStart = offset)
        offset += item.question.length + 1
        val stored = item.answer ?: MessageSource.NO_ANSWER
        val answer = AskUserBlock(
            text = item.answer ?: unansweredLabel,
            isQuestion = false,
            storedStart = offset.takeIf { item.answer != null },
        )
        offset += stored.length
        listOf(question, answer)
    }
}

private val NO_TRIM = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None)
private const val METRICS_SAMPLE = "Ag测"

/**
 * The ask_user bubble body, laid out as separate blocks so a wrapped question can use its own
 * tighter [ASK_USER_QUESTION_LINE_HEIGHT] while every other gap stays what one [bodyStyle] text
 * would give: the space above a question and below its last line is padded back to what a
 * [bodyStyle] line would reserve, measured from the real font, and groups are separated by one
 * empty [bodyStyle] line. The first block keeps its top trimmed and the last its bottom, as a
 * single text would.
 */
@Composable
internal fun AskUserAnswerBlocks(
    source: MessageSource,
    unansweredLabel: String,
    bodyStyle: TextStyle,
    textColor: Color,
    searchHighlight: SearchHighlightSpec?,
) {
    val dimColor = textColor.copy(alpha = textColor.alpha * ASK_USER_DIM_ALPHA)
    val questionStyle = bodyStyle.copy(
        fontSize = ASK_USER_QUESTION_FONT_SIZE,
        lineHeight = ASK_USER_QUESTION_LINE_HEIGHT,
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // How much less room a tight question line leaves above and below its glyphs than a body line.
    val (extraAbove, extraBelow) = remember(measurer, density, bodyStyle, questionStyle) {
        fun layout(style: TextStyle) =
            measurer.measure(METRICS_SAMPLE, style.copy(fontSize = questionStyle.fontSize, lineHeightStyle = NO_TRIM))
        val wide = layout(bodyStyle)
        val tight = layout(questionStyle)
        val above = (wide.getLineBaseline(0) - wide.getLineTop(0)) - (tight.getLineBaseline(0) - tight.getLineTop(0))
        val below = (wide.getLineBottom(0) - wide.getLineBaseline(0)) - (tight.getLineBottom(0) - tight.getLineBaseline(0))
        with(density) { above.coerceAtLeast(0f).toDp() to below.coerceAtLeast(0f).toDp() }
    }
    val emptyLine: Dp = with(density) { bodyStyle.lineHeight.toDp() }
    val groups = remember(source, unansweredLabel) { askUserBlocks(source, unansweredLabel) }
    Column {
        groups.forEachIndexed { groupIndex, (question, answer) ->
            val first = groupIndex == 0
            val last = groupIndex == groups.lastIndex
            if (!first) Spacer(Modifier.height(emptyLine + extraAbove))
            AskUserBlockText(
                block = question,
                style = questionStyle.copy(lineHeightStyle = trim(top = first, bottom = false)),
                color = dimColor,
                searchHighlight = searchHighlight,
            )
            Spacer(Modifier.height(extraBelow))
            AskUserBlockText(
                block = answer,
                style = bodyStyle.copy(lineHeightStyle = trim(top = false, bottom = last)),
                color = if (answer.storedStart == null) dimColor else textColor,
                searchHighlight = searchHighlight,
            )
        }
    }
}

private fun trim(top: Boolean, bottom: Boolean) = LineHeightStyle(
    LineHeightStyle.Alignment.Proportional,
    when {
        top && bottom -> LineHeightStyle.Trim.Both
        top -> LineHeightStyle.Trim.FirstLineTop
        bottom -> LineHeightStyle.Trim.LastLineBottom
        else -> LineHeightStyle.Trim.None
    },
)

@Composable
private fun AskUserBlockText(
    block: AskUserBlock,
    style: TextStyle,
    color: Color,
    searchHighlight: SearchHighlightSpec?,
) {
    SearchHighlightedPlainText(
        text = block.text,
        style = style,
        color = color,
        spec = block.storedStart?.let { start -> searchHighlight?.forSourceSlice(start, block.text.length) },
    )
}
