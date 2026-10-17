package com.newoether.agora.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role

/*
 * Option rows in dialogs and bottom sheets share the dropdown item highlight: the press and hover
 * ripple is clipped to the same 24dp corner instead of filling a square.
 */

/** Highlight shape for option rows; identical to the dropdown item highlight. */
internal val OPTION_HIGHLIGHT_SHAPE = DROPDOWN_ITEM_SHAPE

/** Side gap between a bottom sheet's edge and its option rows' highlight, as in dropdown menus. */
internal val SHEET_OPTION_INSET = DROPDOWN_ITEM_INSET

/**
 * Clickable option row whose highlight is clipped to [OPTION_HIGHLIGHT_SHAPE]. Use inside dialogs,
 * whose content already sits away from the container edge.
 */
fun Modifier.optionClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = clip(OPTION_HIGHLIGHT_SHAPE).clickable(enabled = enabled, role = role, onClick = onClick)

/**
 * [optionClickable] for rows that span a bottom sheet's full width: the highlight is also inset by
 * [SHEET_OPTION_INSET] on both sides, so callers reduce their own horizontal padding by the same
 * amount to keep the row content where it was.
 */
fun Modifier.sheetOptionClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = padding(horizontal = SHEET_OPTION_INSET).optionClickable(enabled, role, onClick)
