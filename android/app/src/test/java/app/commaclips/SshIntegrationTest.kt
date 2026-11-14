package app.commaclips

import app.commaclips.core.*
import app.commaclips.ssh.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Talks to a real comma when COMMA_TEST_HOST (ip) and COMMA_TEST_KEY (private key that the comma accepts)
 * are set. Read-only: lists files, reads a few bytes over SFTP and runs the read-only scripts.
 */
class SshIntegrationTest {
  private val host = System.getenv("COMMA_TEST_HOST")
  private val key = System.getenv("COMMA_TEST_KEY")?.let(::File)
  private val assets = File("src/main/assets")

  private fun trusted(): Set<String> = setOfNotNull(CommaSsh.probeHostKey(host)?.second)

  @Test fun generatedKeyLoadsInSshj() {
    val dir = Files.createTempDirectory("keys").toFile()
    val k = Keys(dir)
    val line = k.generate()
    assertTrue(line.startsWith("ssh-ed25519 "))
    val c = net.schmizz.sshj.SSHClient()
    val kp = c.loadKeys(k.privateFile.absolutePath)
    assertEquals("Ed25519", kp.public.algorithm.let { if (it == "EdDSA") "Ed25519" else it })
    // fingerprint matches ssh-keygen
    val p = ProcessBuilder("ssh-keygen", "-lf", k.publicFile.absolutePath).start()
    val fp = p.inputStream.bufferedReader().readText().split(" ")[1]
    assertEquals(fp, k.fingerprint())
    // importing the public key is rejected with a clear message
    val err = runCatching { k.import(k.publicFile.readBytes()) }.exceptionOrNull()
    assertTrue(err?.message?.contains("public key") == true)
    k.import(k.privateFile.readBytes())
    c.close()
  }

  @Test fun unknownHostKeyIsReported() {
    assumeTrue(host != null && key?.exists() == true)
    val e = runCatching { CommaSsh(host, "comma", key!!, emptySet()).use { it.exec("true") } }.exceptionOrNull()
    assertTrue("expected UnknownHostKey, got $e", e is UnknownHostKey)
    println("fingerprint ${(e as UnknownHostKey).fingerprint}")
  }

  @Test fun listsRoutesReadsFilesAndRunsScripts() {
    assumeTrue(host != null && key?.exists() == true)
    CommaSsh(host, "comma", key!!, trusted()).use { ssh ->
      val routes = RouteParser.parse(ssh.exec(DevicePaths.LIST_COMMAND))
      println("routes: ${routes.size}, newest ${routes.first().name} (${routes.first().minutes} min, cams ${routes.first().cameras})")
      assertTrue(routes.isNotEmpty())
      val r = routes.first()
      val seg = r.segments.first { "qcamera.ts" in it.files }
      val path = "${DevicePaths.REALDATA}/${r.name}--${seg.n}/qcamera.ts"
      assertEquals(seg.files["qcamera.ts"], ssh.size(path))
      ssh.openRead(path).use { f ->
        val b = ByteArray(188); val n = f.read(0, b, 0, b.size)
        assertEquals(188, n); assertEquals(0x47, b[0].toInt() and 0xff) // MPEG-TS sync byte
        val n2 = f.read(188 * 100L, b, 0, b.size); assertEquals(188, n2); assertEquals(0x47, b[0].toInt() and 0xff)
      }
      // thumbnails
      val thumbs = ssh.exec(DevicePaths.pythonCommand(listOf(DevicePaths.REALDATA) + routes.take(3).map { it.name }),
        File(assets, "remote_thumbs.py").readBytes())
      val t = thumbs.lines().filter { it.isNotBlank() }.map { JSONObject(it) }
      println("thumbs: " + t.map { it.optString("jpg").length })
      assertTrue(t.first().has("jpg"))
      // telemetry for one segment
      val tl = ssh.exec(DevicePaths.pythonCommand(listOf(DevicePaths.REALDATA, r.name, seg.n.toString())),
        File(assets, "remote_telemetry.py").readBytes())
      val st = SegmentTelemetry.parse(JSONObject(tl.lines().first { it.isNotBlank() }))
      val tel = Telemetry(mapOf(seg.n to st), listOf(seg.n), "road")
      val s = tel.sample(tel.frameMono(600)!!)
      println("telemetry frames=${st.frames["road"]?.size} samples=${st.t.size} at 30s: $s wall=${tel.wallMillis(tel.frameMono(0)!!)}")
      assertNotNull(s)
      assertEquals(2, st.format)
      assertEquals(st.t.size, st.ay.size); assertEquals(st.t.size, st.blink.size); assertEquals(st.t.size, st.lat.size)
      val withFix = st.lat.count { !it.isNaN() }
      println("format ${st.format}: gps fixes $withFix/${st.t.size}, blinker samples ${st.blink.count { it != 0 }}, pedal samples ${st.ped.count { it != 0 }}, max |g| ${"%.2f".format(st.ay.filter { !it.isNaN() }.maxOfOrNull { kotlin.math.abs(it) / 9.81f } ?: 0f)}")
    }
  }
}
