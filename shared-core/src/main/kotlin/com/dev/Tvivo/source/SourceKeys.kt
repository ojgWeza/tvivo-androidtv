package com.dev.Tvivo.source

import java.security.MessageDigest
import java.util.UUID

/** Room account partition. Xtream keeps its existing AccountIdentity value unchanged. */
@JvmInline
value class SourceId private constructor(val value: String) {
    companion object {
        private val legacyAccountKey = Regex("[0-9a-f]{32}")

        fun xtream(accountKey: String): SourceId {
            require(legacyAccountKey.matches(accountKey)) { "Invalid Xtream account key" }
            return SourceId(accountKey)
        }

        /** Drive's opaque permission ID is never stored as a Room key. */
        fun drive(permissionId: String): SourceId {
            require(permissionId.isNotBlank() && permissionId == permissionId.trim()) {
                "Invalid Drive permission ID"
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(permissionId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return SourceId("drive:${digest.take(32)}")
        }
    }
}

/** Stable item ID within a source and content type; never derived from a title or URL. */
@JvmInline
value class ItemKey private constructor(val value: String) {
    companion object {
        fun xtream(id: Int): ItemKey {
            require(id > 0) { "Invalid Xtream item ID" }
            return ItemKey(id.toString())
        }

        /** Xtream episode IDs already have a string representation in the API. */
        fun xtreamEpisode(id: String): ItemKey {
            require(id.isNotBlank() && id == id.trim()) { "Invalid Xtream episode ID" }
            return ItemKey(id)
        }

        fun drive(uuid: String): ItemKey {
            val canonical = runCatching { UUID.fromString(uuid).toString() }.getOrNull()
            require(canonical != null && canonical == uuid.lowercase() && uuid.length == 36) {
                "Invalid Drive item ID"
            }
            return ItemKey(canonical)
        }
    }
}
