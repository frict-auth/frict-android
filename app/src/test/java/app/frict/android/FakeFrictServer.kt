package app.frict.android

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

class RecordedRequest(val path: String, val authorization: String, val body: ByteArray) {
    val json get() = JSONObject(String(body, Charsets.UTF_8))
}

/**
 * Stands in for frict-server's enroll endpoint: accepts one activation [code] for [userId]
 * when the request is signed, per docs/device-protocol.md, by the body's device_key.
 */
class FakeFrictServer(private val userId: String, private val code: String) : AutoCloseable {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var claimed = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val request = RecordedRequest(
                    it.requestURI.path,
                    it.requestHeaders.getFirst("Authorization").orEmpty(),
                    it.requestBody.readBytes(),
                )
                requests += request
                val (status, reply) = if (accepts(request)) 200 to "{}" else 401 to """{"error":"unauthorized"}"""
                val bytes = reply.toByteArray()
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        start()
    }

    val url = "http://127.0.0.1:${server.address.port}"

    @Synchronized
    private fun accepts(r: RecordedRequest): Boolean {
        if (r.path != ENROLL_PATH || claimed) return false
        val (claimedUser, unix, signature) = r.authorization.removePrefix("Frict-P256 ").split(':')
        val body = r.json
        if (body.keys().asSequence().toSet() != setOf("activation_code", "device_key", "approval_key", "attestation", "fid")) return false
        if (body.getString("activation_code") != code || claimedUser != userId || body.getString("fid").isEmpty()) return false
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64url(body.getString("device_key")))))
            update(signedBytes(claimedUser, unix.toLong(), r.path, r.body))
        }
        if (!verifier.verify(b64url(signature))) return false
        claimed = true
        return true
    }

    override fun close() = server.stop(0)
}

fun b64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
