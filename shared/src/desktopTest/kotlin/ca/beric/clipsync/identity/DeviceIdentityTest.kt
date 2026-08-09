package ca.beric.clipsync.identity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.db.ClipsyncDb
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exercises the real macOS Keychain (via the `security` CLI) and identity persistence. */
class DeviceIdentityTest {

    private val store = SecretStore()
    private val testAlias = "clipsync-test-identity-secret"

    @AfterTest
    fun cleanup() {
        store.delete(testAlias)
    }

    @Test
    fun keychainRoundTrips() {
        store.delete(testAlias)
        val secret = ByteArray(32) { (it * 7).toByte() }
        store.put(testAlias, secret)
        assertContentEquals(secret, store.get(testAlias))
        store.delete(testAlias)
        assertNull(store.get(testAlias))
    }

    @Test
    fun identityIsStableAcrossCalls() = runTest {
        store.delete(testAlias)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        val identity = DeviceIdentity(ClipsyncDb(driver), store, secretAlias = testAlias)

        val first = identity.getOrCreate("Eric's Mac")
        val second = identity.getOrCreate("Eric's Mac")

        assertEquals(first.deviceId, second.deviceId)
        assertEquals("Eric's Mac", first.deviceName)
        assertContentEquals(first.keyPair.publicKey, second.keyPair.publicKey)
        assertContentEquals(first.keyPair.secretKey, second.keyPair.secretKey)
    }
}
