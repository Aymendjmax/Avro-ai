package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNavigationEffectsTest {
    @Test
    fun drawerComposerDismissThresholdIsStrictlyPastHalf() {
        assertFalse(drawerPastComposerDismissThreshold(0f))
        assertFalse(drawerPastComposerDismissThreshold(0.5f))
        assertTrue(drawerPastComposerDismissThreshold(Math.nextUp(0.5f)))
        assertTrue(drawerPastComposerDismissThreshold(1f))
    }

    @Test
    fun drawerNavigationObservesOneDistinctThresholdProjection() {
        val source = sourceFile("ChatAppInteractionEffects.kt")
        val navigation = source
            .substringAfter("internal fun ChatNavigationEffects(")
            .substringBefore("internal fun SendAcceptedHapticBindingEffect(")

        assertTrue(
            navigation.contains(
                "snapshotFlow { drawerPastComposerDismissThreshold(drawerState.progress) }",
            ),
        )
        assertTrue(navigation.contains(".distinctUntilChanged()"))
        assertFalse(navigation.contains("drawerState.isVisible"))
    }

    @Test
    fun drawerOpenActionDoesNotBypassProgressThreshold() {
        val source = sourceFile("ChatApp.kt")
        val openDrawer = source
            .substringAfter("onOpenDrawer = {")
            .substringBefore("onSearchQueryChange =")

        assertTrue(openDrawer.contains("drawerState.toggle(motionPolicy)"))
        assertFalse(openDrawer.contains("clearFocus()"))
    }

    private fun sourceFile(name: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(
                directory,
                "app/src/main/java/com/newoether/agora/ui/chat/$name",
            )
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            directory = directory.parentFile ?: error("Unable to locate $name")
        }
    }
}
