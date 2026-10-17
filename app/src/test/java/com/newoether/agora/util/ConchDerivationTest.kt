package com.newoether.agora.util

import android.app.Application
import android.util.Base64
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConchDerivationTest {
    @Test fun v2X25519HkdfMatchesGoAndIndependentHmacVector() {
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val private = KeyFactory.getInstance("XDH").generatePrivate(PKCS8EncodedKeySpec(bytes(
            "302e020100300506032b656e04220420" +
                "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
        )))
        val public = ShellCrypto.decodePublicKey(Base64.encodeToString(bytes(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
        ), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        val actual = ShellCrypto.deriveAesKey(private, public).joinToString("") { "%02x".format(it) }
        assertEquals("d551f7913184eaeb096dd8fe635ebf6017d7ca3159f721aa2fa43f54e4edc4d8", actual)
    }
}
