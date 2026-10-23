package com.dev.Tvivo.auth

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceStoreTest {
    private class MemoryBlobs : SourceBlobStore {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun update(change: (String?) -> String?): String? {
            value = change(value)
            return value
        }
    }
    private fun cipher(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(Aead::class.java)
    }
    private val legacy = Credentials("example.invalid", 8080, "user", "password")

    @Test fun migratesLegacyAndKeepsItUntilReadback() = runBlocking {
        val blobs = MemoryBlobs(); val aead = cipher()
        // The old CredentialsStore JSON shape and associated data, encrypted under the same keyset.
        val oldCiphertext = aead.encrypt(
            Gson().toJson(mapOf(
                "host" to legacy.host, "port" to legacy.port,
                "username" to legacy.username, "password" to legacy.password,
                "useHttps" to legacy.useHttps
            )).toByteArray(), "tvivo_credentials_v1".toByteArray()
        )
        var oldBlobPresent = true
        val store = SourceStore(blobs, aead) {
            if (!oldBlobPresent) null else {
                val json = String(aead.decrypt(oldCiphertext, "tvivo_credentials_v1".toByteArray()))
                Gson().fromJson(json, Credentials::class.java)
            }
        }
        val first = store.load()
        assertTrue(oldBlobPresent)
        assertEquals(SavedSource.xtream(legacy).id, first.activeId)
        assertEquals(legacy, first.active?.credentials)
        assertFalse(blobs.value!!.contains("password"))
        oldBlobPresent = false
        assertEquals(first, store.load())
    }

    @Test fun failedStagedSwitchPreservesActiveAndDisconnectRemovesOnlyTarget() = runBlocking {
        val store = SourceStore(MemoryBlobs(), cipher()) { legacy }
        val prior = SavedSource.xtream(legacy).id
        val candidate = SavedSource.drive("permission-1", "refresh-secret")
        assertFalse(store.stageSwitch(candidate) { false })
        assertEquals(prior, store.load().activeId)
        assertTrue(store.stageSwitch(candidate) { true })
        assertEquals(candidate.id, store.load().activeId)
        assertEquals(2, store.load().sources.size)
        assertFalse(store.disconnect(candidate.id) { throw IllegalStateException("offline") })
        assertNull(store.load().activeId)
        assertEquals(listOf(prior), store.load().sources.map { it.id })
    }

    @Test fun tokenRotationAndLocalStop() = runBlocking {
        val store = SourceStore(MemoryBlobs(), cipher()) { null }
        val candidate = SavedSource.drive("permission-2", "initial")
        store.stageSwitch(candidate) { true }
        val tokens = store.refreshTokens(candidate.id)
        assertEquals("initial", tokens.load())
        tokens.save("rotated")
        assertEquals("rotated", tokens.load())
        store.stopUsing(candidate.id)
        assertNull(store.load().activeId)
        assertEquals("rotated", tokens.load())
    }
}
