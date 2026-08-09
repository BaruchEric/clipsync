package ca.beric.clipsync.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * mDNS discovery for Android via [NsdManager]. Registers a `_clipsync._tcp` service with
 * the device id in a TXT record and browses for peers.
 *
 * A Wi-Fi multicast lock is required for the device to receive mDNS packets; it is held
 * only while discovery is running (acquired in [start], released in [stop]) to keep the
 * battery cost bounded, per the design.
 *
 * Note: [NsdManager.resolveService] and [NsdServiceInfo.getHost] are deprecated on API 34+
 * in favor of a callback API, but the deprecated path works across minSdk 29 and is used
 * for MVP simplicity.
 */
class NsdDiscovery(context: Context) : PeerDiscovery {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override fun start(deviceId: String, port: Int, onPeer: (DiscoveredService) -> Unit) {
        multicastLock = wifi.createMulticastLock("clipsync-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        register(deviceId, port)
        discover(deviceId, onPeer)
    }

    private fun register(deviceId: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "clipsync-$deviceId"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_ID, deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "advertised ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "register failed: $errorCode") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover(selfId: String, onPeer: (DiscoveredService) -> Unit) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery start failed: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info, selfId, onPeer)
        }
        discoveryListener = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Fresh ResolveListener per call — reusing one instance throws "listener already in use". */
    private fun resolve(found: NsdServiceInfo, selfId: String, onPeer: (DiscoveredService) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "resolve failed: $errorCode") }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val id = info.attributes[TXT_ID]?.let { String(it) } ?: info.serviceName
                if (id == selfId) return
                @Suppress("DEPRECATION")
                val host = info.host?.hostAddress ?: return
                onPeer(DiscoveredService(id, host, info.port))
            }
        }
        @Suppress("DEPRECATION")
        runCatching { nsd.resolveService(found, resolveListener) }
    }

    override fun stop() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        registrationListener = null
        discoveryListener = null
        multicastLock = null
    }

    private companion object {
        const val TAG = "clipsyncNsd"
        const val SERVICE_TYPE = "_clipsync._tcp."
        const val TXT_ID = "id"
    }
}
