package ca.beric.clipsync.protocol

/**
 * Last-write-wins ordering for the single shared clipboard value.
 *
 * The winning value is the one with the greatest wall-clock time; ties break by
 * deviceId so every device converges on the same winner deterministically. A
 * per-device monotonic counter rejects stale or duplicate updates (including the
 * echo of a clip this device just received and re-applied) from the same device.
 *
 * Not thread-safe; call from a single sync coroutine.
 */
class LwwResolver {

    private var current: ClipVersion? = null
    private val lastCounter = mutableMapOf<String, Long>()

    fun current(): ClipVersion? = current

    /** Record a locally-generated version as the current winner (a fresh local copy always wins). */
    fun recordLocal(version: ClipVersion) {
        lastCounter[version.deviceId] = version.counter
        current = version
    }

    /**
     * Decide whether an [incoming] remote version should replace the current value.
     * Updates internal state (seen-counter, current) as a side effect when accepted
     * or when the update is merely seen-but-not-winning.
     */
    fun accept(incoming: ClipVersion): Boolean {
        val seen = lastCounter[incoming.deviceId]
        if (seen != null && incoming.counter <= seen) return false
        lastCounter[incoming.deviceId] = incoming.counter
        val cur = current
        if (cur == null || incoming.wins(cur)) {
            current = incoming
            return true
        }
        return false
    }

    private fun ClipVersion.wins(other: ClipVersion): Boolean =
        wallClockMs > other.wallClockMs ||
            (wallClockMs == other.wallClockMs && deviceId > other.deviceId)
}
