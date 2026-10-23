package com.dev.Tvivo.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dev.Tvivo.source.SourceId
import com.dev.Tvivo.source.drive.RefreshTokenStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.sourcesDataStore by preferencesDataStore(name = "tvivo_sources")

enum class SourceKind { XTREAM, DRIVE }

data class SavedSource(
    val id: String,
    val kind: SourceKind,
    val credentials: Credentials? = null,
    val permissionId: String? = null,
    val refreshToken: String? = null
) {
    override fun toString(): String = "SavedSource(id=$id, kind=$kind, secrets=redacted)"

    companion object {
        fun xtream(credentials: Credentials) = SavedSource(
            AccountIdentity.of(credentials), SourceKind.XTREAM, credentials = credentials
        )
        fun drive(permissionId: String, refreshToken: String?) = SavedSource(
            SourceId.drive(permissionId).value, SourceKind.DRIVE,
            permissionId = permissionId, refreshToken = refreshToken
        )
    }
}

sealed interface SourceSession {
    val id: String
    data class Xtream(override val id: String, val credentials: Credentials) : SourceSession
    data class Drive(override val id: String, val permissionId: String) : SourceSession
}

data class SourceSnapshot(val sources: List<SavedSource>, val activeId: String?) {
    val active: SavedSource? get() = sources.firstOrNull { it.id == activeId }
    override fun toString(): String = "SourceSnapshot(count=${sources.size}, activeId=$activeId)"
}

/** Atomic encrypted-blob transaction. The Android implementation uses one DataStore edit. */
interface SourceBlobStore {
    suspend fun read(): String?
    suspend fun update(change: (String?) -> String?): String?
}

private class DataStoreSourceBlobStore(private val context: Context) : SourceBlobStore {
    private val key = stringPreferencesKey("sources_blob")
    override suspend fun read() = context.sourcesDataStore.data.first()[key]
    override suspend fun update(change: (String?) -> String?): String? {
        var result: String? = null
        context.sourcesDataStore.edit { prefs ->
            result = change(prefs[key])
            if (result == null) prefs.remove(key) else prefs[key] = result!!
        }
        return result
    }
}

/** Whole-snapshot encryption keeps the active pointer and source list in one atomic edit. */
class SourceStore(
    private val blobs: SourceBlobStore,
    private val cipher: Aead,
    private val legacy: suspend () -> Credentials?
) {
    private val gson = Gson()
    private data class Envelope(
        val version: Int,
        val sources: List<SavedSource>,
        val activeId: String?,
        val legacyMigrated: Boolean = false
    )

    companion object {
        private val associatedData = "tvivo_sources_v1".toByteArray()

        suspend fun open(context: Context): SourceStore = withContext(Dispatchers.IO) {
            AeadConfig.register()
            // Reuse the existing keyset. It must never be cleared by source disconnect or sign-out.
            val aead = AndroidKeysetManager.Builder()
                .withSharedPref(context, "tvivo_credentials_keyset", "tvivo_tink_keyset")
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://tvivo_credentials_master_key")
                .build().keysetHandle.getPrimitive(Aead::class.java)
            SourceStore(DataStoreSourceBlobStore(context.applicationContext), aead) {
                CredentialsStore(context.applicationContext).load()
            }
        }
    }

    private fun decode(raw: String?): Envelope {
        if (raw == null) return Envelope(1, emptyList(), null)
        val bytes = cipher.decrypt(Base64.decode(raw, Base64.NO_WRAP), associatedData)
        val data = gson.fromJson(String(bytes, Charsets.UTF_8), Envelope::class.java)
        require(data.version == 1 && data.sources.distinctBy { it.id }.size == data.sources.size) {
            "Invalid source store"
        }
        require(data.activeId == null || data.sources.any { it.id == data.activeId })
        return data
    }

    private fun encode(data: Envelope): String = Base64.encodeToString(
        cipher.encrypt(gson.toJson(data).toByteArray(Charsets.UTF_8), associatedData),
        Base64.NO_WRAP
    )

    suspend fun load(): SourceSnapshot {
        migrateLegacy()
        val data = decode(blobs.read())
        return SourceSnapshot(data.sources, data.activeId)
    }

    /** The legacy blob is intentionally retained until a later, separately reviewed removal. */
    suspend fun migrateLegacy() {
        if (decode(blobs.read()).legacyMigrated) return
        val credentials = legacy() ?: return
        val source = SavedSource.xtream(credentials)
        blobs.update { raw ->
            val data = decode(raw)
            if (data.legacyMigrated) raw
            else encode(data.copy(
                sources = if (data.sources.any { it.id == source.id }) data.sources else data.sources + source,
                activeId = data.activeId ?: source.id,
                legacyMigrated = true
            ))
        }
        check(decode(blobs.read()).legacyMigrated) { "Source migration did not read back" }
    }

    /** Validation runs before the encrypted transaction; failed or cancelled validation changes nothing. */
    suspend fun stageSwitch(candidate: SavedSource, validate: suspend (SavedSource) -> Boolean): Boolean {
        migrateLegacy()
        if (!validate(candidate)) return false
        when (candidate.kind) {
            SourceKind.XTREAM -> require(candidate.credentials != null &&
                candidate.id == AccountIdentity.of(candidate.credentials))
            SourceKind.DRIVE -> require(candidate.permissionId != null &&
                candidate.id == SourceId.drive(candidate.permissionId).value)
        }
        blobs.update { raw ->
            val data = decode(raw)
            encode(data.copy(
                sources = data.sources.filterNot { it.id == candidate.id } + candidate,
                activeId = candidate.id
            ))
        }
        return true
    }

    /** Local stop leaves the saved source and its grant intact for later re-selection. */
    suspend fun stopUsing(sourceId: String) {
        blobs.update { raw ->
            val data = decode(raw)
            encode(data.copy(activeId = if (data.activeId == sourceId) null else data.activeId))
        }
    }

    /** Remove local grant even if remote revocation failed; caller displays that failure. */
    suspend fun disconnect(sourceId: String, revoke: suspend () -> Boolean): Boolean {
        val revoked = runCatching { revoke() }.getOrDefault(false)
        blobs.update { raw ->
            val data = decode(raw)
            encode(data.copy(
                sources = data.sources.filterNot { it.id == sourceId },
                activeId = if (data.activeId == sourceId) null else data.activeId
            ))
        }
        return revoked
    }

    /** Removes all local source grants without loading or importing legacy credentials. */
    suspend fun clearAll() {
        blobs.update { raw ->
            val data = decode(raw)
            encode(data.copy(sources = emptyList(), activeId = null))
        }
        check(loadWithoutMigration().sources.isEmpty()) { "Source store clear did not read back" }
    }

    private suspend fun loadWithoutMigration(): SourceSnapshot {
        val data = decode(blobs.read())
        return SourceSnapshot(data.sources, data.activeId)
    }

    fun refreshTokens(sourceId: String): RefreshTokenStore = object : RefreshTokenStore {
        override suspend fun load(): String? = this@SourceStore.load().sources
            .firstOrNull { it.id == sourceId && it.kind == SourceKind.DRIVE }?.refreshToken

        override suspend fun save(token: String?) {
            blobs.update { raw ->
                val data = decode(raw)
                require(data.sources.any { it.id == sourceId && it.kind == SourceKind.DRIVE })
                encode(data.copy(sources = data.sources.map {
                    if (it.id == sourceId) it.copy(refreshToken = token) else it
                }))
            }
        }
    }
}
