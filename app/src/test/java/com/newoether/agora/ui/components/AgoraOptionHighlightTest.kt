package com.newoether.agora.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AgoraOptionHighlightTest {
    @Test
    fun optionRowsShareTheDropdownHighlight() {
        assertEquals(
            24f,
            OPTION_HIGHLIGHT_SHAPE.topStart.toPx(Size(300f, 72f), Density(1f)),
            0.001f,
        )
        assertEquals(8.dp, SHEET_OPTION_INSET)
    }

    @Test
    fun dialogAndSheetOptionRowsUseTheSharedHighlight() {
        val ui = File("src/main/java/com/newoether/agora/ui")
        // File -> (dialog rows, sheet rows) that must use the shared highlight.
        val expected = mapOf(
            "components/SystemPromptPickerDialog.kt" to (2 to 0),
            "settings/datacontrol/SettingsDataControlPage.kt" to (2 to 0),
            "settings/SettingsImageGenPage.kt" to (2 to 0),
            "settings/SettingsModelsPage.kt" to (1 to 0),
            "settings/SettingsTitleGenPage.kt" to (2 to 0),
            "settings/SettingsTranscriptionPage.kt" to (2 to 0),
            "settings/SettingsWebSearchPage.kt" to (1 to 0),
            // The Tasks model picker's ChoiceRow joins the existing option row (UI-21).
            "tasks/TaskEditorSupportingComponents.kt" to (2 to 0),
            "chat/ImageActions.kt" to (0 to 1),
            "settings/SettingsPromptsPage.kt" to (0 to 2),
            "settings/SettingsSkillsPage.kt" to (0 to 2),
            "settings/SystemPromptEditorPage.kt" to (0 to 1),
        )
        expected.forEach { (path, counts) ->
            val source = File(ui, path).readLines().filterNot { it.startsWith("import ") }.joinToString("\n")
            assertEquals(path, counts.first, Regex("""\.optionClickable\b""").findAll(source).count())
            assertEquals(path, counts.second, Regex("""\.sheetOptionClickable\b""").findAll(source).count())
        }
    }
}
