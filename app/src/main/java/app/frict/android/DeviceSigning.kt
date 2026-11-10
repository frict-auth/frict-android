package app.frict.android

import java.util.Base64

/** Signs with ECDSA P-256 over SHA-256 and returns the ASN.1 DER signature. */
fun interface Signer {
    fun sign(data: ByteArray): ByteArray
}

/** The bytes a device signs for one request (docs/device-protocol.md in frict-server). */
fun signedBytes(userId: String, unixSeconds: Long, path: String, body: ByteArray): ByteArray =
    "frict-device-v1\n$userId\n$unixSeconds\n$path\n".toByteArray(Charsets.UTF_8) + body

/** The Authorization header value for a device request, signed by [signer]. */
fun authorization(signer: Signer, userId: String, unixSeconds: Long, path: String, body: ByteArray): String {
    val signature = signer.sign(signedBytes(userId, unixSeconds, path, body))
    return "Frict-P256 $userId:$unixSeconds:${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"
}
