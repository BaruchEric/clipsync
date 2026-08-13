package ca.beric.clipsync.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replay-on-connect: a value captured while no peer was linked (network switch, LTE) must
 * arrive when a peer registers — there is no queue, only the newest value — and LWW must
 * keep both sides convergent when each replays its own latest.
 */
class SyncEngineReplayTest {

    private class RecordingApplier : ClipboardApplier {
        val applied = mutableListOf<String>()
        val images = mutableListOf<ByteArray>()
        override fun applyText(text: String) { applied += text }
        override fun applyImage(bytes: ByteArray, mime: String) { images += bytes }
    }

    private fun repo(): ClipRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        return ClipRepository(ClipsyncDb(driver))
    }

    /** Wires b to receive whatever a peer handle for [to] sends (in-memory, no sockets). */
    private fun peerInto(to: SyncEngine, fromId: String, key: ByteArray, asId: String) =
        RemotePeer(
            asId, key,
            send = { to.onRemoteMessage(fromId, it) },
            sendChunk = { to.onBinaryFrame(it) },
        )

    /**
     * Registers both directions. Over the real transport each side registers the peer on its
     * Hello before any replay arrives; with synchronous in-memory wiring the first addPeer's
     * replay fires before the other engine knows the sender, so the first add is repeated
     * once both maps exist — which is also exactly what a reconnect does.
     */
    private suspend fun connectBothWays(a: SyncEngine, b: SyncEngine, key: ByteArray) {
        val aToB = peerInto(b, fromId = "A", key = key, asId = "B")
        val bToA = peerInto(a, fromId = "B", key = key, asId = "A")
        a.addPeer(aToB)
        b.addPeer(bToA)
        a.addPeer(aToB)
    }

    @Test
    fun offlineTextCaptureReplaysOnConnect() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val key = ClipsyncCrypto.randomKey()
        val applierB = RecordingApplier()
        val engineA = SyncEngine("A", repo(), RecordingApplier())
        val engineB = SyncEngine("B", repo(), applierB)

        assertTrue(engineA.onLocalCapture("copied while offline", nowMs = 100))
        assertTrue(applierB.applied.isEmpty())

        connectBothWays(engineA, engineB, key)

        assertEquals(listOf("copied while offline"), applierB.applied)
    }

    @Test
    fun newerSideWinsWhenBothReplay() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val key = ClipsyncCrypto.randomKey()
        val applierA = RecordingApplier()
        val applierB = RecordingApplier()
        val engineA = SyncEngine("A", repo(), applierA)
        val engineB = SyncEngine("B", repo(), applierB)

        engineA.onLocalCapture("older", nowMs = 100)
        engineB.onLocalCapture("newer", nowMs = 200)

        connectBothWays(engineA, engineB, key)

        // A's stale replay is rejected by B's LWW; B's newer replay lands on A.
        assertTrue(applierB.applied.isEmpty(), "stale replay must not clobber the newer value: ${applierB.applied}")
        assertEquals(listOf("newer"), applierA.applied)
    }

    @Test
    fun offlineImageCaptureReplaysOnConnect() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val key = ClipsyncCrypto.randomKey()
        val applierB = RecordingApplier()
        val engineA = SyncEngine("A", repo(), RecordingApplier())
        val engineB = SyncEngine("B", repo(), applierB)

        val image = ByteArray(200_000) { ((it * 7) % 251).toByte() }
        assertTrue(engineA.onLocalImageCapture(image, "image/png", nowMs = 100))

        connectBothWays(engineA, engineB, key)

        assertEquals(1, applierB.images.size)
        assertContentEquals(image, applierB.images.single())
    }
}
