package dev.neoneon.flamingo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import android.util.Base64

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val KEY_ALIAS = "flamingo_player_signing_key"
private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

/**
 * The P-256 keypair that authorizes editing this installation's profile.
 *
 * Generated inside the **Android Keystore**, so the private half is non-extractable — it cannot be
 * read out by this app or any other, and it is excluded from Android Backup.
 *
 * **This key is device-bound and there is no way around that.** Unlike iOS, where the key rides
 * iCloud Keychain to a new phone, a Keystore key cannot be exported by design and the usual escape
 * hatch — Play Services' Block Store — does not exist on a Light Phone III. Moving the key off the
 * Keystore to make it portable would throw away the hardware protection and gain nothing, since
 * there is no sync to carry it. So a new handset means a new key and a new profile; the only
 * migration path is `POST /flamingo/players/:id/key`, signed by the old device while it still
 * works. See `neoneon/docs/adr/ADR-001-signed-personal-data-edits.md`.
 */
internal class PlayerKeyStore {

    private val keyStore: KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    /**
     * The public half as base64 SPKI DER — the shape the server stores and the other two clients
     * produce. `PublicKey.encoded` is already SPKI DER, so nothing here parses ASN.1 by hand.
     *
     * Generates the keypair on first call. Returns null only if key generation fails outright,
     * which leaves the tool able to play but not to rename, and says so rather than crashing.
     */
    fun publicKeyBase64(): String? = runCatching {
        val entry = keyStore.getCertificate(KEY_ALIAS) ?: run {
            generate()
            keyStore.getCertificate(KEY_ALIAS)
        } ?: return null
        Base64.encodeToString(entry.publicKey.encoded, Base64.NO_WRAP)
    }.getOrNull()

    /**
     * Signs [payload] and returns the signature as base64 DER.
     *
     * `SHA256withECDSA` emits DER, which is exactly what swift-crypto's
     * `ECDSASignature(derRepresentation:)` accepts on the server — the formats were chosen so no
     * conversion is needed in either direction.
     */
    fun sign(payload: String): String? = runCatching {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey ?: return null
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
            update(payload.toByteArray(Charsets.UTF_8))
        }
        Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }.getOrNull()

    /**
     * Creates the keypair, preferring StrongBox and falling back to the TEE.
     *
     * StrongBox is optional hardware — [StrongBoxUnavailableException] is thrown on devices that
     * lack it, and the Light Phone III's silicon is not something to assume. The fallback key is
     * still hardware-backed by the TEE and just as non-extractable; only the tamper resistance
     * differs.
     *
     * `setUserAuthenticationRequired(false)`: the tool signs a rename without prompting for a
     * lock-screen credential. The key guards *whose profile this is*, not a payment.
     */
    private fun generate() {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE,
        )
        runCatching {
            generator.initialize(specBuilder().setIsStrongBoxBacked(true).build())
            generator.generateKeyPair()
        }.onFailure { error ->
            if (error !is StrongBoxUnavailableException) throw error
            generator.initialize(specBuilder().build())
            generator.generateKeyPair()
        }
    }

    private fun specBuilder() = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
    )
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(false)
}
