package ca.beric.clipsync.transport

import ca.beric.clipsync.protocol.ControlCodec
import ca.beric.clipsync.protocol.ControlMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import io.ktor.server.application.install
import io.ktor.server.netty.Netty
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * A live, bidirectional link to one paired peer over a single TLS WebSocket.
 * Control messages travel as JSON text frames; image chunks as binary frames.
 * Inbound frames are pumped into [control]/[binary] by the owning session scope.
 */
class PeerLink internal constructor(private val session: WebSocketSession) {

    private val controlChannel = Channel<ControlMessage>(Channel.BUFFERED)
    private val binaryChannel = Channel<ByteArray>(Channel.BUFFERED)

    val control: Flow<ControlMessage> get() = controlChannel.receiveAsFlow()
    val binary: Flow<ByteArray> get() = binaryChannel.receiveAsFlow()

    init {
        // WebSocketSession is a CoroutineScope; pump lives as long as the socket.
        session.launch { pump() }
    }

    private suspend fun pump() {
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> ControlCodec.decode(frame.readText())?.let { controlChannel.send(it) }
                    is Frame.Binary -> binaryChannel.send(frame.readBytes())
                    else -> Unit
                }
            }
        } finally {
            controlChannel.close()
            binaryChannel.close()
        }
    }

    suspend fun send(message: ControlMessage) = session.send(ControlCodec.encode(message))

    suspend fun sendChunk(frame: ByteArray) = session.send(Frame.Binary(true, frame))

    suspend fun close() = session.close()
}

/** TLS WebSocket server. Each accepted peer connection is handed to [onPeer]. */
class ClipServer(
    private val identity: TlsIdentity,
    private val onPeer: suspend (PeerLink) -> Unit,
) {
    private val keyPassword = "clipsync".toCharArray()
    private var engine: EmbeddedServer<*, *>? = null

    fun start(port: Int, host: String = "0.0.0.0") {
        val keyStore = identity.keyStore(password = keyPassword)
        val server = embeddedServer(
            Netty,
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "clipsync",
                    keyStorePassword = { keyPassword },
                    privateKeyPassword = { keyPassword },
                ) {
                    this.port = port
                    this.host = host
                }
            },
            module = {
                install(ServerWebSockets)
                routing {
                    webSocket("/sync") {
                        val link = PeerLink(this)
                        onPeer(link)
                    }
                }
            },
        )
        server.start(wait = false)
        engine = server
    }

    fun stop() {
        engine?.stop(0, 0)
    }
}

/** TLS WebSocket client that pins the server certificate by SHA-256 fingerprint. */
object ClipClient {

    suspend fun connect(host: String, port: Int, pinnedFingerprint: String): PeerLink {
        val trustManager = pinningTrustManager(pinnedFingerprint)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), java.security.SecureRandom())
        }
        val client = HttpClient(OkHttp) {
            install(ClientWebSockets)
            engine {
                preconfigured = OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustManager)
                    // Self-signed cert CN won't match the IP; we authenticate by pinned fingerprint.
                    .hostnameVerifier { _, _ -> true }
                    // Stored peer rows can carry dead endpoints (e.g. VM-bridge addresses from an
                    // old pairing) that are dialed before the live one; OkHttp's default 10 s per
                    // dead endpoint made reconnects visibly slow on hardware. 3 s caps the damage.
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            }
        }
        val session = client.webSocketSession(
            method = HttpMethod.Get,
            host = host,
            port = port,
            path = "/sync",
        ) {
            url.protocol = URLProtocol.WSS
        }
        return PeerLink(session)
    }

    private fun pinningTrustManager(pinnedFingerprint: String) = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val presented = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
            val fp = TlsIdentity.sha256Hex(presented.encoded)
            if (fp != pinnedFingerprint) {
                throw CertificateException("certificate fingerprint mismatch: expected $pinnedFingerprint got $fp")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
