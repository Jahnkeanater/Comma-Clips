package app.commaclips.data

import android.content.Context
import android.net.ConnectivityManager
import app.commaclips.core.*
import app.commaclips.ssh.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address

sealed interface Conn {
  data object NotSetUp : Conn
  data object NoKey : Conn
  data object Connecting : Conn
  data object Online : Conn
  data class NeedsTrust(val err: UnknownHostKey) : Conn
  data class Failed(val message: String) : Conn
}

data class TestResult(val ok: Boolean, val message: String, val trust: UnknownHostKey? = null)

/** App-wide access to the comma: connection, drive list, thumbnails and telemetry (all read-only). */
class Repo(private val ctx: Context) {
  val prefs = Prefs(ctx)
  val keys = Keys(File(ctx.filesDir, "ssh"))
  private val thumbDir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
  private val telDir = File(ctx.cacheDir, "telemetry").apply { mkdirs() }
  val segDir = File(ctx.cacheDir, "segments").apply { mkdirs() }

  private val _conn = MutableStateFlow<Conn>(Conn.NotSetUp)
  val conn: StateFlow<Conn> = _conn
  private val _routes = MutableStateFlow<List<Route>?>(null)
  val routes: StateFlow<List<Route>?> = _routes
  private val _thumbs = MutableStateFlow<Map<String, File>>(emptyMap())
  val thumbs: StateFlow<Map<String, File>> = _thumbs

  private var ssh: CommaSsh? = null
  private val mutex = Mutex()

  init {
    _thumbs.value = thumbDir.listFiles()?.filter { it.length() > 0 }?.associateBy { it.nameWithoutExtension } ?: emptyMap()
    _conn.value = initialState()
  }

  private fun initialState(): Conn = when {
    activeKeyFile() == null -> Conn.NoKey
    prefs.address.isBlank() -> Conn.NotSetUp
    else -> Conn.Connecting
  }

  fun activeKeyFile(): File? = when (prefs.keyMode) {
    "imported" -> keys.importedFile.takeIf { it.exists() }
    else -> keys.privateFile.takeIf { it.exists() }
  }

  private fun script(name: String) = ctx.assets.open(name).use { it.readBytes() }

  @Synchronized fun client(): CommaSsh {
    ssh?.let { return it }
    val key = activeKeyFile() ?: throw SshError("This phone has no SSH key yet. Open Connection to create one.")
    if (prefs.address.isBlank()) throw SshError("No comma set up yet. Open Connection to find it.")
    return CommaSsh(prefs.address, prefs.user, key, prefs.trustedKeys).also { ssh = it }
  }

  @Synchronized fun dropClient() { ssh?.close(); ssh = null }

  /** Runs [block] with the comma, updating the connection state and following the comma if its IP changed. */
  suspend fun <T> withComma(block: (CommaSsh) -> T): T = withContext(Dispatchers.IO) {
    try {
      val r = block(client()); _conn.value = Conn.Online; r
    } catch (e: UnknownHostKey) {
      _conn.value = Conn.NeedsTrust(e); throw e
    } catch (e: SshError) {
      if (e.cause !is net.schmizz.sshj.userauth.UserAuthException && relocate()) {
        val r = block(client()); _conn.value = Conn.Online; r
      } else { _conn.value = if (activeKeyFile() == null) Conn.NoKey else Conn.Failed(e.message ?: "connection failed"); throw e }
    }
  }

  /** If the saved address stopped answering, look for the trusted comma elsewhere on the network. */
  private fun relocate(): Boolean {
    if (prefs.trustedKeys.isEmpty() || prefs.address.isBlank()) return false
    if (Discovery.portOpen(prefs.address)) return false
    val (ip, prefix) = localNetwork() ?: return false
    val found = Discovery.findTrusted(ip, prefix, prefs.trustedKeys) ?: return false
    prefs.address = found.ip
    dropClient()
    return true
  }

