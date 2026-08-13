package ca.beric.clipsync.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun helloRoundTrips() {
        val msg = ControlMessage.Hello(deviceId = "mac-1", protocolVersion = 1)
        val decoded = ControlCodec.decode(ControlCodec.encode(msg))
        assertEquals(msg, decoded)
    }

    @Test
    fun helloEndpointsRoundTripAndLegacyHelloStillDecodes() {
        val msg = ControlMessage.Hello("mac-1", 1, endpoints = listOf("192.168.1.32:47653", "100.72.29.68:47653"))
        assertEquals(msg, ControlCodec.decode(ControlCodec.encode(msg)))
        // A v1 peer sends no "ep" field; it must decode with empty endpoints, not fail.
        val legacy = ControlCodec.decode("""{"t":"hello","dev":"old-1","v":1}""")
        assertEquals(ControlMessage.Hello("old-1", 1, emptyList()), legacy)
    }

    @Test
    fun clipUpdateRoundTripsWithSealedBytes() {
        val sealed = ByteArray(40) { it.toByte() }
        val msg = ControlMessage.ClipUpdate.of(
            ClipVersion("mac-1", counter = 3, wallClockMs = 1000), "text", sealed,
        )
        val decoded = ControlCodec.decode(ControlCodec.encode(msg))
        assertTrue(decoded is ControlMessage.ClipUpdate)
        assertContentEquals(sealed, (decoded as ControlMessage.ClipUpdate).sealedBytes)
        assertEquals(ClipVersion("mac-1", 3, 1000), decoded.version)
        assertEquals("text", decoded.kind)
    }

    @Test
    fun imageUpdateRoundTrips() {
        val msg = ControlMessage.ImageUpdate(
            ClipVersion("phone-1", 5, 2000),
            ImageMeta("image/png", size = 3_000_000, sha256 = "deadbeef"),
            chunkCount = 46,
        )
        assertEquals(msg, ControlCodec.decode(ControlCodec.encode(msg)))
    }

    @Test
    fun decodeGarbageReturnsNull() {
        assertNull(ControlCodec.decode("garbage"))
    }

    @Test
    fun fileMessagesRoundTrip() {
        val offer = ControlMessage.FileOffer(
            id = "ab".repeat(32), name = "song.mp3", size = 4_567_890,
            mime = "audio/mpeg", sha256 = "cd".repeat(32), chunkCount = 18,
        )
        assertEquals(offer, ControlCodec.decode(ControlCodec.encode(offer)))

        val ack = ControlMessage.FileAck(id = "ab".repeat(32), received = 8)
        assertEquals(ack, ControlCodec.decode(ControlCodec.encode(ack)))

        val err = ControlMessage.FileError(id = "ab".repeat(32), reason = "receiver busy")
        assertEquals(err, ControlCodec.decode(ControlCodec.encode(err)))
    }

    @Test
    fun unknownMessageTypeDecodesToNull() {
        // An older peer decoding a future message type must drop it, not crash.
        assertNull(ControlCodec.decode("""{"t":"file-resume","id":"x"}"""))
    }

    @Test
    fun chunkFrameRoundTrips() {
        val sha = ByteArray(32) { (it + 1).toByte() }
        val payload = ByteArray(500) { (it % 256).toByte() }
        val frame = ChunkFrame.encode(sha, index = 7, total = 46, payload = payload)
        val decoded = ChunkFrame.decode(frame)!!
        assertContentEquals(sha, decoded.sha256)
        assertEquals(7, decoded.index)
        assertEquals(46, decoded.total)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun chunkFrameRejectsTruncated() {
        assertNull(ChunkFrame.decode(ByteArray(10)))
    }

    @Test
    fun mirrorEnvelopeRoundTripsWithSealedBytes() {
        val sealed = ByteArray(24) { (it * 3).toByte() }
        val msg = ControlMessage.Mirror.of(sealed)
        val decoded = ControlCodec.decode(ControlCodec.encode(msg)) as ControlMessage.Mirror
        assertContentEquals(sealed, decoded.sealedBytes)
    }

    @Test
    fun mirrorEventsRoundTrip() {
        val events = listOf(
            MirrorEvent.NotifPosted("0|app|1", "Signal", "Alice", "see you", 12L, canReply = true),
            MirrorEvent.NotifReply("0|app|1", "on my way"),
            MirrorEvent.SmsQueryThreads,
            MirrorEvent.SmsThreads(listOf(SmsThread(3L, "+15550001111", "hey", 1L, 9))),
            MirrorEvent.SmsQueryThread(3L),
            MirrorEvent.SmsMessages(3L, listOf(SmsMessage("+15550001111", "hey", 1L, outbound = false))),
            MirrorEvent.SmsSend("+15550001111", "hello back"),
            MirrorEvent.SmsSent(ok = true, to = "+15550001111"),
        )
        for (event in events) {
            assertEquals(event, MirrorCodec.decode(MirrorCodec.encode(event)), "round trip: $event")
        }
    }

    @Test
    fun unknownMirrorEventDecodesNull() {
        assertNull(MirrorCodec.decode("""{"t":"hologram","x":1}"""))
        assertNull(MirrorCodec.decode("not json"))
    }
}
