package com.newoether.agora.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperOptionsPersistenceContractTest {
    @Test
    fun `developer feature keys remain stable`() {
        val schemaSource = File(
            locateMainSourceRoot(),
            "com/newoether/agora/data/SettingsPreferenceSchema.kt",
        ).readText()
        assertTrue(
            schemaSource.contains(
                "DEVELOPER_OPTIONS_ENABLED = booleanPreferencesKey(\"developer_options_enabled\")",
            ),
        )
        assertTrue(
            schemaSource.contains(
                "DEBUG_MODEL_ENABLED = booleanPreferencesKey(\"debug_model_enabled\")",
            ),
        )
    }

    @Test
    fun `developer features remain device local during portable archive and replace`() {
        val sourceRoot = locateMainSourceRoot()
        val portableSource = File(
            sourceRoot,
            "com/newoether/agora/data/PortableSettingsArchive.kt",
        ).readText()
        assertFalse(
            "Developer Options must not enter portable settings archives",
            portableSource.contains("developerOptionsEnabled"),
        )
        assertFalse(
            "Debug Model must not enter portable settings archives",
            portableSource.contains("debugModelEnabled"),
        )

        val managerSource = File(
            sourceRoot,
            "com/newoether/agora/data/SettingsManager.kt",
        ).readText()
        val resetEntry = managerSource
            .substringAfter("suspend fun resetPortableSettingsForImport()")
            .substringBefore("suspend fun invalidatePortableModelCaches")
        assertTrue(resetEntry.contains("clearPortableSettings(prefs)"))
        val resetBody = File(
            sourceRoot,
            "com/newoether/agora/data/PortableSettingsReset.kt",
        ).readText()
        assertFalse(
            "Replacing portable settings must preserve this installation's Developer Options gate",
            resetBody.contains("DEVELOPER_OPTIONS_ENABLED"),
        )
        assertFalse(
            "Replacing portable settings must preserve this installation's Debug Model gate",
            resetBody.contains("DEBUG_MODEL_ENABLED"),
        )
    }

    @Test
    fun `debug model is gated and reset in the developer mode edit`() {
        val managerSource = File(
            locateMainSourceRoot(),
            "com/newoether/agora/data/SettingsManager.kt",
        ).readText()
        val debugFlow = managerSource
            .substringAfter("val debugModelEnabled: Flow<Boolean>")
            .substringBefore("val shellEnabled")
        assertTrue(debugFlow.contains("preferences[DEVELOPER_OPTIONS_ENABLED] ?: false"))
        assertTrue(debugFlow.contains("preferences[DEBUG_MODEL_ENABLED] ?: false"))

        val developerSetter = managerSource
            .substringAfter("suspend fun saveDeveloperOptionsEnabled(enabled: Boolean)")
            .substringBefore("suspend fun saveDebugModelEnabled(enabled: Boolean)")
        assertEquals(1, Regex("context\\.dataStore\\.edit").findAll(developerSetter).count())
        assertTrue(developerSetter.contains("it[DEVELOPER_OPTIONS_ENABLED] = enabled"))
        assertTrue(developerSetter.contains("if (!enabled)"))
        assertTrue(developerSetter.contains("it[DEBUG_MODEL_ENABLED] = false"))

        val debugSetter = managerSource
            .substringAfter("suspend fun saveDebugModelEnabled(enabled: Boolean)")
            .substringBefore("suspend fun saveShellEnabled(enabled: Boolean)")
        assertTrue(
            debugSetter.contains(
                "enabled && (it[DEVELOPER_OPTIONS_ENABLED] ?: false)",
            ),
        )
    }

    private fun locateMainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(directory, "app/src/main/java"),
                File(directory, "src/main/java"),
            ).firstOrNull(File::isDirectory)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate the main Java source directory")
    }
}
