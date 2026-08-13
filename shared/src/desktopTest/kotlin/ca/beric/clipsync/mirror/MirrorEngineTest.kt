package ca.beric.clipsync.mirror

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.protocol.SmsThread
import ca.beric.clipsync.sync.RemotePeer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Two [MirrorEngine]s wired in memory: sealing, routing, and the drop paths. */
class MirrorEngineTest {

    private fun wirePair(
        keyB: ByteArray? = null,
        mangle: ((ControlMessage.Mirror) -> ControlMessage.Mirror)? = null,
    ): Triple<MirrorEngine, MutableList<Pair<String, MirrorEvent>>, MutableList<Pair<String, MirrorEvent>>> {
        val key = ClipsyncCrypto.randomKey()
        val eventsA = mutableListOf<Pair<String, MirrorEvent>>()
        val eventsB = mutableListOf<Pair<String, MirrorEvent>>()
        val a = MirrorEngine({ from, e -> eventsA += from to e })
        val b = MirrorEngine({ from, e -> eventsB += from to e })
        a.addPeer(
            RemotePeer("B", key, send = { msg ->
                val m = msg as ControlMessage.Mirror
                b.onRemoteMessage("A", mangle?.invoke(m) ?: m)
            }),
        )
        b.addPeer(RemotePeer("A", keyB ?: key, send = { a.onRemoteMessage("B", it as ControlMessage.Mirror) }))
        return Triple(a, eventsA, eventsB)
    }

    @Test
    fun notificationReachesThePeerIntact() = runBlocking {
        val (a, _, eventsB) = wirePair()
        val notif = MirrorEvent.NotifPosted("k1", "Signal", "Alice", "see you at 8", 1234L, canReply = true)
        assertTrue(a.send(null, notif))
        assertEquals(listOf<Pair<String, MirrorEvent>>("A" to notif), eventsB)
    }

    @Test
    fun smsThreadsRoundTripThroughTheSeal() = runBlocking {
        val (a, _, eventsB) = wirePair()
        val threads = MirrorEvent.SmsThreads(
            listOf(SmsThread(7L, "+15551234567", "on my way", 99L, 42)),
        )
        assertTrue(a.send("B", threads))
        assertEquals("A" to threads, eventsB.single())
    }

    @Test
    fun tamperedEnvelopeIsDropped() = runBlocking {
        val (a, _, eventsB) = wirePair(mangle = { m ->
            val bytes = m.sealedBytes
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            ControlMessage.Mirror.of(bytes)
        })
        assertTrue(a.send(null, MirrorEvent.SmsQueryThreads))
        assertTrue(eventsB.isEmpty())
    }

    @Test
    fun wrongKeyIsDropped() = runBlocking {
        // B holds a different per-pair key for A, so B's open() fails on A's envelopes.
        val (a, _, eventsB) = wirePair(keyB = ClipsyncCrypto.randomKey())
        assertTrue(a.send(null, MirrorEvent.NotifReply("k", "hi")))
        assertTrue(eventsB.isEmpty())
    }

    @Test
    fun unknownEventTypeFromANewerPeerIsDropped() {
        val key = ClipsyncCrypto.randomKey()
        val events = mutableListOf<Pair<String, MirrorEvent>>()
        val b = MirrorEngine({ from, e -> events += from to e })
        b.addPeer(RemotePeer("A", key, send = {}))
        val sealed = ClipsyncCrypto.seal(key, """{"t":"hologram","x":1}""".encodeToByteArray())
        b.onRemoteMessage("A", ControlMessage.Mirror.of(sealed))
        assertTrue(events.isEmpty())
    }

    @Test
    fun sendWithNoPeersReturnsFalse() = runBlocking {
        val lone = MirrorEngine({ _, _ -> })
        assertFalse(lone.send(null, MirrorEvent.SmsQueryThreads))
    }
}
