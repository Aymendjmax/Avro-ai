package com.newoether.agora.remote

import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteConnectionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val token = "ab".repeat(32)
    private fun store(file: File) = RemoteConnectionStore(file, encrypt = { plaintext ->
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        "enc:v1:" + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray()))
    }, decrypt = { stored ->
        val bytes = Base64.getDecoder().decode(stored.removePrefix("enc:v1:"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    })

    @Test fun aNewStoreRestoresEncryptedConnectionsAndRemoval() = runBlocking {
        val file = File(temporary.root, "connections.json")
        store(file).save(RemoteConnection("Computer", "http://computer:7435/", token))
        assertFalse(file.readText().contains(token))
        val restored = store(file).load().single()
        assertEquals("Computer", restored.name)
        assertEquals(token, restored.token)
        store(file).remove(restored.address)
        assertTrue(store(file).load().isEmpty())
    }

    @Test fun configuredNameEditSurvivesRestartWithoutLosingViewedHistory() = runBlocking {
        val file = File(temporary.root, "connections.json")
        store(file).save(RemoteConnection("My computer", "http://computer/", token))
        store(file).markViewed("http://computer/", "session", "turn")
        store(file).save(RemoteConnection("Work computer", "http://computer/", token), "http://computer/")
        val restored = store(file).load().single()
        assertEquals("Work computer", restored.name)
        assertEquals(token, restored.token)
        assertEquals("turn", restored.viewedTurns["session"])
        assertFalse(file.readText().contains(token))
    }

    @Test fun failedEncryptionCannotReplacePreviouslySavedConnections() = runBlocking {
        val file = File(temporary.root, "connections.json")
        store(file).save(RemoteConnection("Saved", "http://saved/", token))
        val before = file.readText()
        val failing = RemoteConnectionStore(file, encrypt = { it }, decrypt = { token })
        try { failing.save(RemoteConnection("Unsaved", "http://new/", token)); fail() }
        catch (_: RemoteStorageException) { }
        assertEquals(before, file.readText())
        assertEquals("Saved", store(file).load().single().name)
        assertFalse(File(temporary.root, "connections.json.new").exists())
    }

    @Test fun unreadableStorageIsPreservedWithoutOverwritingIt() = runBlocking {
        val file = temporary.newFile("connections.json").apply { writeText("broken") }
        try { store(file).save(RemoteConnection("New", "http://new/", token)); fail() }
        catch (_: RemoteStorageException) { }
        assertEquals("broken", file.readText())
        store(file).run {
            file.writeText("[{\"name\":\"Plain\",\"address\":\"http://plain/\",\"token\":\"$token\"}]")
            try { load(); fail() } catch (_: RemoteStorageException) { }
        }
    }

    @Test fun overlappingOwnersDoNotLoseEachOthersConnections() = runBlocking {
        val file = File(temporary.root, "connections.json")
        (1..8).map { index -> async {
            store(file).save(RemoteConnection("Device $index", "http://device$index/", token))
        } }.awaitAll()
        assertEquals(8, store(file).load().size)
    }

    @Test fun editingAtomicallyReplacesAddressAndTokenWithoutAddingADevice() = runBlocking {
        val file = File(temporary.root, "connections.json")
        store(file).save(RemoteConnection("Old", "http://old/", token))
        val changedToken = "cd".repeat(32)
        store(file).save(RemoteConnection("New", "http://new/", changedToken), "http://old/")
        val restored = store(file).load().single()
        assertEquals("http://new/", restored.address)
        assertEquals(changedToken, restored.token)
        assertFalse(file.readText().contains(changedToken))
    }

    @Test fun failedEditAndAddressCollisionPreserveBothSavedConnections() = runBlocking {
        val file = File(temporary.root, "connections.json")
        store(file).save(RemoteConnection("One", "http://one/", token))
        store(file).save(RemoteConnection("Two", "http://two/", token))
        val before = file.readText()
        try {
            store(file).save(RemoteConnection("Changed", "http://two/", token), "http://one/"); fail()
        } catch (_: FiloConfigurationException) { }
        assertEquals(before, file.readText())
        val failing = RemoteConnectionStore(file, encrypt = { it }, decrypt = { token })
        try {
            failing.save(RemoteConnection("Changed", "http://new/", token), "http://one/"); fail()
        } catch (_: RemoteStorageException) { }
        assertEquals(before, file.readText())
    }

    @Test fun viewedCompletionsSurviveRestartAndCredentialEditWithoutRestoringDeletedDevices() = runBlocking {
        val file = File(temporary.root, "connections.json")
        store(file).save(RemoteConnection("Computer", "http://computer/", token))
        store(file).markViewed("http://computer/", "session", "first")
        assertEquals("first", store(file).load().single().viewedTurns["session"])
        store(file).save(RemoteConnection("Renamed", "http://computer/", token), "http://computer/")
        assertEquals("first", store(file).load().single().viewedTurns["session"])
        store(file).markViewed("http://computer/", "session", "second")
        assertEquals("second", store(file).load().single().viewedTurns["session"])
        assertFalse(file.readText().contains(token))
        store(file).remove("http://computer/")
        store(file).markViewed("http://computer/", "session", "late")
        assertTrue(store(file).load().isEmpty())
    }
}
