package app.commaclips.ssh

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The phone's own SSH key (or one the user imported), stored in the app's private storage. */
class Keys(private val dir: File) {
  val privateFile get() = File(dir, "id_ed25519")
  val publicFile get() = File(dir, "id_ed25519.pub")
  val importedFile get() = File(dir, "imported_key")

  fun hasGenerated() = privateFile.exists() && publicFile.exists()

  /** Creates a new ed25519 key pair; returns the OpenSSH public key line. */
  fun generate(comment: String = "comma-clips-android"): String {
    val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
    val pair = gen.generateKeyPair()
    val priv = pair.private as Ed25519PrivateKeyParameters
    val pub = pair.public as Ed25519PublicKeyParameters
    val blob = OpenSSHPrivateKeyUtil.encodePrivateKey(priv)
    val pem = buildString {
      append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
      Base64.getEncoder().encodeToString(blob).chunked(70).forEach { append(it).append('\n') }
      append("-----END OPENSSH PRIVATE KEY-----\n")
    }
    dir.mkdirs()
    writePrivate(privateFile, pem.toByteArray())
    val line = "ssh-ed25519 " + Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(pub)) + " " + comment
    publicFile.writeText(line + "\n")
    return line
  }

  fun publicKeyLine(): String? = publicFile.takeIf { it.exists() }?.readText()?.trim()

  fun fingerprint(): String? = publicKeyLine()?.split(" ")?.getOrNull(1)?.let { sha256Fingerprint(Base64.getDecoder().decode(it)) }

  /** Saves a private key file the user picked. Throws with a readable message if it's not usable. */
  fun import(bytes: ByteArray) {
    val text = String(bytes, Charsets.UTF_8)
    require(!text.trimStart().startsWith("ssh-")) { "That's a public key. Pick the private key file (the one without .pub)." }
    require("PRIVATE KEY" in text) { "That file isn't an SSH private key." }
    require("ENCRYPTED" !in text && !hasPassphrase(text)) { "That key has a passphrase, which the app doesn't support. Use a key without one, or let the app make its own." }
    dir.mkdirs()
    writePrivate(importedFile, bytes)
  }

  private fun hasPassphrase(text: String): Boolean {
    // openssh-key-v1: cipher name follows the magic; "none" means no passphrase
    if ("BEGIN OPENSSH PRIVATE KEY" !in text) return false
    val b64 = text.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
    val blob = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return false
    val magic = "openssh-key-v1\u0000".toByteArray()
    if (blob.size < magic.size + 8) return false
    val n = java.nio.ByteBuffer.wrap(blob, magic.size, 4).int
    val cipher = String(blob, magic.size + 4, n)
    return cipher != "none"
  }

  private fun writePrivate(f: File, data: ByteArray) {
    f.writeBytes(data)
    f.setReadable(false, false); f.setReadable(true, true)
    f.setWritable(false, false); f.setWritable(true, true)
  }

  companion object {
    fun sha256Fingerprint(blob: ByteArray): String =
      "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
  }
}
