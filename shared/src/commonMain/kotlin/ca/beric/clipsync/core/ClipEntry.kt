package ca.beric.clipsync.core

const val LOCAL_DEVICE_ID = "local"

data class ClipEntry(
    val id: Long,
    val deviceId: String,
    val kind: String,
    val content: String,
    val createdAtMs: Long,
)
