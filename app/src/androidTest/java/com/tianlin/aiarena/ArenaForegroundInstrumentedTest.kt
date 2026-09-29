package com.tianlin.aiarena

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArenaForegroundInstrumentedTest {
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @After fun backToFront() = onMain { ArenaForeground.setVisible(true) }

    @Test fun deadlineWaitsInTheBackgroundWhileOrdinaryWorkKeepsRunning() {
        val handler = onMain { ArenaForegroundHandler(Looper.getMainLooper(), freezeAll = false) }
        val deadline = AtomicInteger(); val ordinary = AtomicInteger()
        onMain {
            handler.postDelayed(ArenaDeadline { deadline.incrementAndGet() }, 300L)
            handler.postDelayed({ ordinary.incrementAndGet() }, 300L)
            ArenaForeground.setVisible(false)
        }
        Thread.sleep(1_200)
        assertEquals("A deadline never fires while the App is in the background", 0, deadline.get())
        assertEquals("Reading answers keeps going in the background", 1, ordinary.get())
        onMain { ArenaForeground.setVisible(true) }
        Thread.sleep(1_800)
        assertEquals("After returning, the remaining time runs out once", 1, deadline.get())
    }

    @Test fun frozenPoolWorkKeepsItsRemainingTimeAndCanStillBeCancelled() {
        val handler = onMain { ArenaForegroundHandler(Looper.getMainLooper(), freezeAll = true) }
        val kept = AtomicInteger(); val cancelled = AtomicInteger()
        val cancel = Runnable { cancelled.incrementAndGet() }
        onMain {
            handler.postDelayed({ kept.incrementAndGet() }, 1_000L)
            handler.postDelayed(cancel, 200L)
            ArenaForeground.setVisible(false)
        }
        Thread.sleep(1_500)
        onMain { handler.removeCallbacks(cancel) }
        assertEquals(0, kept.get() + cancelled.get())
        val back = onMain { ArenaForeground.setVisible(true); ArenaForeground.elapsed() }
        Thread.sleep(400)
        assertEquals("Background time does not count: 1 s of foreground time is still left", 0, kept.get())
        while (kept.get() == 0 && ArenaForeground.elapsed() - back < 3_000L) Thread.sleep(50)
        assertEquals(1, kept.get())
        assertEquals("removeCallbacks cancels work that was waiting for the foreground", 0, cancelled.get())
    }

    @Test fun foregroundClockSkipsTimeSpentInTheBackground() {
        val start = onMain { ArenaForeground.elapsed() }
        onMain { ArenaForeground.setVisible(false) }
        Thread.sleep(600)
        val during = onMain { ArenaForeground.elapsed() }
        onMain { ArenaForeground.setVisible(true) }
        assertTrue("clock stands still in the background: ${during - start}", during - start < 150L)
    }
}
