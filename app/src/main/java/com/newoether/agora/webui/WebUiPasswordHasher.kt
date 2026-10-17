package com.newoether.agora.webui

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 for the WebUI password. Only the encoded hash is stored; it carries
 * its own iteration count so the cost can be raised later without breaking existing passwords.
 *
 * Encoded form: `pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>`.
 */
internal class WebUiPasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(password: String): String {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(password, salt, iterations)
        return listOf(
            SCHEME,
            iterations.toString(),
            encoder.encodeToString(salt),
            encoder.encodeToString(derived),
        ).joinToString(SEPARATOR)
    }

    /** False for a wrong password and for any stored value this hasher cannot parse. */
    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val storedIterations = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS } ?: return false
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        if (salt.isEmpty() || expected.size != KEY_BYTES) return false
        val actual = derive(password, salt, storedIterations)
        // Constant-time comparison so the response time does not reveal matching prefixes.
        return MessageDigest.isEqual(actual, expected)
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        /** Tuned for phone CPUs: well under a second per login on current devices. */
        const val DEFAULT_ITERATIONS = 120_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val SALT_BYTES = 16
        private const val KEY_BYTES = 32
        private const val SCHEME = "pbkdf2-sha256"
        private const val SEPARATOR = "$"
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private val encoder = Base64.getEncoder().withoutPadding()
        private val decoder = Base64.getDecoder()
    }
}
