package com.newoether.agora.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxPathResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootfsDir: File
    private lateinit var homeMountDir: File
    private lateinit var sharedDir: File
    private lateinit var resolver: SandboxPathResolver

    @Before
    fun setUp() {
        rootfsDir = tempFolder.newFolder("rootfs")
        homeMountDir = tempFolder.newFolder("home")
        sharedDir = tempFolder.newFolder("shared")

        resolver = SandboxPathResolver(
            rootfsDir = rootfsDir,
            homeMountDir = homeMountDir,
            homeMountPath = "/home/agora",
            sharedStorageMountPath = "/mnt/shared",
            sharedStorageHostDir = { sharedDir },
        )
    }

    @Test
    fun testResolvesHomeUploadsToHomeMountDir() {
        val resolved = resolver.resolveSandboxPath("/home/uploads/chat-123/file.png")
        assertEquals("/home", resolved.virtualRoot)
        assertEquals(homeMountDir.canonicalPath, resolved.physicalRoot.canonicalPath)
        assertEquals(
            File(homeMountDir, "uploads/chat-123/file.png").canonicalPath,
            resolved.file.canonicalPath,
        )
    }

    @Test
    fun testResolvesCustomHomeMountPath() {
        val resolved = resolver.resolveSandboxPath("/home/agora/workspace/project.kt")
        assertEquals("/home/agora", resolved.virtualRoot)
        assertEquals(homeMountDir.canonicalPath, resolved.physicalRoot.canonicalPath)
        assertEquals(
            File(homeMountDir, "workspace/project.kt").canonicalPath,
            resolved.file.canonicalPath,
        )
    }

    @Test
    fun testResolvesSharedStorageMountPath() {
        val resolved = resolver.resolveSandboxPath("/mnt/shared/DCIM/photo.jpg")
        assertEquals("/mnt/shared", resolved.virtualRoot)
        assertEquals(sharedDir.canonicalPath, resolved.physicalRoot.canonicalPath)
        assertEquals(
            File(sharedDir, "DCIM/photo.jpg").canonicalPath,
            resolved.file.canonicalPath,
        )
    }

    @Test
    fun testResolvesRootfsSystemPaths() {
        val resolved = resolver.resolveSandboxPath("/etc/apk/repositories")
        assertEquals("/", resolved.virtualRoot)
        assertEquals(rootfsDir.canonicalPath, resolved.physicalRoot.canonicalPath)
        assertEquals(
            File(rootfsDir, "etc/apk/repositories").canonicalPath,
            resolved.file.canonicalPath,
        )
    }

    @Test
    fun testPathTraversalIsRejected() {
        try {
            resolver.resolveSandboxPath("/home/uploads/../../etc/shadow")
            fail("Expected IllegalArgumentException for path traversal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Path traversal") == true)
        }

        try {
            resolver.resolveSandboxPath("/mnt/shared/../../../etc/passwd")
            fail("Expected IllegalArgumentException for path traversal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Path traversal") == true)
        }
    }

    @Test
    fun testWalkVirtualFiles() {
        resolver.ensureSandboxMountTargets()
        val uploadDir = File(homeMountDir, "uploads/chat_a").apply { mkdirs() }
        File(uploadDir, "doc.pdf").writeText("content")
        File(uploadDir, "image.png").writeText("png")

        val files = mutableListOf<String>()
        resolver.walkVirtualFiles(
            dir = homeMountDir,
            result = files,
            physicalRootAbsPath = homeMountDir.canonicalPath,
            virtualRoot = "/home",
            remaining = -1,
        )

        assertTrue(files.any { it.endsWith("/uploads/chat_a/doc.pdf") })
        assertTrue(files.any { it.endsWith("/uploads/chat_a/image.png") })
        assertTrue(files.any { it.endsWith("/notes/INDEX.md") })
    }
}
