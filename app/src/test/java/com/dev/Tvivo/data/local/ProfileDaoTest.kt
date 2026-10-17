package com.dev.Tvivo.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.data.local.entities.FavouriteEntity
import com.dev.Tvivo.data.local.entities.defaultProfileId
import com.dev.Tvivo.data.local.entities.PROFILE_ADULT
import com.dev.Tvivo.data.local.entities.PROFILE_KIDS
import com.dev.Tvivo.data.local.entities.ProfileAllowEntity
import com.dev.Tvivo.data.local.entities.ProfileEntity
import com.dev.Tvivo.data.local.entities.RecentlyPlayedEntity
import com.dev.Tvivo.data.local.entities.ResumePositionEntity
import com.dev.Tvivo.data.local.entities.ShelfVisitEntity
import com.dev.Tvivo.data.local.entities.TYPE_VOD
import com.dev.Tvivo.data.local.entities.WatchedEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun legacy_favourite_and_resume_calls_use_only_the_default_adult_profile() = runTest {
        val account = "account-a"
        db.favouriteDao().add(FavouriteEntity(account, TYPE_VOD, "same-id", 100))
        assertNotNull(db.profileDao().get(account, account))
        db.profileDao().insert(ProfileEntity("kid-1", account, "Child", PROFILE_KIDS, 200))
        db.favouriteDao().add(FavouriteEntity(account, TYPE_VOD, "same-id", 300, "kid-1"))

        assertEquals(100L, db.favouriteDao().observe(account, TYPE_VOD).first().single().addedAt)
        assertEquals(300L, db.favouriteDao().observeForProfile(account, "kid-1", TYPE_VOD).first().single().addedAt)
        db.favouriteDao().remove(account, TYPE_VOD, "same-id")
        assertFalse(db.favouriteDao().isFavourite(account, TYPE_VOD, "same-id").first())
        assertTrue(db.favouriteDao().isFavouriteForProfile(account, "kid-1", TYPE_VOD, "same-id").first())

        db.resumeDao().upsert(ResumePositionEntity(account, TYPE_VOD, "same-id", 120_000, 900_000, 100))
        db.resumeDao().upsert(ResumePositionEntity(account, TYPE_VOD, "same-id", 220_000, 900_000, 200, "kid-1"))
        assertEquals(120_000L, db.resumeDao().get(account, TYPE_VOD, "same-id")?.positionMs)
        assertEquals(220_000L, db.resumeDao().getForProfile(account, "kid-1", TYPE_VOD, "same-id")?.positionMs)
        db.resumeDao().remove(account, TYPE_VOD, "same-id")
        assertNull(db.resumeDao().get(account, TYPE_VOD, "same-id"))
        assertNotNull(db.resumeDao().getForProfile(account, "kid-1", TYPE_VOD, "same-id"))
    }

    @Test
    fun new_tables_round_trip_with_account_and_profile_isolation() = runTest {
        db.profileDao().ensureAdult("account-a", 1)
        db.profileDao().ensureAdult("account-b", 2)
        db.profileDao().insert(ProfileEntity("kid-1", "account-a", "Child", PROFILE_KIDS, 3))

        val item = ProfileAllowEntity("kid-1", "account-a", TYPE_VOD, "item:42", "42", null)
        val category = ProfileAllowEntity("kid-1", "account-a", TYPE_VOD, "category:42", "", "42")
        db.profileAllowDao().upsert(item)
        db.profileAllowDao().upsert(category)
        assertEquals(setOf(item, category), db.profileAllowDao().observe("account-a", "kid-1").first().toSet())
        assertTrue(db.profileAllowDao().observe("account-b", "kid-1").first().isEmpty())
        db.profileAllowDao().remove("account-a", "kid-1", TYPE_VOD, "item:42")
        assertEquals(listOf(category), db.profileAllowDao().observe("account-a", "kid-1").first())

        db.watchedDao().mark(WatchedEntity("kid-1", "account-a", TYPE_VOD, "42", 10))
        assertEquals(10L, db.watchedDao().get("account-a", "kid-1", TYPE_VOD, "42")?.watchedAt)
        assertNull(db.watchedDao().get("account-a", "account-a", TYPE_VOD, "42"))
        assertNull(db.watchedDao().get("account-b", "kid-1", TYPE_VOD, "42"))
        db.watchedDao().unmark("account-a", "kid-1", TYPE_VOD, "42")
        assertNull(db.watchedDao().get("account-a", "kid-1", TYPE_VOD, "42"))

        db.visitDao().record("account-a", "kid-1", TYPE_VOD, "42", 20)
        db.visitDao().record("account-a", "kid-1", TYPE_VOD, "42", 30)
        assertEquals(2L, db.visitDao().get("account-a", "kid-1", TYPE_VOD, "42")?.count)
        assertEquals(30L, db.visitDao().mostVisited("account-a", "kid-1", TYPE_VOD).first().single().lastVisitedAt)
        assertNull(db.visitDao().get("account-b", "account-b", TYPE_VOD, "42"))

        assertNull(db.shelfVisitDao().get("account-a", "kid-1", TYPE_VOD, "42"))
        assertEquals(39L, db.shelfVisitDao().lastVisitOrNow("account-a", "kid-1", TYPE_VOD, "42", 39))
        db.shelfVisitDao().record(ShelfVisitEntity("kid-1", "account-a", TYPE_VOD, "42", 40))
        assertEquals(40L, db.shelfVisitDao().get("account-a", "kid-1", TYPE_VOD, "42")?.lastVisitedAt)
        assertEquals(40L, db.shelfVisitDao().lastVisitOrNow("account-a", "kid-1", TYPE_VOD, "42", 50))

        db.recentlyPlayedDao().record(RecentlyPlayedEntity("kid-1", "account-a", TYPE_VOD, "42", 50))
        db.recentlyPlayedDao().record(RecentlyPlayedEntity("kid-1", "account-a", TYPE_VOD, "43", 60))
        assertEquals(listOf("43", "42"), db.recentlyPlayedDao().recent("account-a", "kid-1", TYPE_VOD, 2).first().map { it.itemId })
        assertTrue(db.recentlyPlayedDao().recent("account-b", "account-b", TYPE_VOD, 2).first().isEmpty())
    }

    @Test
    fun default_profile_id_matches_legacy_sql_for_empty_and_odd_accounts() {
        listOf("", "account-a", "apostrophe' / ☺ :?").forEach { account ->
            assertEquals(account, defaultProfileId(account))
        }
    }

    @Test
    fun empty_and_odd_account_ids_round_trip_on_new_writes() = runTest {
        listOf("", "odd ' / ☺ :?").forEach { account ->
            db.favouriteDao().add(FavouriteEntity(account, TYPE_VOD, "item", 1))
            db.resumeDao().upsert(ResumePositionEntity(account, TYPE_VOD, "item", 2, 10, 3))
            assertEquals(account, db.profileDao().get(account, account)?.id)
            assertEquals(account, db.favouriteDao().observe(account, TYPE_VOD).first().single().profileId)
            assertEquals(account, db.resumeDao().get(account, TYPE_VOD, "item")?.profileId)
        }
    }

    @Test
    fun new_account_can_write_each_profile_owned_table_without_prior_user_data() = runTest {
        val account = "new-account"
        assertNull(db.profileDao().get(account, account))
        db.profileAllowDao().upsert(ProfileAllowEntity(account, account, TYPE_VOD, "item:1", "1", null))
        db.watchedDao().mark(WatchedEntity(account, account, TYPE_VOD, "1", 1))
        db.visitDao().record(account, account, TYPE_VOD, "category", 2)
        db.shelfVisitDao().record(ShelfVisitEntity(account, account, TYPE_VOD, "category", 3))
        db.recentlyPlayedDao().record(RecentlyPlayedEntity(account, account, TYPE_VOD, "1", 4))
        assertEquals("Adult", db.profileDao().get(account, account)?.kind)
        assertEquals(1, db.profileAllowDao().observe(account, account).first().size)
        assertNotNull(db.watchedDao().get(account, account, TYPE_VOD, "1"))
        assertEquals(1L, db.visitDao().get(account, account, TYPE_VOD, "category")?.count)
        assertNotNull(db.shelfVisitDao().get(account, account, TYPE_VOD, "category"))
        assertEquals(1, db.recentlyPlayedDao().recent(account, account, TYPE_VOD, 10).first().size)
    }

    @Test
    fun unknown_profile_writes_fail_with_clear_argument_error() = runTest {
        val account = "account-a"
        val missing = "missing"
        val writes: List<suspend () -> Unit> = listOf(
            { db.profileAllowDao().upsert(ProfileAllowEntity(missing, account, TYPE_VOD, "item:1", "1", null)) },
            { db.watchedDao().mark(WatchedEntity(missing, account, TYPE_VOD, "1", 1)) },
            { db.visitDao().record(account, missing, TYPE_VOD, "cat", 1) },
            { db.shelfVisitDao().record(ShelfVisitEntity(missing, account, TYPE_VOD, "cat", 1)) },
            { db.recentlyPlayedDao().record(RecentlyPlayedEntity(missing, account, TYPE_VOD, "1", 1)) },
            { db.favouriteDao().add(FavouriteEntity(account, TYPE_VOD, "1", 1, missing)) },
            { db.resumeDao().upsert(ResumePositionEntity(account, TYPE_VOD, "1", 1, 10, 1, missing)) }
        )
        writes.forEach { write ->
            try {
                write()
                org.junit.Assert.fail("Unknown profile write succeeded")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("Unknown profileId"))
            }
        }
        // Failed transactions must not leave even the newly inserted default adult behind.
        assertNull(db.profileDao().get(account, account))
    }

    @Test
    fun deleting_kids_cascades_all_children_and_refuses_adult() = runTest {
        val account = "account-a"
        val kid = "kid-1"
        db.profileDao().ensureAdult(account, 1)
        db.profileDao().insert(ProfileEntity(kid, account, "Child", PROFILE_KIDS, 2))
        db.profileDao().insert(ProfileEntity("adult-2", account, "Adult 2", PROFILE_ADULT, 3))
        db.profileAllowDao().upsert(ProfileAllowEntity(kid, account, TYPE_VOD, "item:1", "1", null))
        db.watchedDao().mark(WatchedEntity(kid, account, TYPE_VOD, "1", 1))
        db.visitDao().record(account, kid, TYPE_VOD, "cat", 1)
        db.shelfVisitDao().record(ShelfVisitEntity(kid, account, TYPE_VOD, "cat", 1))
        db.recentlyPlayedDao().record(RecentlyPlayedEntity(kid, account, TYPE_VOD, "1", 1))
        db.favouriteDao().add(FavouriteEntity(account, TYPE_VOD, "1", 1, kid))
        db.resumeDao().upsert(ResumePositionEntity(account, TYPE_VOD, "1", 1, 10, 1, kid))
        db.favouriteDao().add(FavouriteEntity(account, TYPE_VOD, "adult", 1))
        db.resumeDao().upsert(ResumePositionEntity(account, TYPE_VOD, "adult", 1, 10, 1))

        assertFalse(db.profileDao().deleteProfile(account, account))
        assertFalse(db.profileDao().deleteProfile(account, "adult-2"))
        assertNotNull(db.profileDao().get(account, "adult-2"))
        assertNotNull(db.favouriteDao().observe(account, TYPE_VOD).first().singleOrNull())
        assertTrue(db.profileDao().deleteProfile(account, kid))
        assertFalse(db.profileDao().deleteProfile(account, kid))
        assertNull(db.profileDao().get(account, kid))
        assertTrue(db.profileAllowDao().observe(account, kid).first().isEmpty())
        assertNull(db.watchedDao().get(account, kid, TYPE_VOD, "1"))
        assertNull(db.visitDao().get(account, kid, TYPE_VOD, "cat"))
        assertNull(db.shelfVisitDao().get(account, kid, TYPE_VOD, "cat"))
        assertTrue(db.recentlyPlayedDao().recent(account, kid, TYPE_VOD, 10).first().isEmpty())
        assertTrue(db.favouriteDao().observeForProfile(account, kid, TYPE_VOD).first().isEmpty())
        assertNull(db.resumeDao().getForProfile(account, kid, TYPE_VOD, "1"))
        assertNotNull(db.favouriteDao().observe(account, TYPE_VOD).first().singleOrNull())
        assertNotNull(db.resumeDao().get(account, TYPE_VOD, "adult"))
    }
}
