package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiResponsesRequest
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.isOpenAiProtocolProvider
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiServiceTierTest {
    private val wireJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun everyEnabledTierIsSerializedWithoutNormalizationLossForResponses() {
        OpenAiServiceTiers.values.forEach { tier ->
            val customized = request().copy(
                serviceTier = OpenAiServiceTiers.requestValue(
                    enabled = true,
                    value = tier,
                    responsesApiEnabled = true,
                    modelId = "unknown-relay-model",
                ),
            )

            assertEquals(tier, customized.serviceTier)
            assertTrue(
                wireJson.encodeToString(customized)
                    .contains("\"service_tier\":\"$tier\""),
            )
        }
    }

    @Test
    fun disabledTierDoesNotLeakIntoTheCompatibleRequest() {
        val baseRequest = request()

        assertNull(
            OpenAiServiceTiers.requestValue(
                enabled = false,
                value = OpenAiServiceTiers.FAST,
                responsesApiEnabled = true,
            ),
        )
        assertNull(
            OpenAiServiceTiers.requestValue(
                enabled = true,
                value = OpenAiServiceTiers.FAST,
                responsesApiEnabled = false,
            ),
        )
        assertNull(baseRequest.serviceTier)
        assertFalse(
            wireJson.encodeToString(baseRequest)
                .contains("\"service_tier\""),
        )
    }

    @Test
    fun invalidOrMissingTiersNormalizeToAuto() {
        assertEquals(
            listOf("auto", "default", "flex", "fast", "ultrafast"),
            OpenAiServiceTiers.values,
        )
        assertEquals(OpenAiServiceTiers.AUTO, OpenAiServiceTiers.normalize(null))
        assertEquals(OpenAiServiceTiers.AUTO, OpenAiServiceTiers.normalize("unknown"))
        assertEquals(OpenAiServiceTiers.FLEX, OpenAiServiceTiers.normalize(" FLEX "))
        assertEquals(OpenAiServiceTiers.DEFAULT, OpenAiServiceTiers.normalize(" SCALE "))
        assertEquals(OpenAiServiceTiers.FAST, OpenAiServiceTiers.normalize(" Priority "))
        assertEquals(1, OpenAiServiceTiers.indexForTier("scale"))
        assertEquals(3, OpenAiServiceTiers.indexForTier("priority"))
        OpenAiServiceTiers.values.forEach { assertEquals(it, OpenAiServiceTiers.normalize(it)) }
    }

    @Test
    fun officialModelListsAndUnknownRelayAreDistinct() {
        val base = listOf("auto", "default")
        assertEquals(base + listOf("flex", "fast"), OpenAiServiceTiers.availableTiers("gpt-6-astra"))
        assertEquals(OpenAiServiceTiers.values, OpenAiServiceTiers.availableTiers("gpt-5.6-sol"))
        assertEquals(base + listOf("flex"), OpenAiServiceTiers.availableTiers("gpt-5.4-pro"))
        assertEquals(base + listOf("fast"), OpenAiServiceTiers.availableTiers("gpt-4o"))
        assertEquals(base, OpenAiServiceTiers.availableTiers("gpt-5.2-pro"))
        assertEquals(base, OpenAiServiceTiers.availableTiers("gpt-3.5-turbo"))
        assertEquals(OpenAiServiceTiers.values, OpenAiServiceTiers.availableTiers("future-gpt"))
        assertEquals(OpenAiServiceTiers.values, OpenAiServiceTiers.availableTiers("gpt-4o", false))
    }

    @Test
    fun unsupportedTiersMapWithoutChangingSavedChoice() {
        assertEquals("default", OpenAiServiceTiers.mappedTier("flex", "gpt-4o"))
        assertEquals("default", OpenAiServiceTiers.mappedTier("ultrafast", "gpt-5.2-pro"))
        assertEquals("fast", OpenAiServiceTiers.mappedTier("ultrafast", "gpt-6-sol"))
        assertEquals("default", OpenAiServiceTiers.mappedTier("fast", "gpt-5.4-pro"))
        assertEquals("auto", OpenAiServiceTiers.mappedTier("auto", "gpt-5.2-pro"))
        assertEquals("default", OpenAiServiceTiers.mappedTier("default", "gpt-5.2-pro"))
        assertEquals("ultrafast", OpenAiServiceTiers.mappedTier("ultrafast", "gpt-5.6-sol"))
        assertEquals("ultrafast", OpenAiServiceTiers.mappedTier("ultrafast", "gpt-4o", false))
        assertEquals("ultrafast", OpenAiServiceTiers.normalize("ultrafast"))
    }

    @Test
    fun normalizedLegacyTiersAndMappedOfficialTiersSerialize() {
        listOf(
            Triple("scale", "gpt-5.6-sol", "default"),
            Triple("priority", "gpt-5.6-sol", "fast"),
            Triple("flex", "gpt-4o", "default"),
            Triple("ultrafast", "gpt-6-astra", "fast"),
        ).forEach { (saved, model, expected) ->
            val value = OpenAiServiceTiers.requestValue(true, saved, true, model)
            assertEquals(expected, value)
            assertTrue(wireJson.encodeToString(request().copy(serviceTier = value))
                .contains("\"service_tier\":\"$expected\""))
        }
    }

    @Test
    fun capabilityIncludesBuiltInAndCustomOpenAiProtocolOnly() {
        val customProviders = listOf(
            CustomProviderConfig("Sub2", CustomEndpointProtocol.OPENAI),
            CustomProviderConfig("Claude relay", CustomEndpointProtocol.ANTHROPIC),
        )

        assertTrue(isOpenAiProtocolProvider(Constants.PROVIDER_OPENAI, customProviders))
        assertTrue(isOpenAiProtocolProvider("Sub2", customProviders))
        assertFalse(isOpenAiProtocolProvider("Claude relay", customProviders))
        assertFalse(isOpenAiProtocolProvider(Constants.PROVIDER_DEEPSEEK, customProviders))
    }

    private fun request() = OpenAiResponsesRequest(
        model = "gpt-5",
        input = listOf(buildJsonObject {}),
    )
}
