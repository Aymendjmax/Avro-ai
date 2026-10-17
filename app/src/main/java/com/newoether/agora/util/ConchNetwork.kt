package com.newoether.agora.util

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.readBoundedWireText
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Conch owns its retry policy; ordinary Provider calls keep their existing policy. */
internal data class ConchTextResponse(
    val code: Int,
    val body: String,
    val isSuccessful: Boolean,
    val signature: String,
)

internal object ConchNetwork {
    const val CONTROL_LIMIT = 64L shl 10
    const val RESPONSE_LIMIT = 40L shl 20
    const val LINE_LIMIT = 10L shl 20

    /** Conch closes idle connections at 120s; expire pooled connections strictly earlier. */
    private const val POOL_KEEP_ALIVE_SECONDS = 45L

    val client by lazy {
        HttpClient.client.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(5, POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            .build()
    }

    /** Unknown execution outcomes must never trigger transparent replay. */
    val streamCalls: Call.Factory get() = client

    fun getTextResponse(url: String, headers: Map<String, String>): ConchTextResponse =
        execute(Request.Builder().url(url).get(), headers, CONTROL_LIMIT, 30_000)

    fun postTextResponse(
        url: String,
        body: String,
        headers: Map<String, String>,
        callTimeoutMillis: Long? = null,
    ): ConchTextResponse = execute(
        Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())),
        headers, RESPONSE_LIMIT, callTimeoutMillis ?: 130_000,
    )

    private fun execute(
        builder: Request.Builder,
        headers: Map<String, String>,
        limit: Long,
        timeoutMillis: Long,
    ): ConchTextResponse {
        require(timeoutMillis > 0)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        return openOnce(request, timeoutMillis).use { response ->
            ConchTextResponse(
                code = response.code,
                body = response.body.source().readBoundedWireText(
                    if (response.isSuccessful) limit else CONTROL_LIMIT,
                ),
                isSuccessful = response.isSuccessful,
                signature = response.header("X-Conch-Response-Signature").orEmpty(),
            )
        }
    }

    private fun openOnce(request: Request, timeoutMillis: Long): Response =
        client.newCall(request).also {
            it.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        }.execute()
}
