package com.dev.Tvivo.ui.detail

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.local.AppDatabase
import com.dev.Tvivo.data.local.entities.VodStreamEntity
import com.dev.Tvivo.ui.home.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemDetailViewModelTest {
    private lateinit var db: AppDatabase
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun movieDetailUsesAuthenticatedRouteWhenLegacyCredentialBlobIsAbsent() = runBlocking {
        val credentials = Credentials("example.test", 8089, "demo", "demo", false)
        db.vodDao().insertAll(listOf(VodStreamEntity(
            accountId = AccountIdentity.of(credentials), streamId = 1, categoryId = "movies",
            name = "Synthetic Film 01", nameDisplay = "Synthetic Film 01",
            nameNormalized = "synthetic film 01", streamIcon = null, containerExtension = "mp4",
            plot = "Mock film", rating = null, added = null, num = null, generation = 1
        )))

        val model = ItemDetailViewModel(app, ContentType.MOVIES, 1, credentials, db)
        val loaded = withTimeout(5_000) { model.state.first { !it.isLoading } }
        assertFalse(loaded.notFound)
        assertEquals("Synthetic Film 01", loaded.title)
        assertEquals("mp4", loaded.extension)
    }
}
