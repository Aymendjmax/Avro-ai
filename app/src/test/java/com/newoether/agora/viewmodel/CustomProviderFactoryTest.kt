package com.newoether.agora.viewmodel

import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomEndpointResolution
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderFactoryTest {
    @Test
    fun factoryReusesExistingProtocolImplementations() {
        val url = "https://example.test/api"

        val openAi = createCustomProvider(
            CustomProviderConfig("OpenAI proxy", CustomEndpointProtocol.OPENAI),
            url,
        )
        val google = createCustomProvider(
            CustomProviderConfig("Google proxy", CustomEndpointProtocol.GOOGLE),
            url,
        )
        val anthropic = createCustomProvider(
            CustomProviderConfig("Anthropic proxy", CustomEndpointProtocol.ANTHROPIC),
            url,
        )

        assertTrue(openAi is CustomOpenAiProvider)
        assertTrue(google is GeminiProvider)
        assertTrue(anthropic is AnthropicProvider)
        assertEquals("Google proxy", google?.name)
        assertEquals("Anthropic proxy", anthropic?.name)
        assertEquals(url, google?.defaultBaseUrl)
        assertEquals(url, anthropic?.defaultBaseUrl)
    }

    @Test
    fun unknownProtocolIsNotRegistered() {
        assertNull(
            createCustomProvider(
                CustomProviderConfig("Unknown", CustomEndpointProtocol.UNKNOWN),
                "https://example.test",
            ),
        )
    }

    @Test
    fun builtInNameCannotBeRegisteredAsCustomProvider() {
        assertNull(
            createCustomProvider(
                CustomProviderConfig(Constants.PROVIDER_LOCAL),
                "https://example.test",
            ),
        )
        assertNull(
            createCustomProvider(
                CustomProviderConfig(Constants.PROVIDER_LOCAL.lowercase()),
                "https://example.test",
            ),
        )
    }

    @Test
    fun baseUrlCandidatesAreProtocolSpecific() {
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.OPENAI,
                "https://example.test",
            ),
        )
        assertEquals(
            listOf("https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.GOOGLE,
                "https://example.test",
            ),
        )
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.ANTHROPIC,
                "https://example.test",
            ),
        )
        assertEquals(
            emptyList<String?>(),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.UNKNOWN,
                "https://example.test",
            ),
        )
    }

    @Test
    fun explicitVersionedBaseUrlIsTriedBeforeMigrationFallbacks() {
        val url = "https://example.test/v1beta"

        assertEquals(
            listOf(url, "https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(CustomEndpointProtocol.OPENAI, url),
        )
        assertEquals(
            listOf(url, "https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(CustomEndpointProtocol.ANTHROPIC, url),
        )
        assertEquals(
            listOf(url, "https://example.test"),
            customEndpointBaseUrlCandidates(CustomEndpointProtocol.GOOGLE, url),
        )
    }

    @Test
    fun oldPersistedV1CanRecoverWhenSwitchingToGoogle() {
        assertEquals(
            listOf("https://example.test/v1", "https://example.test"),
            customEndpointBaseUrlCandidates(
                CustomEndpointProtocol.GOOGLE,
                "https://example.test/v1",
            ),
        )
    }

    @Test
    fun resolvedEndpointIsScopedToProtocolAndConfiguredUrl() {
        val resolution = CustomEndpointResolution(
            protocol = CustomEndpointProtocol.OPENAI,
            configuredBaseUrl = "https://example.test/",
            effectiveBaseUrl = "https://example.test/v1",
        )

        assertTrue(resolution.matches(CustomEndpointProtocol.OPENAI, "https://example.test"))
        assertTrue(!resolution.matches(CustomEndpointProtocol.GOOGLE, "https://example.test"))
        assertTrue(!resolution.matches(CustomEndpointProtocol.OPENAI, "https://other.test"))
    }

    @Test
    fun fetchedCustomModelsUseStableProviderIdentityNotDisplayName() {
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        val config = CustomProviderConfig(name = "Relay X", id = id)

        assertEquals(
            listOf("$id:gemini-3.1-pro"),
            prefixFetchedModels("Relay X", config, listOf("models/gemini-3.1-pro")),
        )
        assertEquals(
            listOf("OpenAI:gpt-5"),
            prefixFetchedModels("OpenAI", null, listOf("gpt-5")),
        )
    }
}
