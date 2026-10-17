package com.newoether.agora.util

import org.junit.Assert.*
import org.junit.Test

class ConchResponseAuthenticationTest {
    @Test fun crossLanguageVectorAndTampering() {
        val key = "0123456789abcdef0123456789abcdef".toByteArray()
        val body = "opaque-response-body"
        val signature = "c07386ab0ed3de798935fbf4a2da6786248563454677fc28721d16d30bcd317e"
        assertEquals(signature, ShellCrypto.responseSignature(key, 200, body))
        assertTrue(ShellCrypto.verifyResponseSignature(key, 200, body, signature))
        assertFalse(ShellCrypto.verifyResponseSignature(key, 201, body, signature))
        assertFalse(ShellCrypto.verifyResponseSignature(key, 200, "another-body", signature))
        assertFalse(ShellCrypto.verifyResponseSignature(key, 200, body, ""))
        assertFalse(ShellCrypto.verifyResponseSignature(key.reversedArray(), 200, body, signature))
    }
}