  fun localNetwork(): Pair<String, Int>? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
    val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
    return la.address.hostAddress!! to la.prefixLength
  }

  suspend fun refreshRoutes(): List<Route> = mutex.withLock {
    val list = withComma { RouteParser.parse(it.exec(DevicePaths.LIST_COMMAND)) }
    _routes.value = list
    list
  }

  suspend fun loadThumbs(routes: List<Route>) {
    val missing = routes.map { it.name }.filter { it !in _thumbs.value }
    if (missing.isEmpty()) return
    for (chunk in missing.chunked(20)) {
      val out = runCatching {
        withComma { it.exec(DevicePaths.pythonCommand(listOf(DevicePaths.REALDATA) + chunk), script("remote_thumbs.py")) }
      }.getOrNull() ?: return
      val add = HashMap<String, File>()
      for (line in out.lineSequence().filter { it.isNotBlank() }) {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
        val name = o.optString("route")
        val f = File(thumbDir, "$name.jpg")
        val jpg = o.optString("jpg")
        if (jpg.isNotEmpty()) f.writeBytes(android.util.Base64.decode(jpg, android.util.Base64.DEFAULT)) else f.writeBytes(ByteArray(0))
        if (f.length() > 0) add[name] = f
      }
      _thumbs.value = _thumbs.value + add
    }
  }

  /** Telemetry for segments, parsed on the comma (read-only) and cached on the phone. */
  suspend fun telemetry(route: String, segs: List<Int>, progress: (Int, Int) -> Unit = { _, _ -> }): Map<Int, SegmentTelemetry> {
    val dir = File(telDir, route).apply { mkdirs() }
    val result = HashMap<Int, SegmentTelemetry>()
    val todo = ArrayList<Int>()
    for (s in segs) {
      val f = File(dir, "$s.json")
      // older caches lack GPS, blinkers, pedals and g-force: read those segments again
      val cached = if (f.exists()) runCatching { SegmentTelemetry.parse(JSONObject(f.readText())) }.getOrNull() else null
      if (cached != null && cached.format >= 2) result[s] = cached else todo.add(s)
    }
    progress(result.size, segs.size)
    if (todo.isEmpty()) return result
    withComma { c ->
      val args = listOf(DevicePaths.REALDATA, route) + todo.map { it.toString() }
      c.execStreaming(DevicePaths.pythonCommand(args), script("remote_telemetry.py")) { input ->
        input.bufferedReader().forEachLine { line ->
          val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
          if (!o.has("seg") || o.has("error")) return@forEachLine
          File(dir, "${o.getInt("seg")}.json").writeText(line)
          result[o.getInt("seg")] = SegmentTelemetry.parse(o)
          progress(result.size, segs.size)
        }
      }
    }
    return result
  }

  suspend fun deviceOnroad(): Boolean = runCatching {
    withComma { it.exec("cat /data/params/d/IsOnroad 2>/dev/null").trim() == "1" }
  }.getOrDefault(false)

  // ---------------------------------------------------------------- connection settings

  suspend fun test(address: String): TestResult = withContext(Dispatchers.IO) {
    val key = activeKeyFile() ?: return@withContext TestResult(false, "Create or import an SSH key first.")
    val addr = address.trim().substringAfter('@')
    if (addr.isEmpty()) return@withContext TestResult(false, "Enter the comma's IP address.")
    try {
      CommaSsh(addr, prefs.user, key, prefs.trustedKeys).use { c ->
        val out = c.exec("hostname; cat /data/params/d/DongleId 2>/dev/null; echo; test -d ${DevicePaths.REALDATA} && echo DATA")
        val lines = out.lines()
        if ("DATA" !in lines.map { it.trim() }) TestResult(false, "Logged in, but there's no drive folder. Is this a comma?")
        else TestResult(true, "Found ${lines.firstOrNull()?.trim().orEmpty()} at $addr")
      }
    } catch (e: UnknownHostKey) {
      TestResult(false, e.message ?: "", e)
    } catch (e: Exception) {
      TestResult(false, e.message ?: e.javaClass.simpleName)
    }
  }

  fun trust(err: UnknownHostKey) {
    prefs.trustedKeys = prefs.trustedKeys + err.blob
    dropClient()
  }

  fun saveAddress(address: String) {
    prefs.address = address.trim().substringAfter('@')
    dropClient()
    _routes.value = null
    _conn.value = Conn.Connecting
  }

  fun keyChanged() {
    dropClient()
    _conn.value = initialState()
  }

  suspend fun scan(): List<Found> = withContext(Dispatchers.IO) {
    val (ip, prefix) = localNetwork() ?: throw SshError("Not connected to a Wi-Fi network.")
    Discovery.scan(ip, prefix, prefs.trustedKeys)
  }
}
