package app.commaclips.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

class SshError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The device's host key isn't trusted yet: the user has to confirm the fingerprint first. */
class UnknownHostKey(val address: String, val type: String, val blob: String) :
  Exception("This device's SSH key isn't trusted yet") {
  val fingerprint get() = Keys.sha256Fingerprint(Base64.getDecoder().decode(blob))
}

fun hostKeyBlob(key: PublicKey): Pair<String, String> {
  val buf = Buffer.PlainBuffer().putPublicKey(key)
  val bytes = buf.compactData
  val type = net.schmizz.sshj.common.KeyType.fromKey(key).toString()
  return type to Base64.getEncoder().encodeToString(bytes)
}

/**
 * One SSH connection to the comma. Read-only use: listing files, reading them over SFTP and running the
 * telemetry/thumbnail scripts (which only read logs).
 */
class CommaSsh(
  val address: String,
  val user: String,
  private val keyFile: File,
  private val trusted: Set<String>,  // trusted host key blobs (base64)
  private val port: Int = 22,
) : AutoCloseable {
  private var client: SSHClient? = null
  private var sftp: SFTPClient? = null
  private val lock = Any()

  private fun connect(): SSHClient {
    val c = SSHClient(DefaultConfig())
    c.connectTimeout = 6000
    c.timeout = 30000
    var unknown: UnknownHostKey? = null
    c.addHostKeyVerifier(object : HostKeyVerifier {
      override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val (type, blob) = hostKeyBlob(key)
        if (blob in trusted) return true
        unknown = UnknownHostKey(address, type, blob)
        return false
      }
      override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    })
    try {
      c.connect(address, port)
    } catch (e: Exception) {
      runCatching { c.close() }
      unknown?.let { throw it }
      throw SshError("Can't reach $address: ${e.message ?: e.javaClass.simpleName}", e)
    }
    try {
      c.authPublickey(user, c.loadKeys(keyFile.absolutePath))
    } catch (e: Exception) {
      runCatching { c.close() }
      throw SshError("The comma didn't accept this phone's key (log in as $user failed). " +
        "Add the key to your GitHub account and re-enter your GitHub username on the comma.", e)
    }
    c.connection.keepAlive.keepAliveInterval = 15
    return c
  }

  private fun ssh(): SSHClient = synchronized(lock) {
    client?.takeIf { it.isConnected && it.isAuthenticated } ?: connect().also { client = it; sftp = null }
  }

  private fun sftpClient(): SFTPClient = synchronized(lock) {
    sftp ?: ssh().newSFTPClient().also { sftp = it }
  }

  /** Runs a command and returns stdout. [stdin] is written then closed (for the python scripts). */
  fun exec(cmd: String, stdin: ByteArray? = null, timeoutSec: Long = 120): String {
    val out = java.io.ByteArrayOutputStream()
    execStreaming(cmd, stdin, timeoutSec) { input -> input.copyTo(out) }
    return out.toString(Charsets.UTF_8.name())
  }

  fun execStreaming(cmd: String, stdin: ByteArray?, timeoutSec: Long = 600, read: (InputStream) -> Unit) {
    val session = try { ssh().startSession() } catch (e: Exception) { reset(); ssh().startSession() }
    session.use { s ->
      val c = s.exec(cmd)
      if (stdin != null) { c.outputStream.write(stdin); c.outputStream.flush(); c.outputStream.close() }
      val errBuf = java.io.ByteArrayOutputStream()
      val errThread = Thread { runCatching { c.errorStream.copyTo(errBuf) } }.apply { start() }
      read(c.inputStream)
      c.join(timeoutSec, TimeUnit.SECONDS)
      errThread.join(2000)
      val status = c.exitStatus
      if (status != null && status != 0) {
        val err = errBuf.toString().trim().lines().lastOrNull().orEmpty()
        throw SshError(if (err.isNotBlank()) err else "command failed ($status)")
      }
    }
  }

  fun openRead(path: String): RemoteFile = sftpClient().open(path, java.util.EnumSet.of(OpenMode.READ))

  fun size(path: String): Long = sftpClient().size(path)

  /** Copies a device file to [out], reporting bytes copied. */
  fun download(path: String, out: OutputStream, progress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }) {
    openRead(path).use { rf ->
      val buf = ByteArray(1 shl 18)
      var off = 0L
      while (true) {
        if (cancelled()) throw InterruptedException("cancelled")
        val n = rf.read(off, buf, 0, buf.size)
        if (n <= 0) break
        out.write(buf, 0, n)
        off += n
        progress(off)
      }
    }
  }

  fun reset() = synchronized(lock) {
    runCatching { sftp?.close() }; runCatching { client?.disconnect() }
    sftp = null; client = null
  }

  override fun close() = reset()

  companion object {
    /** Connects without logging in, just to read the device's host key. */
    fun probeHostKey(address: String, port: Int = 22, timeoutMs: Int = 3000): Pair<String, String>? {
      val c = SSHClient(DefaultConfig())
      c.connectTimeout = timeoutMs
      c.timeout = timeoutMs
      var found: Pair<String, String>? = null
      c.addHostKeyVerifier(object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean { found = hostKeyBlob(key); return false }
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
      })
      runCatching { c.connect(address, port) }
      runCatching { c.close() }
      return found
    }
  }
}
