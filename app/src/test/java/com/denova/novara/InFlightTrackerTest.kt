package com.denova.novara

import com.denova.novara.core.network.InFlightTracker
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G1「退出时等待进行中读取」：在途计数与等待语义。 */
class InFlightTrackerTest {

    @Test
    fun `begin and end track concurrent calls`() {
        val tracker = InFlightTracker()
        val a = Any()
        val b = Any()
        tracker.begin(a)
        tracker.begin(b)
        assertEquals(2, tracker.count)
        tracker.end(a)
        assertEquals(1, tracker.count)
        tracker.end(b)
        assertEquals(0, tracker.count)
    }

    @Test
    fun `end is idempotent per call so callEnd plus callFailed cannot underflow`() {
        val tracker = InFlightTracker()
        val call = Any()
        tracker.begin(call)
        tracker.end(call)
        tracker.end(call)
        assertEquals(0, tracker.count)
    }

    @Test
    fun `awaitIdle returns true once the last call finishes`() = runBlocking {
        val tracker = InFlightTracker()
        val call = Any()
        tracker.begin(call)
        val waiter = async { tracker.awaitIdle(1_000) }
        delay(60)
        tracker.end(call)
        assertTrue(waiter.await())
    }

    @Test
    fun `awaitIdle times out when a long lived stream stays open`() = runBlocking {
        val tracker = InFlightTracker()
        tracker.begin(Any())
        val started = System.currentTimeMillis()
        val drained = tracker.awaitIdle(150)
        assertFalse(drained)
        assertTrue(System.currentTimeMillis() - started >= 120)
        assertEquals(1, tracker.count)
    }
}
