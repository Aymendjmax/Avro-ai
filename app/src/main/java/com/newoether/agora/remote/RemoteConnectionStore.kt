package com.newoether.agora.remote

import com.newoether.agora.util.SecretCrypto
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal class RemoteConnection(
    val name: String, val address: String, val token: String,
    val viewedTurns: Map<String, String> = emptyMap(),
)

internal class RemoteStorageException : IOException("Remote connection storage unavailable")

/** The only durable owner of Remote credentials; callers publish changes after commit. */
internal class RemoteConnectionStore(
    private val file: File,
    private val encrypt: (String) -> String = SecretCrypto::encrypt,
    private val decrypt: (String) -> String = SecretCrypto::decrypt,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): List<RemoteConnection> = withContext(Dispatchers.IO) {
        mutex.withLock { read() }
    }

    suspend fun save(connection: RemoteConnection, previousAddress: String? = null): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = read()
            if (previousAddress != null && saved.none { it.address == previousAddress }) throw RemoteStorageException()
            if (previousAddress != null && previousAddress != connection.address &&
                saved.any { it.address == connection.address }) throw FiloConfigurationException()
            val replaced = previousAddress ?: connection.address
            write(if (saved.any { it.address == replaced }) {
                saved.map { if (it.address == replaced) RemoteConnection(
                    connection.name, connection.address, connection.token, it.viewedTurns + connection.viewedTurns,
                ) else it }
            } else saved + connection)
        }
    }

    suspend fun markViewed(address: String, sessionId: String, turnId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = read()
            if (saved.none { it.address == address }) return@withLock
            write(saved.map { if (it.address == address) RemoteConnection(
                it.name, it.address, it.token, it.viewedTurns + (sessionId to turnId),
            ) else it })
        }
    }

    suspend fun remove(address: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { write(read().filterNot { it.address == address }) }
    }

    private fun read(): List<RemoteConnection> = storageOperation {
        if (!file.exists()) return@storageOperation emptyList()
        json.decodeFromString<List<RemoteConnection>>(file.readText()).map {
            if (!SecretCrypto.isEncrypted(it.token)) throw RemoteStorageException()
            val token = decrypt(it.token)
            if (!token.matches(Regex("[a-fA-F0-9]{64}"))) throw RemoteStorageException()
            RemoteConnection(it.name, it.address, token, it.viewedTurns)
        }.also { entries ->
            if (entries.map { it.address }.distinct().size != entries.size) throw RemoteStorageException()
        }
    }

    private fun write(connections: List<RemoteConnection>) = storageOperation {
        val encoded = json.encodeToString(connections.map {
            val ciphertext = encrypt(it.token)
            if (!SecretCrypto.isEncrypted(ciphertext)) throw RemoteStorageException()
            RemoteConnection(it.name, it.address, ciphertext, it.viewedTurns)
        }).toByteArray(Charsets.UTF_8)
        val parent = file.parentFile ?: throw RemoteStorageException()
        Files.createDirectories(parent.toPath())
        val pending = File(parent, "${file.name}.new")
        try {
            FileOutputStream(pending).use { output -> output.write(encoded); output.fd.sync() }
            Files.move(pending.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending.toPath())
        }
    }

    private inline fun <T> storageOperation(block: () -> T): T = try { block() }
    catch (_: Exception) { throw RemoteStorageException() }

    companion object {
        // Serializes file transactions across overlapping Activity/owner lifetimes.
        private val mutex = Mutex()
    }
}
