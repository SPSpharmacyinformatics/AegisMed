package com.aegismed.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object SecretVault {

    private const val FILE = "aegis_vault"
    private const val KEY_PASSPHRASE = "db_passphrase_b64"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun dbPassphrase(context: Context): ByteArray {
        val p = prefs(context)
        val existing = p.getString(KEY_PASSPHRASE, null)
        if (existing != null) {
            return android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        p.edit()
            .putString(KEY_PASSPHRASE, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            .commit()
        return bytes
    }
}
