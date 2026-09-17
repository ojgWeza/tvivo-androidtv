package com.dev.tvivo.desktop

import com.sun.jna.Pointer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPlayerCloseTest {
    @Test
    fun closeFromNonOwnerThreadWaitsForOwnerTeardownAndSecondCloseIsPrompt() {
        val terminateStarted = CountDownLatch(1)
        val player = playerWithFakeMpv {
            terminateStarted.countDown()
        }

        val closeReturned = CountDownLatch(1)
        val closer = Thread {
            player.close()
            closeReturned.countDown()
        }
        closer.start()

        assertTrue("close() did not reach mpv teardown", terminateStarted.await(1, TimeUnit.SECONDS))
        assertTrue("close() did not return after owner teardown", closeReturned.await(1, TimeUnit.SECONDS))
        closer.join(1_000)
        assertFalse("close() thread is still running", closer.isAlive)
        assertNull(player.fieldValue("mpv"))
        assertNull(player.fieldValue("handle"))

        val secondCloseStarted = System.nanoTime()
        player.close()
        val secondCloseMs = (System.nanoTime() - secondCloseStarted) / 1_000_000
        assertTrue("second close() took ${secondCloseMs}ms", secondCloseMs < 500)
    }

    @Test
    fun closeReturnsAfterBoundedWaitWhenOwnerTeardownIsBlocked() {
        val terminateStarted = CountDownLatch(1)
        val releaseTerminate = CountDownLatch(1)
        val player = playerWithFakeMpv {
            terminateStarted.countDown()
            releaseTerminate.await()
        }

        val closeStarted = System.nanoTime()
        val closeReturned = CountDownLatch(1)
        val closer = Thread {
            player.close()
            closeReturned.countDown()
        }
        closer.start()

        assertTrue("owner never started teardown", terminateStarted.await(1, TimeUnit.SECONDS))
        assertTrue(
            "close() hung beyond its five-second bound",
            closeReturned.await(5_500, TimeUnit.MILLISECONDS),
        )
        val closeMs = (System.nanoTime() - closeStarted) / 1_000_000
        assertTrue("close() returned before exercising the timeout path: ${closeMs}ms", closeMs >= 4_500)
        assertTrue("close() exceeded its bounded wait: ${closeMs}ms", closeMs < 5_500)

        releaseTerminate.countDown()
        closer.join(1_000)
        assertFalse("close() thread is still running", closer.isAlive)
    }

    private fun playerWithFakeMpv(onTerminate: () -> Unit): MpvPlayer {
        val player = MpvPlayer(
            onState = { _, _ -> },
            onPositionMs = { _, _ -> },
        )
        player.setField("mpv", fakeMpv(onTerminate))
        player.setField("handle", Pointer(1L))
        player.fieldValue<Thread>("loopThread")!!.start()
        return player
    }

    private fun fakeMpv(onTerminate: () -> Unit): MpvLibrary {
        val terminated = AtomicBoolean(false)
        val handler = InvocationHandler { _, method, _ ->
            when (method.name) {
                "mpv_terminate_destroy" -> {
                    if (terminated.compareAndSet(false, true)) onTerminate()
                    null
                }
                "mpv_wait_event" -> null
                "mpv_error_string" -> "fake error"
                else -> when (method.returnType) {
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
        }
        return Proxy.newProxyInstance(
            MpvLibrary::class.java.classLoader,
            arrayOf(MpvLibrary::class.java),
            handler,
        ) as MpvLibrary
    }

    private fun Any.setField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setField, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.fieldValue(name: String): T? =
        javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(this) as T?
        }
}
