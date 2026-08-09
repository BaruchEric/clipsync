package ca.beric.clipsync.pairing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingPayloadTest {

    @Test
    fun encodeDecodeRoundTrips() {
        val pubkey = ByteArray(32) { it.toByte() }
        val payload = PairingPayload.of(
            deviceId = "mac-1",
            deviceName = "Eric's Mac",
            publicKey = pubkey,
            certFingerprint = "AB:CD:EF",
            addresses = listOf("192.168.1.5:7010", "100.101.102.103:7010"),
            port = 7010,
        )
        val decoded = PairingPayload.decode(payload.encode())!!
        assertEquals("mac-1", decoded.deviceId)
        assertEquals("Eric's Mac", decoded.deviceName)
        assertContentEquals(pubkey, decoded.publicKey)
        assertEquals("AB:CD:EF", decoded.certFingerprint)
        assertEquals(listOf("192.168.1.5:7010", "100.101.102.103:7010"), decoded.addresses)
        assertEquals(7010, decoded.port)
    }

    @Test
    fun decodeGarbageReturnsNull() {
        assertNull(PairingPayload.decode("not a payload"))
        assertNull(PairingPayload.decode("{}"))
    }
}
