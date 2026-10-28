package dev.ajithv.sonybridge

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    // true hotspot/tether iface name hints (preferred)
    private val AP_RE = Regex("^(ap\\d|softap|swlan|rndis|tether|usb|bridge)", RegexOption.IGNORE_CASE)
    // any Wi-Fi iface (client OR a bridged/shared hotspot) — used as a fallback address
    private val WLAN_RE = Regex("^wlan\\d", RegexOption.IGNORE_CASE)
    // interfaces that are NEVER reachable by the camera (VPN / cellular / virtual)
    private val SKIP_RE = Regex("(tun|ppp|rmnet|ipa|vgate|dummy|clat|p2p|aware)", RegexOption.IGNORE_CASE)

    data class Info(val apIp: String?, val apIface: String?, val all: List<Pair<String, String>>)

    private fun isPrivate(ip: String) =
        ip.startsWith("192.168.") || ip.startsWith("10.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(ip)

    fun scan(): Info {
        val all = ArrayList<Pair<String, String>>()
        var byName: Pair<String, String>? = null    // AP-named iface (classic NAT hotspot)
        var byDotOne: Pair<String, String>? = null  // private .1 gateway
        var byWlan: Pair<String, String>? = null     // any wlan address (bridged/shared hotspot, or same-Wi-Fi)
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val skip = SKIP_RE.containsMatchIn(ni.name)
                for (a in ni.inetAddresses) {
                    if (a !is Inet4Address || a.isLoopbackAddress) continue
                    val ip = a.hostAddress ?: continue
                    all.add(ni.name to ip)
                    if (skip || !isPrivate(ip)) continue
                    if (AP_RE.containsMatchIn(ni.name) && byName == null) byName = ni.name to ip
                    if (ip.endsWith(".1") && byDotOne == null) byDotOne = ni.name to ip
                    if (WLAN_RE.containsMatchIn(ni.name) && byWlan == null) byWlan = ni.name to ip
                }
            }
        } catch (_: Exception) {}
        // Prefer a real AP/.1 gateway; otherwise fall back to the Wi-Fi address (works when the camera
        // is on the same Wi-Fi / a bridged hotspot — which is how OnePlus shares the link).
        val pick = byName ?: byDotOne ?: byWlan
        return Info(pick?.second, pick?.first, all)
    }

    fun localIp(): String = scan().apIp ?: "?"
}
