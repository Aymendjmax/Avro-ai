package com.newoether.agora.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellConfirmationNotificationSourceContractTest {
    @Test
    fun `receiver accepts only current prompt decisions and always finishes async work`() {
        val receiver = source("service/ShellConfirmationReceiver.kt")
        assertTrue(receiver.contains("ACTION_ALLOW -> true"))
        assertTrue(receiver.contains("ACTION_DENY -> false"))
        assertTrue(receiver.contains("else -> return"))
        assertTrue(receiver.contains("promptId == NO_PROMPT"))
        assertTrue(receiver.contains("notificationSessionId == sessionId"))
        assertTrue(receiver.contains("withTimeoutOrNull(CONTAINER_WAIT_MS)"))
        assertTrue(receiver.contains("const val CONTAINER_WAIT_MS = 5_000L"))
        assertTrue(Regex("finally \\{\\s*pendingResult\\.finish\\(\\)").containsMatchIn(receiver))
        assertEquals(1, Regex("pendingResult\\.finish\\(\\)").findAll(receiver).count())
    }

    @Test
    fun `notification intents bind both session and prompt and receiver is private`() {
        val notifier = source("service/ShellConfirmationNotifier.kt")
        assertTrue(notifier.contains("setData(android.net.Uri.parse(\"agora-shell-confirm://\$sessionId/\$promptId\"))"))
        assertTrue(notifier.contains("putExtra(EXTRA_SESSION_ID, sessionId)"))
        assertTrue(notifier.contains("putExtra(EXTRA_PROMPT_ID, promptId)"))
        assertTrue(notifier.contains("PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT"))

        val manifest = File(repositoryRoot(), "app/src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val receivers = document.getElementsByTagName("receiver")
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val match = (0 until receivers.length)
            .map { receivers.item(it) }
            .single {
                it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue ==
                    ".service.ShellConfirmationReceiver"
            }
        assertFalse(match.attributes.getNamedItemNS(androidNamespace, "exported").nodeValue.toBoolean())
    }

    private fun source(relativePath: String) = File(
        repositoryRoot(),
        "app/src/main/java/com/newoether/agora/$relativePath",
    ).readText()

    private fun repositoryRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            if (File(directory, "app/src/main/AndroidManifest.xml").isFile) return directory
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate repository root")
    }
}
