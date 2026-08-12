package ca.beric.clipsync.discovery

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * mDNS discovery for the desktop via JmDNS. Registers a `_clipsync._tcp` service carrying
 * the device id in a TXT record and browses for peers, resolving each to a host:port.
 */
class JmDnsDiscovery : PeerDiscovery {

    @Volatile
    private var jmdns: JmDNS? = null

    /**
     * JmDNS binds ONE interface, and both its advertised A record and its multicast
     * visibility come from it. `InetAddress.getLocalHost()` resolves to 127.0.0.1 on a
     * stock macOS hosts file — peers then "discover" the Mac at loopback and dial
     * themselves (observed live on the S24, 2026-08-12). Bind the first real LAN IPv4:
     * skip loopback/virtual interfaces and prefer non-CGNAT (tailnet multicast can't
     * carry mDNS anyway; the dialer covers that path).
     */
    private fun bindAddress(): InetAddress {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .filterNot { it.name.startsWith("vnic") || it.name.startsWith("bridge") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .sortedBy { if (it.hostAddress.orEmpty().startsWith("100.")) 1 else 0 }
        }.getOrDefault(emptyList())
        return candidates.firstOrNull() ?: InetAddress.getLocalHost()
    }

    override fun start(deviceId: String, port: Int, onPeer: (DiscoveredService) -> Unit) {
        val jm = JmDNS.create(bindAddress())
        jmdns = jm
        val info = ServiceInfo.create(SERVICE_TYPE, deviceId, port, 0, 0, mapOf(TXT_ID to deviceId))
        jm.registerService(info)
        jm.addServiceListener(SERVICE_TYPE, object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // Resolution is async; request it so serviceResolved fires with addresses.
                jm.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val si = event.info
                val id = si.getPropertyString(TXT_ID) ?: event.name
                if (id == deviceId) return // ignore our own advertisement
                val host = si.inet4Addresses.firstOrNull()?.hostAddress
                    ?: si.hostAddresses.firstOrNull()
                    ?: return
                onPeer(DiscoveredService(id, host, si.port))
            }
        })
    }

    override fun stop() {
        jmdns?.let { runCatching { it.unregisterAllServices(); it.close() } }
        jmdns = null
    }

    private companion object {
        const val SERVICE_TYPE = "_clipsync._tcp.local."
        const val TXT_ID = "id"
    }
}
