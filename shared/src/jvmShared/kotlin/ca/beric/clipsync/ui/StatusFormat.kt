package ca.beric.clipsync.ui

import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import ca.beric.clipsync.pairing.Peer
import ca.beric.clipsync.transfer.TransferState
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Status wording and formatting shared by both apps' UIs. This app's whole purpose is to
 * keep a pair of devices in sync — the strings describing that state must not drift apart,
 * so they live once here rather than as per-app copies.
 */

/** One status line for the header chip (and the desktop tray/window title). */
fun statusLine(connected: Set<String>, peers: List<Peer>): String {
    val names = peers.filter { it.deviceId in connected }.map { it.deviceName }
    return when {
        names.isNotEmpty() -> "Connected to ${names.joinToString()}"
        peers.isNotEmpty() -> "Waiting for ${peers.joinToString { it.deviceName }}"
        else -> "Not paired yet"
    }
}

/** The line under a peer's name; [sas] is the comparison code both screens must agree on. */
fun peerStatusLine(on: Boolean, sas: String): String =
    (if (on) "Connected" else "Not connected — syncs on reconnect") + " · code $sas"

/** A transfer row's status text. */
fun transferDetail(t: TransferState): String = when (t.status) {
    TransferState.Status.ACTIVE -> "${formatBytes(t.transferredBytes)} / ${formatBytes(t.sizeBytes)}"
    TransferState.Status.DONE -> if (t.outbound) "sent" else t.detail ?: "received"
    TransferState.Status.FAILED -> "failed: ${t.detail}"
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / (1 shl 30).toDouble())
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1 shl 20).toDouble())
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / (1 shl 10).toDouble())
    else -> "$bytes B"
}

/** Maps a device id to what the UI shows; [selfLabel] is "This Mac" / "This phone". */
fun deviceLabeler(selfLabel: String, peers: List<Peer>): (String) -> String = { id ->
    if (id == LOCAL_DEVICE_ID) selfLabel
    else peers.firstOrNull { it.deviceId == id }?.deviceName ?: id.take(8)
}

// SimpleDateFormat is not thread-safe; every caller formats during composition (UI thread).
private val clipTimeFormat = SimpleDateFormat("HH:mm")

/** Timestamps on history rows, notifications, and messages. */
fun formatClipTime(ms: Long): String = clipTimeFormat.format(Date(ms))
