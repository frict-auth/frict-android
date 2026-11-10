package app.frict.android

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors made by frict-server's own code: see scripts/gen-signing-vectors.sh. */
class DeviceSigningTest {
    private class Vector(json: JSONObject) {
        val name: String = json.getString("name")
        val userId: String = json.getString("user_id")
        val unixSeconds: Long = json.getLong("unix_seconds")
        val path: String = json.getString("path")
        val body: ByteArray = json.getString("body").toByteArray(Charsets.UTF_8)
        val signedBytes: ByteArray = Base64.getDecoder().decode(json.getString("signed_bytes"))
        val authorization: String = json.getString("authorization")
        val signature: ByteArray = Base64.getUrlDecoder().decode(authorization.substringAfterLast(':'))
    }

    private val file = JSONObject(
        checkNotNull(javaClass.getResource("/signing-vectors.json")).readText()
    )
    private val vectors = file.getJSONArray("vectors").let { a -> List(a.length()) { Vector(a.getJSONObject(it)) } }
    private val keys = KeyFactory.getInstance("EC")
    private val privateKey: PrivateKey =
        keys.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(file.getString("private_key_pkcs8"))))
    private val publicKey: PublicKey =
        keys.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(file.getString("public_key_spki"))))

    private fun verifies(data: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(data)
            verify(signature)
        }

    private fun Vector.sign(signer: Signer) = authorization(signer, userId, unixSeconds, path, body)

    @Test
    fun signedBytesMatchServer() {
        for (v in vectors) {
            assertArrayEquals(v.name, v.signedBytes, signedBytes(v.userId, v.unixSeconds, v.path, v.body))
        }
    }

    @Test
    fun serverSignatureVerifiesOverOurSignedBytes() {
        for (v in vectors) {
            assertTrue(v.name, verifies(signedBytes(v.userId, v.unixSeconds, v.path, v.body), v.signature))
        }
    }

    @Test
    fun headerMatchesServerForSameSignature() {
        for (v in vectors) {
            assertEquals(v.name, v.authorization, v.sign { v.signature })
        }
    }

    @Test
    fun headerFromRealSignerVerifiesOverServerSignedBytes() {
        val signer = Signer { data ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey)
                update(data)
                sign()
            }
        }
        for (v in vectors) {
            val header = v.sign(signer)
            assertEquals(v.name, v.authorization.substringBeforeLast(':'), header.substringBeforeLast(':'))
            assertTrue(v.name, verifies(v.signedBytes, Base64.getUrlDecoder().decode(header.substringAfterLast(':'))))
        }
    }
}
