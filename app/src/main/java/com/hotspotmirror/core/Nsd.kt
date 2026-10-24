package com.hotspotmirror.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * mDNS/DNS-SD helpers. The Sender advertises "_hotspotmirror._tcp" on the hotspot interface;
 * the Viewer browses for it. A multicast lock is held while browsing because many phones
 * drop multicast packets with the screen off.
 */
class Nsd(private val context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun register(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Log.i(TAG, "registered ${i.serviceName}")
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) = Log.w(TAG, "register failed $e")
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
    }

    fun unregister() {
        regListener?.let { runCatching { nsd.unregisterService(it) } }
        regListener = null
    }

    /** Calls [onFound] with host and port each time a Sender is resolved. */
    fun discover(onFound: (host: String, port: Int, name: String) -> Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("hotspotmirror").apply { setReferenceCounted(false); acquire() }

        discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) = Log.i(TAG, "discovery started")
            override fun onDiscoveryStopped(t: String) = Unit
            override fun onStartDiscoveryFailed(t: String, e: Int) = Log.w(TAG, "discovery failed $e")
            override fun onStopDiscoveryFailed(t: String, e: Int) = Unit
            override fun onServiceLost(i: NsdServiceInfo) = Log.i(TAG, "lost ${i.serviceName}")
            override fun onServiceFound(i: NsdServiceInfo) {
                if (!i.serviceType.startsWith(SERVICE_TYPE.trimEnd('.'))) return
                @Suppress("DEPRECATION") // resolveService is fine on API 26-33; registerServiceInfoCallback is 34+.
                nsd.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) = Log.w(TAG, "resolve failed $e")
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = s.host?.hostAddress ?: return
                        onFound(host, s.port, s.serviceName)
                    }
                })
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
    }

    fun stopDiscovery() {
        discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discListener = null
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_hotspotmirror._tcp."
        const val DEFAULT_PORT = 47821
        private const val TAG = "Nsd"
    }
}
