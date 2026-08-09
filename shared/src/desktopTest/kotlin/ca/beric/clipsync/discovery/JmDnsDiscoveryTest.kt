package ca.beric.clipsync.discovery

import org.junit.Assume
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API smoke test: proves the JmDNS wrapper advertises and resolves correctly by having
 * one instance discover another's service on this host. It exercises real multicast, so
 * it is skipped unless `-Dclipsync.mdns.test=1` is set (CI has no multicast). This does
 * NOT prove cross-device discovery — that needs two physical machines on one Wi-Fi.
 */
class JmDnsDiscoveryTest {

    private val advertiser = JmDnsDiscovery()
    private val browser = JmDnsDiscovery()

    @AfterTest
    fun cleanup() {
        advertiser.stop()
        browser.stop()
    }

    @Test
    fun advertiseIsDiscoveredByAnotherInstance() {
        Assume.assumeTrue(
            "set -Dclipsync.mdns.test=1 to run (needs multicast)",
            System.getProperty("clipsync.mdns.test") == "1",
        )
        val found = CountDownLatch(1)
        var seen: DiscoveredService? = null
        advertiser.start("AAA-adv", 47653) {}
        browser.start("BBB-brw", 40000) { service ->
            if (service.deviceId == "AAA-adv") {
                seen = service
                found.countDown()
            }
        }
        assertTrue(found.await(15, TimeUnit.SECONDS), "did not discover the advertised service")
        assertEquals(47653, seen?.port)
    }
}
