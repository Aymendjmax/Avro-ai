package com.newoether.agora.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemorySkillManagerPersistenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun contentOnlyEditsDoNotReadOrRewriteMetadata() {
        harnesses("content-only").forEach { harness ->
            harness.create("entry.md", "before", "description")
            harness.metaFile.writeText("not-json")

            harness.replace("entry.md", "after")
            harness.patch("entry.md", "after", "final")

            assertEquals("final", harness.read("entry.md"))
            assertEquals("not-json", harness.metaFile.readText())
            assertEquals(3L, harness.revision)
        }
    }

    @Test
    fun renameConflictDoesNotModifySourceContentOrMetadata() {
        harnesses("rename-conflict").forEach { harness ->
            harness.create("source.md", "source", "source description")
            harness.create("target.md", "target", "target description")
            val metadata = harness.metaFile.readText()
            val revision = harness.revision

            assertThrows(IllegalArgumentException::class.java) {
                harness.replaceAndRename("source.md", "changed", "target.md")
            }

            assertEquals("source", harness.read("source.md"))
            assertEquals("target", harness.read("target.md"))
            assertEquals("source description", harness.description("source.md"))
            assertEquals("target description", harness.description("target.md"))
            assertEquals(metadata, harness.metaFile.readText())
            assertEquals(revision, harness.revision)
            assertNoTransactionFiles(harness.metaFile)
        }
    }

    @Test
    fun malformedMetadataBlocksCatalogMutationsWithoutOverwritingAnything() {
        harnesses("fail-closed").forEach { harness ->
            harness.create("entry.md", "body", "description")
            harness.metaFile.writeText("not-json")
            val revision = harness.revision

            assertThrows(IllegalStateException::class.java) {
                harness.describe("entry.md", "changed")
            }
            assertThrows(IllegalStateException::class.java) {
                harness.rename("entry.md", "renamed.md")
            }
            assertThrows(IllegalStateException::class.java) {
                harness.delete("entry.md")
            }

            assertEquals("body", harness.read("entry.md"))
            assertFalse(harness.exists("renamed.md"))
            assertEquals("not-json", harness.metaFile.readText())
            assertEquals(revision, harness.revision)
        }
    }

    @Test
    fun renameDescriptionAndDeleteKeepFilesAndMetadataConsistent() {
        harnesses("consistency").forEach { harness ->
            harness.create("entry.md", "body", "description")
            assertEquals(1L, harness.revision)

            harness.rename("entry.md", "renamed.md")
            assertFalse(harness.exists("entry.md"))
            assertEquals("description", harness.description("renamed.md"))
            assertEquals(
                mapOf("renamed.md" to "description"),
                metadata(harness.metaFile),
            )
            assertEquals(2L, harness.revision)

            harness.describe("renamed.md", "")
            assertEquals("", harness.description("renamed.md"))
            assertTrue(metadata(harness.metaFile).isEmpty())
            assertEquals(3L, harness.revision)

            harness.describe("renamed.md", "restored")
            assertEquals(4L, harness.revision)
            harness.delete("renamed.md")
            assertFalse(harness.exists("renamed.md"))
            assertTrue(metadata(harness.metaFile).isEmpty())
            assertEquals(5L, harness.revision)
            assertNoTransactionFiles(harness.metaFile)
        }
    }

    @Test
    fun noOpDescriptionDoesNotRewriteMetadataOrAdvanceRevision() {
        harnesses("no-op").forEach { harness ->
            harness.create("entry.md", "body", "description")
            val metadata = harness.metaFile.readText()
            val revision = harness.revision

            harness.describe("entry.md", "description")

            assertEquals(metadata, harness.metaFile.readText())
            assertEquals(revision, harness.revision)
        }
    }

    @Test
    fun invalidMetadataImportIsRejectedWithoutReplacingExistingMetadata() {
        harnesses("invalid-import").forEach { harness ->
            harness.create("entry.md", "body", "description")
            val metadata = harness.metaFile.readText()
            val revision = harness.revision

            assertThrows(IllegalArgumentException::class.java) {
                harness.saveMetaJson("not-json")
            }

            assertEquals(metadata, harness.metaFile.readText())
            assertEquals("description", harness.description("entry.md"))
            assertEquals(revision, harness.revision)
            assertNoTransactionFiles(harness.metaFile)
        }
    }

    @Test
    fun interruptedMetadataCommitRestoresBackupAndDiscardsTemporaryFile() {
        harnesses("recovery").forEach { harness ->
            harness.create("entry.md", "body", "description")
            val backup = File(harness.metaFile.parentFile, harness.metaFile.name + ".bak")
            val temporary = File(harness.metaFile.parentFile, harness.metaFile.name + ".tmp")
            assertTrue(harness.metaFile.renameTo(backup))
            temporary.writeText("partial")

            assertEquals("description", harness.description("entry.md"))
            assertTrue(harness.metaFile.isFile)
            assertFalse(backup.exists())
            assertFalse(temporary.exists())
        }
    }

    private fun metadata(file: File): Map<String, String> =
        json.decodeFromString(file.readText())

    private fun assertNoTransactionFiles(metaFile: File) {
        assertFalse(File(metaFile.parentFile, metaFile.name + ".tmp").exists())
        assertFalse(File(metaFile.parentFile, metaFile.name + ".bak").exists())
    }

    private fun harnesses(prefix: String): List<CatalogHarness> {
        val memoryRoot = temporaryFolder.newFolder("${prefix}_memory")
        val skillRoot = temporaryFolder.newFolder("${prefix}_skill")
        val memoryManager = MemoryManager(context(memoryRoot))
        val skillManager = SkillManager(context(skillRoot))
        return listOf(
            object : CatalogHarness {
                override val metaFile = File(memoryRoot, "memory_db/memory_meta.json")
                override val revision: Long get() = memoryManager.catalogRevision.value
                override fun create(name: String, content: String, description: String) {
                    memoryManager.createFile(name, content, description)
                }
                override fun read(name: String): String = memoryManager.readFile(name)
                override fun replace(name: String, content: String) {
                    memoryManager.editFile(name = name, content = content)
                }
                override fun replaceAndRename(name: String, content: String, newName: String) {
                    memoryManager.editFile(name = name, content = content, newName = newName)
                }
                override fun patch(name: String, oldString: String, newString: String) {
                    memoryManager.editFile(
                        name = name,
                        oldString = oldString,
                        newString = newString,
                    )
                }
                override fun rename(name: String, newName: String) {
                    memoryManager.editFile(name = name, newName = newName)
                }
                override fun describe(name: String, description: String) {
                    memoryManager.editFile(name = name, description = description)
                }
                override fun description(name: String): String =
                    memoryManager.getDescription(name)
                override fun delete(name: String) {
                    memoryManager.deleteFile(name)
                }
                override fun exists(name: String): Boolean =
                    File(memoryRoot, "memory_db/$name").exists()
                override fun saveMetaJson(value: String) {
                    memoryManager.saveMetaJson(value)
                }
            },
            object : CatalogHarness {
                override val metaFile = File(skillRoot, "skill_db/skill_meta.json")
                override val revision: Long get() = skillManager.catalogRevision.value
                override fun create(name: String, content: String, description: String) {
                    skillManager.createFile(name, content, description)
                }
                override fun read(name: String): String = skillManager.readFile(name)
                override fun replace(name: String, content: String) {
                    skillManager.editFile(name = name, content = content)
                }
                override fun replaceAndRename(name: String, content: String, newName: String) {
                    skillManager.editFile(name = name, content = content, newName = newName)
                }
                override fun patch(name: String, oldString: String, newString: String) {
                    skillManager.editFile(
                        name = name,
                        oldString = oldString,
                        newString = newString,
                    )
                }
                override fun rename(name: String, newName: String) {
                    skillManager.editFile(name = name, newName = newName)
                }
                override fun describe(name: String, description: String) {
                    skillManager.editFile(name = name, description = description)
                }
                override fun description(name: String): String =
                    skillManager.getDescription(name)
                override fun delete(name: String) {
                    skillManager.deleteFile(name)
                }
                override fun exists(name: String): Boolean =
                    File(skillRoot, "skill_db/$name").exists()
                override fun saveMetaJson(value: String) {
                    skillManager.saveMetaJson(value)
                }
            },
        )
    }

    private fun context(filesDir: File): Context = mockk {
        every { this@mockk.filesDir } returns filesDir
    }

    private interface CatalogHarness {
        val metaFile: File
        val revision: Long
        fun create(name: String, content: String, description: String)
        fun read(name: String): String
        fun replace(name: String, content: String)
        fun replaceAndRename(name: String, content: String, newName: String)
        fun patch(name: String, oldString: String, newString: String)
        fun rename(name: String, newName: String)
        fun describe(name: String, description: String)
        fun description(name: String): String
        fun delete(name: String)
        fun exists(name: String): Boolean
        fun saveMetaJson(value: String)
    }
}
