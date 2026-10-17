package com.newoether.agora.webui

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Server side of one TLS connection over an already accepted [socket], driven by an [SSLEngine].
 *
 * An engine rather than an `SSLSocket` because [WebUiTlsFront] has already read the first byte
 * to tell TLS from plain HTTP, and Android's `SSLSocketFactory` cannot be handed bytes that were
 * consumed from the socket. [consumed] is fed to the engine before anything else is read.
 *
 * One thread may [readInto] while another calls [write]; the engine allows `unwrap` and `wrap`
 * to run concurrently, and every `wrap` goes through [wrapLock].
 */
internal class WebUiTlsConnection(
    private val socket: Socket,
    context: SSLContext,
    protocols: Set<String>,
    consumed: ByteArray,
) {
    private val engine: SSLEngine = context.createSSLEngine().apply {
        useClientMode = false
        enabledProtocols = supportedProtocols.filter { it in protocols }.toTypedArray()
    }
    private val rawIn: InputStream = socket.getInputStream()
    private val rawOut: OutputStream = socket.getOutputStream()
    private val wrapLock = Any()

    // Both buffers stay in write mode between calls.
    private var netIn: ByteBuffer = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, consumed.size))
        .put(consumed)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)

    /** Completes the handshake; throws when the peer does not speak acceptable TLS. */
    fun handshake() {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP ->
                    if (!unwrap()) throw IOException("TLS peer closed during the handshake")
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(EMPTY)
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                else -> return
            }
        }
    }

    /** Copies decrypted application data to [output] until the peer closes. */
    fun readInto(output: OutputStream) {
        while (unwrap()) {
            if (appIn.position() == 0) continue
            appIn.flip()
            output.write(appIn.array(), appIn.arrayOffset(), appIn.limit())
            output.flush()
            appIn.clear()
        }
    }

    /** Encrypts and sends [length] bytes of [data]. */
    fun write(data: ByteArray, length: Int) {
        val source = ByteBuffer.wrap(data, 0, length)
        synchronized(wrapLock) {
            while (source.hasRemaining()) wrap(source)
        }
    }

    /**
     * One `unwrap` step, reading from the socket when the engine needs more bytes.
     * Returns false once the peer or the engine has closed.
     */
    private fun unwrap(): Boolean {
        netIn.flip()
        val result = try {
            engine.unwrap(netIn, appIn)
        } finally {
            netIn.compact()
        }
        when (result.status) {
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                if (netIn.remaining() < engine.session.packetBufferSize) {
                    netIn = grow(netIn, engine.session.packetBufferSize)
                }
                val read = rawIn.read(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
                if (read < 0) return false
                netIn.position(netIn.position() + read)
            }
            SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = grow(appIn, engine.session.applicationBufferSize)
            SSLEngineResult.Status.CLOSED -> return false
            SSLEngineResult.Status.OK -> Unit
        }
        runTasks()
        // TLS 1.3 post-handshake messages (key updates) can ask for a reply outside the handshake.
        if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            synchronized(wrapLock) { wrap(EMPTY) }
        }
        return true
    }

    /** One `wrap` step and the resulting bytes on the wire. Call under [wrapLock] outside the handshake. */
    private fun wrap(source: ByteBuffer) {
        while (true) {
            netOut.clear()
            val result = engine.wrap(source, netOut)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                SSLEngineResult.Status.CLOSED -> throw IOException("TLS connection closed")
                else -> {
                    netOut.flip()
                    rawOut.write(netOut.array(), netOut.arrayOffset(), netOut.limit())
                    rawOut.flush()
                    runTasks()
                    return
                }
            }
        }
    }

    private fun runTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }

    /** A larger copy of a write-mode [buffer] with at least [minimumFree] bytes free. */
    private fun grow(buffer: ByteBuffer, minimumFree: Int): ByteBuffer {
        val grown = ByteBuffer.allocate(buffer.position() + minimumFree)
        buffer.flip()
        grown.put(buffer)
        return grown
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
