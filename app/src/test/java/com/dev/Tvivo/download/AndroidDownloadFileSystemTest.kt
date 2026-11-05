package com.dev.Tvivo.download

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDownloadFileSystemTest {
    @Test fun playableFileRejectsTraversalAndMissingOrEmptyFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fs = AndroidDownloadFileSystem(context)
        assertNull(fs.playableFile("../outside.mp4"))
        assertNull(fs.playableFile("missing/movie.mp4"))
        fs.finalFile("movie/empty.mp4")!!.apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf()) }
        assertNull(fs.playableFile("movie/empty.mp4"))
        fs.finalFile("movie/ready.mp4")!!.apply { writeBytes(byteArrayOf(1)) }
        assertNotNull(fs.playableFile("movie/ready.mp4"))
    }
}
