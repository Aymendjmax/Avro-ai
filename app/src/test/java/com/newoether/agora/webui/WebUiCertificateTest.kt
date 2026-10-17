package com.newoether.agora.webui

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.cert.X509Certificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiCertificateTest {
    private val dir: File = Files.createTempDirectory("webui-cert").toFile()

    private fun store() = WebUiCertificateStore(
        directory = dir,
        seal = { "sealed:" + it.reversed() },
        unseal = { it.removePrefix("sealed:").reversed() },
        addresses = { listOf(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("100.64.0.7")) },
    )

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun createsAServerCertificateForLocalhostAndTheCurrentAddresses() {
        val identity = store().loadOrCreate()
        val cert = identity.certificate
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertTrue(cert.subjectX500Principal.name.contains("CN=${WebUiCertificateStore.COMMON_NAME}"))
        assertEquals("EC", cert.publicKey.algorithm)
        cert.verify(cert.publicKey)
        assertEquals(-1, cert.basicConstraints)
        assertTrue(cert.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
        val names = cert.subjectAlternativeNames.map { it[1] as String }
        assertTrue(names.containsAll(listOf("localhost", "192.168.1.20", "100.64.0.7")))
        assertTrue(identity.keyStore.isKeyEntry(WebUiCertificateStore.ALIAS))
        assertEquals(95, identity.fingerprintSha256.length)
    }

    @Test
    fun reusesTheStoredCertificateUntilRegenerated() {
        val first = store().loadOrCreate()
        val again = store().loadOrCreate()
        assertEquals(first.fingerprintSha256, again.fingerprintSha256)

        val fresh = store().regenerate()
        assertNotEquals(first.fingerprintSha256, fresh.fingerprintSha256)
        assertEquals(fresh.fingerprintSha256, store().loadOrCreate().fingerprintSha256)
    }

    @Test
    fun theKeyFileIsSealedAndAnUnreadablePairIsReplaced() {
        val first = store().loadOrCreate()
        val passwordFile = File(dir, "webui-tls.pass")
        assertFalse(passwordFile.readText().contains(String(first.password)))

        passwordFile.writeText("sealed:wrong")
        val replaced = store().loadOrCreate()
        assertNotEquals(first.fingerprintSha256, replaced.fingerprintSha256)
        assertTrue(replaced.certificate is X509Certificate)
    }
}
