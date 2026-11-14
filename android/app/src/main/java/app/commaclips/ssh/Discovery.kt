package app.commaclips.ssh

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class Found(
  val ip: String,
  val name: String?,
  val comma: Boolean,       // reverse DNS name starts with "comma-"
  val trusted: Boolean,     // its host key is one the app already trusts
  val keyType: String?,
  val keyBlob: String?,
)

/** Finds devices on the local network that accept SSH and says which look like a comma. */
object Discovery {
  fun portOpen(ip: String, port: Int = 22, timeoutMs: Int = 600): Boolean =
    runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }; true }.getOrDefault(false)

  /** All host addresses of a /24 (or smaller) network around [ip] with [prefix] bits. */
  fun hosts(ip: String, prefix: Int): List<String> {
    val p = prefix.coerceIn(22, 30)
    val addr = InetAddress.getByName(ip).address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
    val mask = (0xffffffffL shl (32 - p)) and 0xffffffffL
    val net = addr and mask
    val count = (1L shl (32 - p))
    return (1 until count - 1).map { i ->
      val v = net + i
      "${(v shr 24) and 0xff}.${(v shr 16) and 0xff}.${(v shr 8) and 0xff}.${v and 0xff}"
    }
  }

  fun scan(localIp: String, prefix: Int, trustedBlobs: Set<String>): List<Found> {
    val pool = Executors.newFixedThreadPool(64)
    try {
      val open = hosts(localIp, prefix).filter { it != localIp }
        .map { ip -> pool.submit(Callable { if (portOpen(ip)) ip else null }) }
        .mapNotNull { it.get() }
      val described = open.map { ip ->
        pool.submit(Callable {
          val name = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip }
          val key = CommaSsh.probeHostKey(ip)
          Found(ip, name, name?.substringBefore('.')?.startsWith("comma-", ignoreCase = true) == true,
            key?.second in trustedBlobs, key?.first, key?.second)
        })
      }.map { it.get() }
      return described.sortedWith(compareBy<Found>({ !it.trusted }, { !it.comma }, { ipKey(it.ip) }))
    } finally {
      pool.shutdownNow()
    }
  }

  /** Finds a trusted comma whose IP changed (same host key, new address). */
  fun findTrusted(localIp: String, prefix: Int, trustedBlobs: Set<String>): Found? =
    scan(localIp, prefix, trustedBlobs).firstOrNull { it.trusted }

  private fun ipKey(ip: String) = ip.split(".").fold(0L) { a, s -> a * 256 + (s.toLongOrNull() ?: 0) }
}
