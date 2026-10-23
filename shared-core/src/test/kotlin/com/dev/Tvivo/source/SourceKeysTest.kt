package com.dev.Tvivo.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceKeysTest {
    @Test fun xtreamPartitionPreservesLegacyKey() {
        val legacy = "0123456789abcdef0123456789abcdef"
        assertEquals(legacy, SourceId.xtream(legacy).value)
        assertThrows(IllegalArgumentException::class.java) { SourceId.xtream("xtream:$legacy") }
    }

    @Test fun drivePartitionIsStableDistinctAndOpaque() {
        val first = SourceId.drive("opaque-permission-id")
        assertEquals(first, SourceId.drive("opaque-permission-id"))
        assertFalse(first == SourceId.drive("other-permission-id"))
        assertTrue(first.value.matches(Regex("drive:[0-9a-f]{32}")))
        assertFalse(first.value.contains("opaque-permission-id"))
        assertThrows(IllegalArgumentException::class.java) { SourceId.drive(" ") }
    }

    @Test fun itemKeysKeepProviderIdsWithoutTitleOrUrl() {
        assertEquals("42", ItemKey.xtream(42).value)
        assertEquals("00042", ItemKey.xtreamEpisode("00042").value)
        val uuid = "d44bfc8e-4106-4a8a-8b69-b57c1d03486a"
        assertEquals(uuid, ItemKey.drive(uuid.uppercase()).value)
        assertThrows(IllegalArgumentException::class.java) { ItemKey.xtream(0) }
        assertThrows(IllegalArgumentException::class.java) { ItemKey.drive("Movie title") }
    }

    @Test fun accessTokenDoesNotPrintItsValue() {
        assertFalse(SourceAccessToken("secret").toString().contains("secret"))
    }
}
