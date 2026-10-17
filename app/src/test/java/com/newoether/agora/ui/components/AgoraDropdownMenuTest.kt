package com.newoether.agora.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgoraDropdownMenuTest {
    private val density = Density(1f)
    private val itemSize = Size(200f, 48f)

    @Test
    fun menuAndItemShareTheTwentyFourDpCorner() {
        assertEquals(24.dp, DROPDOWN_CORNER)
        assertEquals(24f, DROPDOWN_MENU_SHAPE.topStart.toPx(itemSize, density), 0.001f)
        // Half the 48dp item height, so the highlight is a capsule.
        assertEquals(itemSize.height / 2, DROPDOWN_ITEM_SHAPE.topStart.toPx(itemSize, density), 0.001f)
    }

    @Test
    fun appUsesOnlyTheSharedDropdownWrappers() {
        val root = File("src/main/java")
        val raw = Regex("""(?<![\w.])(DropdownMenu|DropdownMenuItem|ExposedDropdownMenu)\(""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AgoraDropdownMenu.kt" }
            .filter { raw.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertTrue("Use AgoraDropdownMenu / AgoraDropdownMenuItem in: $offenders", offenders.isEmpty())
    }
}
