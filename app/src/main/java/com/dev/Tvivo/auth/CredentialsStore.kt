package com.dev.Tvivo.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.credentialsDataStore by preferencesDataStore(name = "tvivo_credentials")

/**
 * Credentials at rest, encrypted with Tink AEAD under an Android Keystore master key,
 * stored as an opaque blob in DataStore.
 *
 * Not `EncryptedSharedPreferences`: it is deprecated, does synchronous crypto on the
 * main thread, and has keyset-corruption crashes on exactly the OEMs most Android TV
 * boxes come from.
 *
 * Key lifecycle: the master key lives in the Android Keystore and can be invalidated
 * out from under us (factory reset, lock-screen change on some OEMs, backup restore
 * onto different hardware). Every read that fails to decrypt therefore [wipe]s and
 * returns null so the app re-prompts for login. It must never crash-loop on startup.
 */
class CredentialsStore(private val context: Context) {

    private val gson = Gson()

    private data class Stored(
        val host: String,
        val port: Int?,
        val username: String,
        val password: String,
        val useHttps: Boolean
    )

    private suspend fun aead(): Aead = withContext(Dispatchers.IO) {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    val credentials: Flow<Credentials?> =
        context.credentialsDataStore.data.map { prefs -> decode(prefs[BLOB]) }

    suspend fun load(): Credentials? = credentials.first()

    suspend fun save(credentials: Credentials) = withContext(Dispatchers.IO) {
        val stored = Stored(
            host = credentials.host,
            port = credentials.port,
            username = credentials.username,
            password = credentials.password,
            useHttps = credentials.useHttps
        )
        val cipherText = aead().encrypt(gson.toJson(stored).toByteArray(), ASSOCIATED_DATA)
        context.credentialsDataStore.edit { prefs ->
            prefs[BLOB] = Base64.encodeToString(cipherText, Base64.NO_WRAP)
        }
        Unit
    }

    suspend fun wipe() = withContext(Dispatchers.IO) {
        context.credentialsDataStore.edit { it.remove(BLOB) }
        context.getSharedPreferences(KEYSET_PREF_FILE, Context.MODE_PRIVATE)
            .edit().clear().apply()
        Unit
    }

    private suspend fun decode(encoded: String?): Credentials? {
        if (encoded.isNullOrEmpty()) return null
        return try {
            val plain = aead().decrypt(Base64.decode(encoded, Base64.NO_WRAP), ASSOCIATED_DATA)
            val stored = gson.fromJson(String(plain), Stored::class.java)
            Credentials(stored.host, stored.port, stored.username, stored.password, stored.useHttps)
        } catch (e: Exception) {
            // Invalidated key or corrupted keyset: drop everything and re-prompt.
            wipe()
            null
        }
    }

    private companion object {
        val BLOB: Preferences.Key<String> = stringPreferencesKey("credentials_blob")
        const val KEYSET_NAME = "tvivo_credentials_keyset"
        const val KEYSET_PREF_FILE = "tvivo_tink_keyset"
        const val MASTER_KEY_URI = "android-keystore://tvivo_credentials_master_key"
        val ASSOCIATED_DATA = "tvivo_credentials_v1".toByteArray()
    }
}
