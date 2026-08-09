@file:OptIn(ExperimentalEncodingApi::class)

package ca.beric.clipsync.identity

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * macOS Keychain via the `security` CLI. Secrets are stored as generic passwords
 * under service "ca.beric.clipsync", account = alias, value = base64(secret).
 */
actual class SecretStore {

    actual fun put(alias: String, secret: ByteArray) {
        val encoded = Base64.encode(secret)
        // -U updates if the item already exists.
        run(
            "security", "add-generic-password",
            "-a", alias, "-s", SERVICE, "-w", encoded, "-U",
        )
    }

    actual fun get(alias: String): ByteArray? {
        val out = runCapturing(
            "security", "find-generic-password",
            "-a", alias, "-s", SERVICE, "-w",
        ) ?: return null
        val trimmed = out.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { Base64.decode(trimmed) }.getOrNull()
    }

    actual fun delete(alias: String) {
        run("security", "delete-generic-password", "-a", alias, "-s", SERVICE)
    }

    private fun run(vararg cmd: String) {
        runCatching {
            ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor()
        }
    }

    private fun runCapturing(vararg cmd: String): String? = runCatching {
        val process = ProcessBuilder(*cmd).start()
        val output = process.inputStream.readBytes().decodeToString()
        val code = process.waitFor()
        if (code == 0) output else null
    }.getOrNull()

    companion object {
        private const val SERVICE = "ca.beric.clipsync"
    }
}
