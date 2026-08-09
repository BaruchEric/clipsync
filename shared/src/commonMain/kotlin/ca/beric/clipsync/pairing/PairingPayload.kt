@file:OptIn(ExperimentalEncodingApi::class)

package ca.beric.clipsync.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The content encoded in the pairing QR code shown by device A and scanned by
 * device B. Carries everything B needs to establish a pinned, authenticated
 * connection: the X25519 public key (key agreement), the TLS cert fingerprint
 * (pinning), and address hints (LAN + tailnet 100.x) for later direct dial.
 */
@Serializable
data class PairingPayload(
    @SerialName("v") val version: Int = 1,
    @SerialName("id") val deviceId: String,
    @SerialName("name") val deviceName: String,
    @SerialName("pk") val publicKeyB64: String,
    @SerialName("fp") val certFingerprint: String,
    @SerialName("addr") val addresses: List<String>,
    @SerialName("port") val port: Int,
) {
    val publicKey: ByteArray get() = Base64.decode(publicKeyB64)

    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun of(
            deviceId: String,
            deviceName: String,
            publicKey: ByteArray,
            certFingerprint: String,
            addresses: List<String>,
            port: Int,
        ): PairingPayload = PairingPayload(
            deviceId = deviceId,
            deviceName = deviceName,
            publicKeyB64 = Base64.encode(publicKey),
            certFingerprint = certFingerprint,
            addresses = addresses,
            port = port,
        )

        /** Returns null if the string is not a valid pairing payload. */
        fun decode(text: String): PairingPayload? =
            runCatching { json.decodeFromString<PairingPayload>(text) }.getOrNull()
    }
}
