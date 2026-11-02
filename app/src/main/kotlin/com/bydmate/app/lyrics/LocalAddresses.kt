package com.bydmate.app.lyrics

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The head unit's own LAN addresses — what the driver types into a display that cannot find the
 * service over mDNS.
 *
 * A DiLink unit usually has two candidates at once: the station it joins (`wlan0`) and the hotspot
 * it owns itself (`ap0`, typically 192.168.4.1), and which one the display is on depends on how the
 * car is wired that day. So every site-local IPv4 is listed instead of a guess at one, and the
 * display is pointed at each in turn.
 *
 * Uses [NetworkInterface] rather than `WifiManager`: it needs no permission, no system service and
 * runs unchanged on a phone, which is what the development setup is.
 */
object LocalAddresses {

    /** Site-local IPv4 addresses a display on the same network could reach this device on. */
    fun reachable(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { net -> runCatching { net.isUp && !net.isLoopback }.getOrDefault(false) }
            ?.flatMap { net -> net.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.filter { it.isSiteLocalAddress }
            ?.map { it.hostAddress }
            ?.filterNotNull()
            ?.distinct()
            ?.toList()
            .orEmpty()
    }.getOrDefault(emptyList())
}
