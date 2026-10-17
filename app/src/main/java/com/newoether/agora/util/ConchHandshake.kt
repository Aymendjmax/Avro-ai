package com.newoether.agora.util

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ConchHandshake(
    val public_key: String,
    val nonce: String,
    val signature: String,
    val features: String = "",
    val challenge: String = "",
    val features_signature: String = "",
) {
    fun verify(apiKey: String, expectedChallenge: String): Boolean =
        expectedChallenge.isNotEmpty() && challenge == expectedChallenge && nonce.isNotEmpty() &&
            verifyPayload(apiKey, nonce, public_key, signature) &&
            verifyPayload(apiKey, nonce, securityPayload(), features_signature) &&
            features.split(',').toSet().containsAll(REQUIRED_FEATURES)

    private fun securityPayload(): String = "conch-handshake-v2|$public_key|$challenge|$features"

    companion object {
        private val decoder = Json { ignoreUnknownKeys = true }
        private val REQUIRED_FEATURES = setOf(
            "hkdf-extract-v2", "response-auth-v1", "sse-sequence-v1", "rejection-auth-v1",
        )

        fun decode(body: String): ConchHandshake = decoder.decodeFromString(body)

        private fun verifyPayload(apiKey: String, nonce: String, payload: String, signature: String): Boolean {
            if (signature.length != 64) return false
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal("$nonce|$payload".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII),
            )
        }
    }
}
