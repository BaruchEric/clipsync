package ca.beric.clipsync.transport

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.identity.DeviceIdentity
import ca.beric.clipsync.pairing.PairingManager
import ca.beric.clipsync.pairing.PairingPayload
import ca.beric.clipsync.pairing.Peer
import ca.beric.clipsync.pairing.PeerStore
import ca.beric.clipsync.sync.ClipboardApplier
import ca.beric.clipsync.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves the full app wiring — two ConnectionManagers over real TLS syncing a clip. */
class ConnectionManagerTest {

    private val scope = CoroutineScope(SupervisorJob())
    private var a: ConnectionManager? = null
    private var b: ConnectionManager? = null

    @AfterTest
    fun tearDown() {
        a?.stop(); b?.stop(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private class RecordingApplier : ClipboardApplier {
        val applied = mutableListOf<String>()
        override fun applyText(text: String) { applied += text }
    }

    private fun repo(): ClipRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        return ClipRepository(ClipsyncDb(driver))
    }

    private fun peerStore(): PeerStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        return PeerStore(ClipsyncDb(driver))
    }

    @Test
    fun serverAndDialerSyncOverTls() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val idA = TlsIdentity.generate("A")
        val ka = ClipsyncCrypto.generateKeyPair()
        val kb = ClipsyncCrypto.generateKeyPair()
        val keyA = ClipsyncCrypto.deriveSharedKey(ka.secretKey, ka.publicKey, kb.publicKey)
        val keyB = ClipsyncCrypto.deriveSharedKey(kb.secretKey, kb.publicKey, ka.publicKey)

        val applierB = RecordingApplier()
        val engineA = SyncEngine("A", repo(), RecordingApplier())
        val engineB = SyncEngine("B", repo(), applierB)

        a = ConnectionManager("A", idA, engineA, { if (it == "B") keyA else null }, scope)
            .also { it.startServer(17795, host = "127.0.0.1") }
        b = ConnectionManager("B", TlsIdentity.generate("B"), engineB, { if (it == "A") keyB else null }, scope)

        withTimeout(15_000) {
            b!!.dial("127.0.0.1", 17795, idA.fingerprint)
            // give the Hello handshake a moment to register peers both ways
            delay(500)
            engineA.onLocalCapture("synced!", nowMs = 100)
            withTimeout(5_000) { while (applierB.applied.isEmpty()) delay(20) }
            assertEquals("synced!", applierB.applied.last())
        }
    }

    /**
     * Reverse-channel pairing: B has scanned A's QR (so B knows A's key + fingerprint) but A
     * knows nothing about B. When B dials A it sends its payload as a [PairRequest]; A must
     * pair B over the wire, then both register and sync both ways. This proves QR pairing
     * end to end without a camera.
     */
    @Test
    fun reverseChannelPairingFromScan() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val aTls = TlsIdentity.generate("A")
        val aKeys = ClipsyncCrypto.generateKeyPair()
        val bKeys = ClipsyncCrypto.generateKeyPair()

        // A: pairs peers over the wire into its own store.
        val aPeers = peerStore()
        val aPairing = PairingManager(DeviceIdentity.Identity("AAA", "A", aKeys), aPeers)

        // B: already paired A (as if from scanning A's QR) and has A's dial endpoint + fingerprint.
        val bPeers = peerStore()
        val keyBA = ClipsyncCrypto.deriveSharedKey(bKeys.secretKey, bKeys.publicKey, aKeys.publicKey)
        bPeers.save(Peer("AAA", "A", aKeys.publicKey, aTls.fingerprint, keyBA, listOf("127.0.0.1:17798"), 0))
        val bPayload = PairingPayload.of("BBB", "B", bKeys.publicKey, "b-fp", emptyList(), 0).encode()

        val applierA = RecordingApplier()
        val applierB = RecordingApplier()
        val engineA = SyncEngine("AAA", repo(), applierA)
        val engineB = SyncEngine("BBB", repo(), applierB)

        a = ConnectionManager(
            "AAA", aTls, engineA, { aPeers.get(it)?.perPairKey }, scope,
            pairingSink = { json -> aPairing.pair(json, 0)?.deviceId },
        ).also { it.startServer(17798, host = "127.0.0.1") }
        b = ConnectionManager(
            "BBB", TlsIdentity.generate("B"), engineB, { bPeers.get(it)?.perPairKey }, scope,
            myPayload = { bPayload },
        ).also { it.offerReciprocalPairing() }

        withTimeout(20_000) {
            scope.launch { b!!.dialPeer("AAA", listOf("127.0.0.1:17798"), aTls.fingerprint) }
            withTimeout(10_000) { while (!a!!.isConnected("BBB") || !b!!.isConnected("AAA")) delay(20) }
            // A saved B from the PairRequest, deriving the same key.
            assertEquals(keyBA.toList(), aPeers.get("BBB")?.perPairKey?.toList())
            engineA.onLocalCapture("A to B", nowMs = 100)
            withTimeout(5_000) { while (applierB.applied.isEmpty()) delay(20) }
            assertEquals("A to B", applierB.applied.last())
            engineB.onLocalCapture("B to A", nowMs = 200)
            withTimeout(5_000) { while (applierA.applied.isEmpty()) delay(20) }
            assertEquals("B to A", applierA.applied.last())
        }
    }
}
