package com.safewatch.app.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.safewatch.core.tv.Dlna
import com.safewatch.core.tv.Ssdp
import com.safewatch.core.tv.TvDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Finds the smart TVs on the home Wi-Fi that can play a video from the phone. */
object TvFinder {

    /** Asks the network and waits [waitMs] for answers. Call off the main thread. */
    fun find(context: Context, waitMs: Long = 3500): List<TvDevice> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("SafeWatch TV search").apply { setReferenceCounted(false); acquire() }
        val places = LinkedHashSet<String>() // where each smart TV describes itself
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val group = InetAddress.getByName(Ssdp.ADDRESS)
                // Only smart TVs that play a video themselves (Samsung, LG, Sony and the like); Roku has no such way.
                val questions = listOf(Ssdp.RENDERER)
                val until = System.currentTimeMillis() + waitMs
                var asked = 0
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < until) {
                    // Asked twice, a second apart: the network can lose one.
                    if (asked < 2 && System.currentTimeMillis() > until - waitMs + asked * 1000) {
                        for (q in questions) {
                            val bytes = Ssdp.question(q).toByteArray()
                            socket.send(DatagramPacket(bytes, bytes.size, group, Ssdp.PORT))
                        }
                        asked++
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val headers = Ssdp.headers(String(packet.data, 0, packet.length))
                    // Roku answers the renderer question but cannot play a video from the phone, so it is left out.
                    if (Ssdp.isRoku(headers)) continue
                    headers["LOCATION"]?.let { places += it }
                }
            }
        } finally {
            lock.release()
        }
        // Each device is asked for its name and controls, all at once.
        val pool = Executors.newFixedThreadPool(6)
        try {
            return pool.invokeAll(places.map { location -> Callable { Dlna.describe(location) } }, 8, TimeUnit.SECONDS)
                .mapNotNull { try { it.get() } catch (e: Exception) { null } }
                .distinctBy { it.host + it.controlUrl }
                .sortedBy { it.name.lowercase() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** The phone's own address on the Wi-Fi, which the TV uses to fetch the file. */
    fun phoneAddress(context: Context): InetAddress? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val networks = listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()
        for (network in networks.distinct()) {
            val caps = manager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val props = manager.getLinkProperties(network) ?: continue
            props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let { return it }
        }
        return null
    }
}
