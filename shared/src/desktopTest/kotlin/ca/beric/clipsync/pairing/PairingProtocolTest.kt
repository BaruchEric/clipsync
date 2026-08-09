package ca.beric.clipsync.pairing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Simulates the full pairing handshake between two devices without any camera or
 * QR rendering: exchange payloads, derive the per-pair key on each side, confirm
 * the SAS matches, persist each peer, then prove a clip sealed on one device
 * opens on the other — and that a tampered frame is rejected.
 */
class PairingProtocolTest {

    private fun newStore(): PeerStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        return PeerStore(ClipsyncDb(driver))
    }

    private class Device(val id: String, val name: String) {
        val keys = ClipsyncCrypto.generateKeyPair()
        fun payload() = PairingPayload.of(
            deviceId = id,
            deviceName = name,
            publicKey = keys.publicKey,
            certFingerprint = "fp-$id",
            addresses = listOf("192.168.1.9:7010"),
            port = 7010,
        )
    }

    @Test
    fun twoDevicesPairAndExchangeAnEncryptedClip() = runTest {
        ClipsyncCrypto.ensureInitialized()

        val mac = Device("mac-1", "Mac")
        val phone = Device("phone-1", "Phone")

        // Each scans the other's QR payload.
        val macSeesPhone = PairingPayload.decode(phone.payload().encode())!!
        val phoneSeesMac = PairingPayload.decode(mac.payload().encode())!!

        // Each derives the per-pair key from its own secret + the other's public key.
        val keyOnMac = ClipsyncCrypto.deriveSharedKey(mac.keys.secretKey, mac.keys.publicKey, macSeesPhone.publicKey)
        val keyOnPhone = ClipsyncCrypto.deriveSharedKey(phone.keys.secretKey, phone.keys.publicKey, phoneSeesMac.publicKey)
        assertContentEquals(keyOnMac, keyOnPhone)

        // SAS shown on both screens must match for the user to confirm.
        assertEquals(ClipsyncCrypto.shortAuthString(keyOnMac), ClipsyncCrypto.shortAuthString(keyOnPhone))

        // Both persist the peer.
        val macStore = newStore()
        val phoneStore = newStore()
        macStore.save(
            Peer("phone-1", macSeesPhone.deviceName, macSeesPhone.publicKey, macSeesPhone.certFingerprint,
                keyOnMac, macSeesPhone.addresses, pairedAtMs = 1),
        )
        phoneStore.save(
            Peer("mac-1", phoneSeesMac.deviceName, phoneSeesMac.publicKey, phoneSeesMac.certFingerprint,
                keyOnPhone, phoneSeesMac.addresses, pairedAtMs = 1),
        )

        // Mac seals a clip with the stored per-pair key; phone opens it with its stored key.
        val storedMacKey = macStore.get("phone-1")!!.perPairKey
        val storedPhoneKey = phoneStore.get("mac-1")!!.perPairKey
        val sealed = ClipsyncCrypto.seal(storedMacKey, "clipboard text".encodeToByteArray())
        assertContentEquals("clipboard text".encodeToByteArray(), ClipsyncCrypto.open(storedPhoneKey, sealed))

        // A tampered frame is rejected.
        val tampered = sealed.copyOf().also { it[28] = (it[28].toInt() xor 0x01).toByte() }
        assertNull(ClipsyncCrypto.open(storedPhoneKey, tampered))
    }

    @Test
    fun peerStorePersistsAndUpdatesAddresses() {
        val store = newStore()
        store.save(Peer("p1", "Peer", ByteArray(32) { 7 }, "fp", ByteArray(32) { 9 }, listOf("10.0.0.1:7010"), 5))
        assertEquals(1, store.all().size)
        assertEquals(listOf("10.0.0.1:7010"), store.get("p1")!!.addresses)

        store.updateAddresses("p1", listOf("10.0.0.1:7010", "100.64.0.1:7010"))
        assertEquals(listOf("10.0.0.1:7010", "100.64.0.1:7010"), store.get("p1")!!.addresses)

        store.remove("p1")
        assertNull(store.get("p1"))
    }
}
