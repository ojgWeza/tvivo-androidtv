package com.dev.Tvivo.data.remote

import com.dev.Tvivo.data.local.entities.TYPE_LIVE
import com.dev.Tvivo.data.local.entities.TYPE_SERIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quirk this exists for: `category_id` is a **string** on live/VOD categories and an
 * **int** on series objects. Left un-normalised it silently splits the grouped counts,
 * so it is asserted rather than assumed.
 */
class CategoryListParserTest {

    @Test
    fun `parses live categories in panel order`() {
        val json = """
            [
              { "category_id": "823", "category_name": "NEWS", "parent_id": 0 },
              { "category_id": "824", "category_name": "SPORT", "parent_id": 0 }
            ]
        """.trimIndent()

        val rows = CategoryListParser.parse(json, ACCOUNT, TYPE_LIVE)

        assertEquals(listOf("823", "824"), rows.map { it.categoryId })
        assertEquals(listOf("NEWS", "SPORT"), rows.map { it.name })
        // Ordering is the panel's, carried explicitly rather than relying on list order
        // surviving a round trip through Room.
        assertEquals(listOf(0, 1), rows.map { it.ordering })
        assertTrue(rows.all { it.type == TYPE_LIVE && it.accountId == ACCOUNT })
    }

    @Test
    fun `a numeric category_id normalises to the same string form`() {
        val json = """[{ "category_id": 770, "category_name": "DRAMA" }]"""
        assertEquals("770", CategoryListParser.parse(json, ACCOUNT, TYPE_SERIES).single().categoryId)
    }

    @Test
    fun `a category with no name falls back to its id rather than rendering blank`() {
        val json = """[{ "category_id": "5" }]"""
        assertEquals("5", CategoryListParser.parse(json, ACCOUNT, TYPE_LIVE).single().name)
    }

    @Test
    fun `an error object or malformed body is zero categories, not a crash`() {
        assertTrue(CategoryListParser.parse("""{"user_info":{}}""", ACCOUNT, TYPE_LIVE).isEmpty())
        assertTrue(CategoryListParser.parse("not json at all {", ACCOUNT, TYPE_LIVE).isEmpty())
        assertTrue(CategoryListParser.parse("", ACCOUNT, TYPE_LIVE).isEmpty())
    }

    private companion object {
        const val ACCOUNT = "acct-1"
    }
}
