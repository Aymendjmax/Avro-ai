package com.newoether.agora.webui

import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** The server's TLS key and self-signed certificate, ready for a KeyManagerFactory. */
internal class WebUiTlsIdentity(
    val keyStore: KeyStore,
    val password: CharArray,
    val certificate: X509Certificate,
) {
    /** SHA-256 of the DER certificate as colon-separated hex, as browsers show it. */
    val fingerprintSha256: String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }
}

/**
 * Keeps the WebUI's self-signed certificate in app-private storage.
 *
 * The certificate is made once and reused, so a browser that accepted it keeps trusting it; it
 * changes only on [regenerate]. The PKCS12 file is protected by a random password that is stored
 * [seal]ed (AndroidKeyStore in the app), so the file alone does not reveal the key.
 */
internal class WebUiCertificateStore(
    private val directory: File,
    private val seal: (String) -> String,
    private val unseal: (String) -> String,
    /** Addresses written into the certificate; the current interfaces in the app. */
    private val addresses: () -> List<InetAddress>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val keyStoreFile get() = File(directory, KEYSTORE_FILE)
    private val passwordFile get() = File(directory, PASSWORD_FILE)

    @Synchronized
    fun loadOrCreate(): WebUiTlsIdentity = load() ?: create()

    /** Replaces the certificate; browsers must accept the new one once. */
    @Synchronized
    fun regenerate(): WebUiTlsIdentity = create()

    private fun load(): WebUiTlsIdentity? {
        if (!keyStoreFile.isFile || !passwordFile.isFile) return null
        return runCatching {
            val password = unseal(passwordFile.readText()).toCharArray()
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            keyStoreFile.inputStream().use { keyStore.load(it, password) }
            val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
            certificate.checkValidity(Date(clock()))
            WebUiTlsIdentity(keyStore, password, certificate)
        }.getOrNull()
    }

    private fun create(): WebUiTlsIdentity {
        val password = randomPassword()
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
            .generateKeyPair()
        val now = clock()
        val subject = X500Name("CN=$COMMON_NAME")
        val names = listOf(GeneralName(GeneralName.dNSName, "localhost")) +
            addresses().map { GeneralName(GeneralName.iPAddress, it.hostAddress!!.substringBefore('%')) }
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - CLOCK_SKEW_MILLIS),
            Date(now + VALIDITY_MILLIS),
            subject,
            keyPair.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            .addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
            .build(JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, password, arrayOf(certificate))
        }
        directory.mkdirs()
        // Each file is written whole before it replaces the old one. A crash between the two
        // moves leaves a mismatched pair, which load() rejects, so the next start makes a new one.
        val keyStoreTemp = File(directory, "$KEYSTORE_FILE.tmp")
        keyStoreTemp.outputStream().use { keyStore.store(it, password) }
        val passwordTemp = File(directory, "$PASSWORD_FILE.tmp")
        passwordTemp.writeText(seal(String(password)))
        Files.move(keyStoreTemp.toPath(), keyStoreFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(passwordTemp.toPath(), passwordFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return WebUiTlsIdentity(keyStore, password, certificate)
    }

    private fun randomPassword(): CharArray {
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        return Base64.getEncoder().withoutPadding().encodeToString(bytes).toCharArray()
    }

    companion object {
        const val ALIAS = "webui"
        const val COMMON_NAME = "Avro WebUI"
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val KEYSTORE_FILE = "webui-tls.p12"
        private const val PASSWORD_FILE = "webui-tls.pass"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val CLOCK_SKEW_MILLIS = 24L * 60 * 60 * 1000
        /** Ten years: a self-signed certificate is trusted by fingerprint, not by expiry. */
        private const val VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
