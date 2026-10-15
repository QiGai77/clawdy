package io.github.nanomuse.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Plain `http://` is for the local network only (0.1.31). The platform's network security
 * config cannot say "any private address", so the rule lives here: an `http://` endpoint is
 * accepted when its host is this phone, a private range (10/8, 172.16/12, 192.168/16, the
 * carrier-grade 100.64/10 that Tailscale and friends use, link-local, IPv6 ULA/link-local) or a
 * local name (no dot, or `.local`, `.lan`, `.home`, `.internal`, `.home.arpa`, `.localdomain`,
 * Tailscale's `.ts.net`). Anything else must be `https://`. The relay address on the sign-in
 * screen is judged by the same rule ([io.github.nanomuse.cloud.RelayAddress.isPrivateHost]).
 * A LAN LLM server or a runtime on your own computer keeps working; a key sent in the clear
 * across the Internet does not.
 */
object LanOnly {
    private val localSuffixes = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain", ".ts.net")

    /** Null when the endpoint is fine; otherwise a short reason it is refused (English, for logs). */
    fun problem(url: String): String? {
        val host = refusedHost(url) ?: return null
        if (host.isEmpty()) return "the address has no host"
        return "plain http:// only works for addresses on your own network (10.x, 172.16-31.x, 192.168.x, .local names); use https:// for $host"
    }

    /**
     * The host of an `http://` endpoint that is not on the local network, or "" when the address
     * has no host at all; null when the endpoint is fine. Screens pass it to
     * `R.string.nm_lan_only_https` so the refusal reads in the person's language.
     */
    fun refusedHost(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host?.trim('[', ']')?.lowercase() ?: return ""
        return if (isLocal(host)) null else host
    }

    /** Whether [host] (a name or an IP literal) is this device or on its local network. */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost")) return true
        val literal = ipLiteral(h)
        if (literal != null) return isPrivate(literal)
        if (!h.contains('.')) return true
        return localSuffixes.any { h.endsWith(it) }
    }

    /** Whether an address is loopback, private, carrier-grade NAT or link-local. */
    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return true
        val b = address.address
        return when (address) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64 // 100.64.0.0/10
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc // fc00::/7 (ULA)
            else -> false
        }
    }

    /** The parsed address when [host] is an IP literal (no lookup is ever made), else null. */
    private fun ipLiteral(host: String): InetAddress? {
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
        if (v4 != null) {
            val parts = v4.groupValues.drop(1).map { it.toInt() }
            if (parts.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() })
        }
        if (host.contains(':')) return runCatching { InetAddress.getByName(host) }.getOrNull()
        return null
    }
}
