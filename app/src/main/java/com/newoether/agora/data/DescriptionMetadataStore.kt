package com.newoether.agora.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

internal class DescriptionMetadataStore(
    private val metadataFile: File,
    private val json: Json,
) {
    private val temporaryFile = File(metadataFile.parentFile, metadataFile.name + ".tmp")
    private val backupFile = File(metadataFile.parentFile, metadataFile.name + ".bak")

    @Synchronized
    fun read(): MutableMap<String, String> {
        recoverInterruptedWrite()
        if (!metadataFile.exists()) return mutableMapOf()
        return try {
            json.decodeFromString<Map<String, String>>(metadataFile.readText()).toMutableMap()
        } catch (error: Exception) {
            throw IllegalStateException(
                "Unable to parse ${metadataFile.name}; metadata was not modified",
                error,
            )
        }
    }

    @Synchronized
    fun readJson(): String {
        recoverInterruptedWrite()
        return if (metadataFile.exists()) metadataFile.readText() else "{}"
    }

    @Synchronized
    fun replaceJson(jsonString: String) {
        val metadata = try {
            json.decodeFromString<Map<String, String>>(jsonString)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid metadata JSON", error)
        }
        write(metadata)
    }

    @Synchronized
    fun write(metadata: Map<String, String>) {
        atomicWrite(json.encodeToString<Map<String, String>>(metadata.toSortedMap()))
    }

    private fun atomicWrite(content: String) {
        recoverInterruptedWrite()
        val parent = requireNotNull(metadataFile.parentFile)
        require(parent.isDirectory || parent.mkdirs()) {
            "Unable to create metadata directory: ${parent.absolutePath}"
        }
        require(!temporaryFile.exists() || temporaryFile.delete()) {
            "Unable to remove stale metadata temporary file"
        }
        temporaryFile.outputStream().use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }

        var originalMoved = false
        try {
            if (metadataFile.exists()) {
                require(!backupFile.exists() || backupFile.delete()) {
                    "Unable to remove stale metadata backup"
                }
                require(metadataFile.renameTo(backupFile)) {
                    "Unable to stage existing ${metadataFile.name}"
                }
                originalMoved = true
            }
            if (!temporaryFile.renameTo(metadataFile)) {
                throw IOException("Unable to commit ${metadataFile.name}")
            }
            if (backupFile.exists()) backupFile.delete()
        } catch (error: Exception) {
            if (originalMoved && !metadataFile.exists() && !backupFile.renameTo(metadataFile)) {
                error.addSuppressed(IOException("Unable to restore ${metadataFile.name}"))
            }
            throw error
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    private fun recoverInterruptedWrite() {
        if (backupFile.exists()) {
            if (metadataFile.exists()) {
                backupFile.delete()
            } else if (!backupFile.renameTo(metadataFile)) {
                throw IOException("Unable to restore ${metadataFile.name} from backup")
            }
        }
        if (temporaryFile.exists()) temporaryFile.delete()
    }
}
