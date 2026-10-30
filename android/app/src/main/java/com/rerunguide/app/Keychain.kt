package com.rerunguide.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Just enough Keychain to hold the auth tokens: values sealed with an AES/GCM key that never leaves the Android
 *  Keystore, kept in a private preferences file. A value that can't be opened (the key is gone, e.g. after a backup
 *  restore to another device) reads as missing, so the app asks to sign in again. Call [init] from MainActivity.onCreate. */
object Keychain {
    private const val service = "com.rerunguide.RerunGuide"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) { prefs = context.getSharedPreferences(service, Context.MODE_PRIVATE) }

    /** The Swift `set(_ value:, for key:)`. */
    fun set(value: String, key: String) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
            prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }.onFailure { if (BuildConfig.DEBUG) throw IllegalStateException("Keychain write failed", it) }
    }

    fun get(key: String): String? = runCatching {
        val sealed = Base64.decode(prefs.getString(key, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12)) }
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }.getOrNull()

    fun delete(key: String) { prefs.edit().remove(key).apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(service, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(service, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
}
