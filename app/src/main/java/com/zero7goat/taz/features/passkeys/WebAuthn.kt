package com.zero7goat.taz.features.passkeys

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/*
 * The WebAuthn pieces a passkey provider has to produce, as pure Kotlin (unit tested): key pairs
 * (ES256: ECDSA P-256 with SHA-256), authenticator data, the CBOR attestation object ("none"
 * attestation: TAZ makes no claims about the device) and the JSON responses Android expects.
 */

object B64 {
    fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun fromUrl(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))
}

/** A minimal CBOR encoder: just the types WebAuthn needs (ints, byte and text strings, maps). */
class Cbor {
    private val out = ByteArrayOutputStream()

    private fun head(major: Int, value: Long) {
        val m = major shl 5
        when {
            value < 24 -> out.write(m or value.toInt())
            value < 0x100 -> { out.write(m or 24); out.write(value.toInt()) }
            value < 0x10000 -> { out.write(m or 25); out.write((value shr 8).toInt()); out.write(value.toInt() and 0xff) }
            else -> { out.write(m or 26); for (s in 24 downTo 0 step 8) out.write(((value shr s) and 0xff).toInt()) }
        }
    }

    fun int(value: Long) = apply { if (value >= 0) head(0, value) else head(1, -1 - value) }
    fun bytes(value: ByteArray) = apply { head(2, value.size.toLong()); out.write(value) }
    fun text(value: String) = apply { val b = value.toByteArray(Charsets.UTF_8); head(3, b.size.toLong()); out.write(b) }
    fun map(entries: Int) = apply { head(5, entries.toLong()) }
    fun raw(value: ByteArray) = apply { out.write(value) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

class CreationOptions(
    val rpId: String,
    val userId: String,
    val userName: String,
    val userDisplayName: String,
    val challenge: String,
    val excludeCredentialIds: List<String>,
    val supportsEs256: Boolean
)

class RequestOptions(val rpId: String, val challenge: String, val allowCredentialIds: List<String>)

object WebAuthn {

    const val ES256 = -7L
    private const val FLAG_UP = 0x01
    private const val FLAG_UV = 0x04
    private const val FLAG_AT = 0x40

    fun parseCreation(json: String): CreationOptions {
        val o = JSONObject(json)
        val rp = o.getJSONObject("rp")
        val user = o.getJSONObject("user")
        val params = o.optJSONArray("pubKeyCredParams") ?: JSONArray()
        val es256 = params.length() == 0 || (0 until params.length()).any { params.getJSONObject(it).optLong("alg") == ES256 }
        return CreationOptions(
            rpId = rp.optString("id").ifBlank { rp.optString("name") },
            userId = user.getString("id"),
            userName = user.optString("name"),
            userDisplayName = user.optString("displayName"),
            challenge = o.getString("challenge"),
            excludeCredentialIds = ids(o.optJSONArray("excludeCredentials")),
            supportsEs256 = es256
        )
    }

    fun parseRequest(json: String): RequestOptions {
        val o = JSONObject(json)
        return RequestOptions(o.optString("rpId"), o.getString("challenge"), ids(o.optJSONArray("allowCredentials")))
    }

    private fun ids(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it).getString("id") }

    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun privateKeyFrom(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    /** The public key as a COSE_Key map: EC2, ES256, P-256, x and y. */
    fun coseKey(publicKey: ECPublicKey): ByteArray {
        fun coord(v: BigInteger): ByteArray {
            val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - raw.size) + raw
        }
        return Cbor().map(5)
            .int(1).int(2) // kty: EC2
            .int(3).int(ES256) // alg
            .int(-1).int(1) // crv: P-256
            .int(-2).bytes(coord(publicKey.w.affineX))
            .int(-3).bytes(coord(publicKey.w.affineY))
            .toByteArray()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * Authenticator data: SHA-256 of the site, flags (user present, user verified, and for a new
     * passkey "attested credential data"), the signature counter and, when registering, the
     * credential id and public key (AAGUID all zero: no attestation).
     */
    fun authenticatorData(rpId: String, signCount: Long, credentialId: ByteArray? = null, coseKey: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(sha256(rpId.toByteArray(Charsets.UTF_8)))
        out.write(FLAG_UP or FLAG_UV or (if (credentialId != null) FLAG_AT else 0))
        for (s in 24 downTo 0 step 8) out.write(((signCount shr s) and 0xff).toInt())
        if (credentialId != null && coseKey != null) {
            out.write(ByteArray(16)) // AAGUID
            out.write(credentialId.size shr 8)
            out.write(credentialId.size and 0xff)
            out.write(credentialId)
            out.write(coseKey)
        }
        return out.toByteArray()
    }

    fun attestationObject(authData: ByteArray): ByteArray =
        Cbor().map(3).text("fmt").text("none").text("attStmt").map(0).text("authData").bytes(authData).toByteArray()

    /** ES256 signature (DER encoded, as WebAuthn expects) over authenticator data + client data hash. */
    fun sign(privateKey: PrivateKey, authData: ByteArray, clientDataHash: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(authData)
            update(clientDataHash)
            sign()
        }

    fun clientDataJson(type: String, challenge: String, origin: String, packageName: String?): ByteArray =
        JSONObject()
            .put("type", type)
            .put("challenge", challenge)
            .put("origin", origin)
            .apply { if (packageName != null) put("androidPackageName", packageName) }
            .toString().toByteArray(Charsets.UTF_8)

    fun registrationResponse(credentialId: ByteArray, clientDataJson: ByteArray?, attestationObject: ByteArray, authData: ByteArray, publicKey: ECPublicKey): String {
        val id = B64.url(credentialId)
        return JSONObject()
            .put("id", id)
            .put("rawId", id)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put(
                "response",
                JSONObject()
                    // A browser that passed its own client data hash fills clientDataJSON in itself.
                    .put("clientDataJSON", B64.url(clientDataJson ?: "{}".toByteArray()))
                    .put("attestationObject", B64.url(attestationObject))
                    .put("authenticatorData", B64.url(authData))
                    .put("publicKey", B64.url(publicKey.encoded))
                    .put("publicKeyAlgorithm", ES256)
                    .put("transports", JSONArray().put("internal").put("hybrid"))
            )
            .put("clientExtensionResults", JSONObject().put("credProps", JSONObject().put("rk", true)))
            .toString()
    }

    fun authenticationResponse(credentialId: String, clientDataJson: ByteArray?, authData: ByteArray, signature: ByteArray, userHandle: String): String =
        JSONObject()
            .put("id", credentialId)
            .put("rawId", credentialId)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put(
                "response",
                JSONObject()
                    .put("clientDataJSON", B64.url(clientDataJson ?: "{}".toByteArray()))
                    .put("authenticatorData", B64.url(authData))
                    .put("signature", B64.url(signature))
                    .put("userHandle", userHandle)
            )
            .put("clientExtensionResults", JSONObject())
            .toString()
}
