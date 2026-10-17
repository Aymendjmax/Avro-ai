package com.newoether.agora.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun `headers query parameters and user info are permanently redacted`() {
        val headers = DiagnosticRedactor.captureHeaders(
            mapOf(
                "Authorization" to "Bearer top-secret-token",
                "Cookie" to "session=cookie-secret",
                "X-Trace" to "api_key=another-secret",
                "X-Conch-Signature" to "conch-signature-value",
            ),
        )
        val url = DiagnosticRedactor.captureUrl(
            "https://user:password@example.com/v1/chat" +
                "?X-Amz-Credential=signed-user&X-Amz-Signature=signed-secret&model=test",
        )

        assertEquals("[REDACTED_SECRET]", headers["Authorization"])
        assertEquals("[REDACTED_SECRET]", headers["Cookie"])
        assertEquals("[REDACTED_SECRET]", headers["X-Conch-Signature"])
        assertFalse(headers.getValue("X-Trace").contains("another-secret"))
        assertFalse(url.value.contains("user"))
        assertFalse(url.value.contains("password"))
        assertFalse(url.value.contains("signed-user"))
        assertFalse(url.value.contains("signed-secret"))
        assertTrue(url.value.contains("model=test"))
    }

    @Test
    fun `raw capture keeps json content while removing credentials`() {
        val raw = """
            {
              "api_key": "nested-secret",
              "messages": [{"role": "user", "content": "private prompt"}],
              "max_tokens": 10,
              "nested": {"access_token": "token-secret", "text": "private text"}
            }
        """.trimIndent()

        val captured = DiagnosticRedactor.captureJson(
            raw,
            credentialValues = setOf("nested-secret", "token-secret"),
        )

        assertFalse(captured.value.contains("nested-secret"))
        assertFalse(captured.value.contains("token-secret"))
        assertTrue(captured.value.contains("private prompt"))
        assertTrue(captured.value.contains("private text"))
        assertTrue(captured.value.contains("[REDACTED_SECRET]"))
        assertTrue(captured.value.contains("max_tokens"))
        assertTrue(captured.value.contains("10"))
    }

    @Test
    fun `captured content keeps semantic text but removes inline credentials`() {
        val raw = """
            {
              "content": "keep this text but remove sk-abcdefghijklmnop",
              "authorization": "Bearer abcdefghijklmnop",
              "nested": {"password": "password-secret"}
            }
        """.trimIndent()

        val captured = DiagnosticRedactor.captureJson(
            raw,
            credentialValues = setOf("password-secret"),
        )

        assertTrue(captured.value.contains("keep this text"))
        assertFalse(captured.value.contains("sk-abcdefghijklmnop"))
        assertFalse(captured.value.contains("abcdefghijklmnop"))
        assertFalse(captured.value.contains("password-secret"))
    }

    @Test
    fun `raw capture preserves ordinary ndjson fields without removing message content`() {
        val line = DiagnosticRedactor.captureWireLine(
            """{"message":{"content":"private local response"},"token":"secret-value"}""",
        )

        assertTrue(line.value.contains("private local response"))
        assertTrue(line.value.contains("secret-value"))
    }

    @Test
    fun `message prose keeps credential words and keyword assignments`() {
        val prose = "Use token: abc123 in your config, key = value and password = hunter2. " +
            "The word apikey appears in this sentence too."

        val content = DiagnosticRedactor.captureContent(prose)
        val json = DiagnosticRedactor.captureJson(
            """{"messages":[{"role":"user","content":"$prose"}]}""",
        )
        val wire = DiagnosticRedactor.captureWireLine("""data: {"text":"$prose"}""")

        assertEquals(prose, content.value)
        assertTrue(json.value.contains(prose))
        assertTrue(wire.value.contains(prose))
    }

    @Test
    fun `credential shapes are masked wherever they appear`() {
        val text = "openai sk-abcdefghijklmnop google AIzaabcdefghijklmnopqrstuv12 " +
            "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.YGl0aGVycmVzdHZhbHVl " +
            "raw Bearer dXNlcjpwYXNz"

        val captured = DiagnosticRedactor.captureContent(text)

        assertFalse(captured.value.contains("sk-abcdefghijklmnop"))
        assertFalse(captured.value.contains("AIzaabcdefghijklmnopqrstuv12"))
        assertFalse(captured.value.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(captured.value.contains("dXNlcjpwYXNz"))
        assertTrue(captured.value.contains("openai"))
        assertTrue(captured.value.contains("google"))
        assertTrue(captured.value.contains("raw"))
    }

    @Test
    fun `malformed urls fail closed`() {
        val url = DiagnosticRedactor.captureUrl(
            "not a valid url user:password",
        )

        assertEquals("[UNAVAILABLE_INVALID_URL]", url.value)
    }

    @Test
    fun `invalid json preserves text while masking credential shapes`() {
        val body = DiagnosticRedactor.captureJson(
            "not-json token=private-secret visible text",
        )
        val line = DiagnosticRedactor.captureWireLine(
            "data: not-json token=private-secret visible text",
        )
        val keyedBody = DiagnosticRedactor.captureJson(
            "not-json api_key=sk-abcdefghijklmnop visible text",
        )

        assertTrue(body.value.contains("visible text"))
        assertTrue(line.value.contains("visible text"))
        assertTrue(body.value.contains("token=private-secret"))
        assertTrue(line.value.contains("token=private-secret"))
        assertTrue(keyedBody.value.contains("visible text"))
        assertFalse(keyedBody.value.contains("sk-abcdefghijklmnop"))
    }
    @Test
    fun `request credentials are removed without rewriting tool json fields`() {
        val headers = mapOf(
            "Authorization" to "Bearer actual-request-secret",
            "X-Trace" to "trace-value",
        )
        val raw = """
            {
              "api_key": "actual-request-secret",
              "tool": {
                "key": "user-key",
                "token": "user-token",
                "signature": "protocol-signature",
                "password": "user-password"
              }
            }
        """.trimIndent()
        val captured = DiagnosticRedactor.captureJson(
            raw,
            DiagnosticRedactor.credentialValues(headers),
        )
        assertFalse(captured.value.contains("actual-request-secret"))
        assertTrue(captured.value.contains("user-key"))
        assertTrue(captured.value.contains("user-token"))
        assertTrue(captured.value.contains("protocol-signature"))
        assertTrue(captured.value.contains("user-password"))
    }

    @Test
    fun `export projection preserves message content and masks credentials`() {
        val captured = CapturedDiagnosticText(
            value = """{"messages":[{"role":"user","content":"private prompt"}],""" +
                """"arguments":"tool args","result":"tool result","api_key":"[REDACTED_SECRET]","max_tokens":10}""",
            originalLength = 142,
            truncated = false,
            redacted = true,
        )

        val redacted = DiagnosticRedactor.redactJsonContent(captured)

        assertTrue(redacted.value.contains("private prompt"))
        assertTrue(redacted.value.contains("tool args"))
        assertTrue(redacted.value.contains("tool result"))
        assertTrue(redacted.value.contains("max_tokens"))
        assertTrue(redacted.value.contains("10"))
        assertFalse(redacted.value.contains("export-secret"))
        assertFalse(redacted.value.contains("[REDACTED_CONTENT]"))
    }

    @Test
    fun `export wire projection preserves data lines and plain text`() {
        val data = DiagnosticRedactor.redactWireContent(
            CapturedDiagnosticText(
                value = """data: {"text":"private response","result":"private tool result"}""",
                originalLength = 65,
                truncated = false,
                redacted = true,
            ),
        )
        val plain = DiagnosticRedactor.redactWireContent(
            CapturedDiagnosticText(
                value = "<html>502 bad gateway</html>",
                originalLength = 24,
                truncated = false,
                redacted = true,
            ),
        )

        assertTrue(data.value.contains("private response"))
        assertTrue(data.value.contains("private tool result"))
        assertTrue(plain.value.contains("502 bad gateway"))
        assertFalse(plain.value.contains("[REDACTED_CONTENT]"))
    }

    @Test
    fun `export content projection preserves parsed stream text`() {
        val content = DiagnosticRedactor.redactContent(
            CapturedDiagnosticText(
                value = "private parsed tool result",
                originalLength = 28,
                truncated = false,
                redacted = true,
            ),
        )

        assertEquals("private parsed tool result", content.value)
        assertEquals(28, content.originalLength)
    }

    @Test
    fun `large captured values use the two mebibyte utf8 limit`() {
        val privateContent = "x".repeat(DiagnosticCaptureStore.DEFAULT_MAX_PAYLOAD_BYTES + 1)
        val captured = DiagnosticRedactor.captureContent(privateContent)

        assertTrue(captured.truncated)
        assertTrue(captured.originalLength > captured.value.length)
        assertEquals(DiagnosticCaptureStore.DEFAULT_MAX_PAYLOAD_BYTES, captured.value.length)
    }
}
