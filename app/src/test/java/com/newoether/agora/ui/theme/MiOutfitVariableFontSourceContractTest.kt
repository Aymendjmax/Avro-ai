package com.newoether.agora.ui.theme

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiOutfitVariableFontSourceContractTest {
    @Test
    fun `minimum Android version supports variable font weights`() {
        val build = sourceFile("app/build.gradle.kts").readText()
        val minimum = Regex("minSdk\\s*=\\s*(\\d+)").find(build)!!.groupValues[1].toInt()
        assertEquals(26, minimum)
    }

    @Test
    fun `default type family uses one variable font with five explicit weights`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/theme/Type.kt",
        ).readText()
        val expectedWeights = listOf(
            "ExtraLight",
            "Light",
            "Normal",
            "Medium",
            "Bold",
        )

        assertTrue(source.contains("resId = R.font.mioutfit_variable"))
        assertTrue(source.contains("variationSettings = FontVariation.Settings("))
        assertTrue(source.contains("FontVariation.weight(weight.weight)"))
        expectedWeights.forEach { weight ->
            assertTrue(source.contains("miOutfitFont(FontWeight.$weight)"))
        }
        assertEquals(5, Regex("miOutfitFont\\(FontWeight\\.").findAll(source).count())

        listOf("extralight", "light", "regular", "medium", "bold").forEach { suffix ->
            assertFalse(source.contains("mioutfit_$suffix"))
        }
    }

    @Test
    fun `font resources contain only the approved Mi Outfit variable font`() {
        val fontDirectory = sourceFile("app/src/main/res/font")
        val miOutfitFiles = fontDirectory.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("mioutfit_") }
            .sortedBy(File::getName)

        assertEquals(listOf("mioutfit_variable.ttf"), miOutfitFiles.map(File::getName))
        assertEquals(20_235_120L, miOutfitFiles.single().length())
        assertEquals(
            "498223B2A22892AFD8205CAF765CB45A423317DDF37C0CC7A1035AA9ABFB02C1",
            sha256(miOutfitFiles.single()),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun sourceFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::exists)?.let { return it }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
