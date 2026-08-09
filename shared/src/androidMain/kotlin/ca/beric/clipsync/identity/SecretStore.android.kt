@file:OptIn(ExperimentalEncodingApi::class)

package ca.beric.clipsync.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Android Keystore-backed secret storage. A non-exportable AES key lives in the
 * hardware-backed Keystore; secrets are AES-GCM encrypted with it and the
 * (iv || ciphertext) blob is kept in private SharedPreferences. The raw secret
 * never leaves the process in plaintext and the AES key never leaves Keystore.
 */
actual class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("clipsync_secrets", Context.MODE_PRIVATE)

    actual fun put(alias: String, secret: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, aesKey()) }
        val iv = cipher.iv
        val ct = cipher.doFinal(secret)
        prefs.edit().putString(alias, Base64.encode(iv + ct)).apply()
    }

    actual fun get(alias: String): ByteArray? {
        val blob = prefs.getString(alias, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(blob)
            val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
            val ct = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORM)
                .apply { init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
            cipher.doFinal(ct)
        }.getOrNull()
    }

    actual fun delete(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "clipsync_secret_wrapping_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
