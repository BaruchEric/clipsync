package ca.beric.clipsync.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LwwResolverTest {

    @Test
    fun firstUpdateIsAccepted() {
        val r = LwwResolver()
        assertTrue(r.accept(ClipVersion("a", 1, 100)))
        assertEquals(ClipVersion("a", 1, 100), r.current())
    }

    @Test
    fun newerWallClockWins() {
        val r = LwwResolver()
        r.accept(ClipVersion("a", 1, 100))
        assertTrue(r.accept(ClipVersion("b", 1, 200)))
        assertEquals("b", r.current()!!.deviceId)
    }

    @Test
    fun olderWallClockLoses() {
        val r = LwwResolver()
        r.accept(ClipVersion("a", 1, 200))
        assertFalse(r.accept(ClipVersion("b", 1, 100)))
        assertEquals("a", r.current()!!.deviceId)
    }

    @Test
    fun equalWallClockBreaksTieByDeviceIdDeterministically() {
        val r1 = LwwResolver()
        r1.accept(ClipVersion("a", 1, 100))
        assertTrue(r1.accept(ClipVersion("b", 1, 100))) // "b" > "a"
        assertEquals("b", r1.current()!!.deviceId)

        val r2 = LwwResolver()
        r2.accept(ClipVersion("b", 1, 100))
        assertFalse(r2.accept(ClipVersion("a", 1, 100))) // "a" < "b"
        assertEquals("b", r2.current()!!.deviceId)
    }

    @Test
    fun duplicateCounterFromSameDeviceIsRejected() {
        val r = LwwResolver()
        r.accept(ClipVersion("a", 5, 100))
        assertFalse(r.accept(ClipVersion("a", 5, 999))) // same counter, even with newer clock
    }

    @Test
    fun outOfOrderLowerCounterFromSameDeviceIsRejected() {
        val r = LwwResolver()
        r.accept(ClipVersion("a", 5, 100))
        assertFalse(r.accept(ClipVersion("a", 4, 999)))
    }

    @Test
    fun recordLocalSuppressesEchoOfSameCounter() {
        val r = LwwResolver()
        r.recordLocal(ClipVersion("me", 10, 500))
        // an echo of my own just-applied clip (same counter) must not re-win
        assertFalse(r.accept(ClipVersion("me", 10, 500)))
        assertEquals("me", r.current()!!.deviceId)
    }

    @Test
    fun seenButNotWinningStillDedupsFutureStale() {
        val r = LwwResolver()
        r.accept(ClipVersion("a", 1, 300))       // current = a@300
        assertFalse(r.accept(ClipVersion("b", 2, 100))) // seen b:2 but older clock → not applied
        assertFalse(r.accept(ClipVersion("b", 2, 999))) // same counter b:2 → rejected as duplicate
        assertTrue(r.accept(ClipVersion("b", 3, 400)))  // newer counter + newer clock → wins
        assertEquals("b", r.current()!!.deviceId)
    }
}
