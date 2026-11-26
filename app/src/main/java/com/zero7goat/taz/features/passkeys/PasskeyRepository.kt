package com.zero7goat.taz.features.passkeys

import android.content.Context
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.security.FieldCipher
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thrown when the request can't be answered; the message is safe to show. */
class PasskeyException(message: String) : Exception(message)

/**
 * Creates passkeys and signs in with them. Private keys are generated here, encrypted with the
 * Keystore key straight away, and decrypted only for the moment a signature is made.
 *
 * [origin] is the web origin from a browser, or "android:apk-key-hash:…" for an app.
 * [clientDataHash] is given by browsers that build the client data themselves; otherwise TAZ
 * builds it from [origin].
 */
class PasskeyRepository(private val dao: PasskeyDao, private val cipher: FieldCipher, private val now: () -> Long = { System.currentTimeMillis() }) {

    suspend fun create(requestJson: String, origin: String, packageName: String?, clientDataHash: ByteArray?): String = withContext(Dispatchers.Default) {
        val options = try {
            WebAuthn.parseCreation(requestJson)
        } catch (e: Exception) {
            throw PasskeyException("This sign-up request couldn't be read.")
        }
        if (!options.supportsEs256) throw PasskeyException("This site needs a kind of passkey TAZ can't make.")
        if (options.excludeCredentialIds.any { dao.byCredentialId(it) != null }) {
            throw PasskeyException("TAZ already has a passkey for this account.")
        }
        val keys = WebAuthn.newKeyPair()
        val credentialId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = UUID.randomUUID().toString()
        val publicKey = keys.public as ECPublicKey
        val cose = WebAuthn.coseKey(publicKey)
        val authData = WebAuthn.authenticatorData(options.rpId, 0, credentialId, cose)
        val clientData = if (clientDataHash == null) WebAuthn.clientDataJson("webauthn.create", options.challenge, origin, packageName) else null
        val pkcs8 = keys.private.encoded
        try {
            dao.upsert(
                PasskeyEntity(
                    id = id,
                    credentialId = B64.url(credentialId),
                    rpId = options.rpId,
                    userName = options.userName,
                    userDisplayName = options.userDisplayName,
                    userHandle = options.userId,
                    privateKeyCipher = cipher.encrypt(Base64.getEncoder().encodeToString(pkcs8), aad(id)),
                    signCount = 0,
                    createdAt = now(),
                    lastUsedAt = now()
                )
            )
        } finally {
            pkcs8.fill(0)
        }
        WebAuthn.registrationResponse(credentialId, clientData, WebAuthn.attestationObject(authData), authData, publicKey)
    }

    /** Signs the request with the passkey [credentialId] (Base64url). */
    suspend fun authenticate(credentialId: String, requestJson: String, origin: String, packageName: String?, clientDataHash: ByteArray?): String =
        withContext(Dispatchers.Default) {
            val passkey = dao.byCredentialId(credentialId) ?: throw PasskeyException("This passkey is no longer in TAZ.")
            val options = try {
                WebAuthn.parseRequest(requestJson)
            } catch (e: Exception) {
                throw PasskeyException("This sign-in request couldn't be read.")
            }
            if (options.rpId.isNotBlank() && options.rpId != passkey.rpId) throw PasskeyException("This passkey belongs to another site.")
            val count = passkey.signCount + 1
            val authData = WebAuthn.authenticatorData(passkey.rpId, count)
            val clientData = if (clientDataHash == null) WebAuthn.clientDataJson("webauthn.get", options.challenge, origin, packageName) else null
            val hash = clientDataHash ?: WebAuthn.sha256(clientData!!)
            val pkcs8 = Base64.getDecoder().decode(cipher.decrypt(passkey.privateKeyCipher, aad(passkey.id)))
            val signature = try {
                WebAuthn.sign(WebAuthn.privateKeyFrom(pkcs8), authData, hash)
            } finally {
                pkcs8.fill(0)
            }
            dao.markUsed(passkey.id, count, now())
            WebAuthn.authenticationResponse(passkey.credentialId, clientData, authData, signature, passkey.userHandle)
        }

    /** Passkeys for [rpId], or (when the site names some) only those it allows. */
    suspend fun candidates(rpId: String, allowed: List<String>): List<PasskeyEntity> =
        dao.forRp(rpId).filter { allowed.isEmpty() || it.credentialId in allowed }

    companion object {
        // DO NOT CHANGE: stored passkeys depend on this exact value.
        fun aad(id: String) = "taz.passkey.v1|$id|key"

        fun create(context: Context) =
            PasskeyRepository(TazDatabase.getInstance(context.applicationContext).passkeyDao(), KeystoreFieldCipher())
    }
}
