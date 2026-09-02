package dev.kiro.android.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.kiro.core.auth.TokenStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore("kiro_tokens")

/**
 * AndroidKeyStore + DataStore, written by hand.
 *
 * **Not `androidx.security:security-crypto`.** That library was deprecated at
 * `1.1.0-alpha07` and its own guidance points at `AndroidKeyStore` directly, so
 * the wrapper is not a shortcut any more — it is a dependency on something that
 * is going away. ADR-003 §1 makes this explicit, and it is the correct amount of
 * work for a credential that can reach the user's repositories.
 *
 * The key is non-exportable: it lives in the Keystore, and this class only ever
 * holds ciphertext. Combined with `allowBackup=false` and the extraction rules,
 * that is what keeps tokens off cloud backup and out of device transfer (F-06).
 */
class KeystoreTokenStore(private val context: Context) : TokenStore {

    override suspend fun put(bridgeId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(token.toByteArray())
        // The IV is generated per encryption and is not a secret, but it is
        // required to decrypt -- so it is stored alongside rather than derived.
        val packed = cipher.iv + ciphertext
        context.tokenDataStore.edit { prefs ->
            prefs[stringPreferencesKey(bridgeId)] = Base64.encodeToString(packed, Base64.NO_WRAP)
        }
    }

    override suspend fun get(bridgeId: String): String? {
        val stored = context.tokenDataStore.data.first()[stringPreferencesKey(bridgeId)] ?: return null
        return runCatching {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext))
        }.getOrNull()
        // A failure here means the Keystore key is gone -- a factory reset, a
        // restored backup, or biometric enrolment invalidating it. Returning null
        // sends the user back to pairing, which is the correct outcome and is why
        // this does not throw.
    }

    override suspend fun remove(bridgeId: String) {
        context.tokenDataStore.edit { it.remove(stringPreferencesKey(bridgeId)) }
    }

    override suspend fun clear() {
        context.tokenDataStore.edit { it.clear() }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.kiro.android.bridge-tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
