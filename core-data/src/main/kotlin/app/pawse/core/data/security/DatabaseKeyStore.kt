package app.pawse.core.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates the SQLCipher passphrase once, wraps it with an AES key held in the
 * Android keystore, and stores only the wrapped bytes.
 *
 * The passphrase never leaves the device and is never derived from anything the
 * user types, because there is no account to type into.
 */
@Singleton
class DatabaseKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun passphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WRAPPED, null)
        val storedIv = prefs.getString(KEY_IV, null)

        if (stored != null && storedIv != null) {
            return unwrap(
                wrapped = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP),
                iv = android.util.Base64.decode(storedIv, android.util.Base64.NO_WRAP),
            )
        }

        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val wrapped = cipher.doFinal(fresh)
        prefs.edit {
            putString(KEY_WRAPPED, android.util.Base64.encodeToString(wrapped, android.util.Base64.NO_WRAP))
            putString(KEY_IV, android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
        }
        return fresh
    }

    private fun unwrap(wrapped: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, iv)) }
            .doFinal(wrapped)

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Deliberately not setUserAuthenticationRequired: the nightly
                    // WorkManager recompute runs while the device is locked.
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pawse_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val PREFS = "pawse_secure"
        const val KEY_WRAPPED = "db_passphrase_wrapped"
        const val KEY_IV = "db_passphrase_iv"
    }
}
