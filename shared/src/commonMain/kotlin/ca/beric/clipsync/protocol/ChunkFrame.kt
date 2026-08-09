package ca.beric.clipsync.protocol

/**
 * Binary frame for one image chunk, sent as a WebSocket binary message:
 *
 *   [ sha256 (32 bytes) | index (u32 BE) | total (u32 BE) | sealed payload ]
 *
 * The sha256 ties a chunk to its announced [ControlMessage.ImageUpdate]; index/total
 * let the receiver reassemble and know when the transfer is complete. The payload is
 * the AEAD-sealed chunk bytes.
 */
object ChunkFrame {

    const val SHA256_BYTES = 32
    const val HEADER_BYTES = SHA256_BYTES + 4 + 4

    data class Decoded(val sha256: ByteArray, val index: Int, val total: Int, val payload: ByteArray)

    fun encode(sha256: ByteArray, index: Int, total: Int, payload: ByteArray): ByteArray {
        require(sha256.size == SHA256_BYTES) { "sha256 must be $SHA256_BYTES bytes" }
        val out = ByteArray(HEADER_BYTES + payload.size)
        sha256.copyInto(out, 0)
        writeU32(out, SHA256_BYTES, index)
        writeU32(out, SHA256_BYTES + 4, total)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    fun decode(frame: ByteArray): Decoded? {
        if (frame.size < HEADER_BYTES) return null
        val sha = frame.copyOfRange(0, SHA256_BYTES)
        val index = readU32(frame, SHA256_BYTES)
        val total = readU32(frame, SHA256_BYTES + 4)
        val payload = frame.copyOfRange(HEADER_BYTES, frame.size)
        return Decoded(sha, index, total, payload)
    }

    private fun writeU32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun readU32(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
}
