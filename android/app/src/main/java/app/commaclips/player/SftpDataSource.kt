package app.commaclips.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.commaclips.core.DevicePaths
import app.commaclips.data.Repo
import java.io.IOException
import java.io.InputStream
import net.schmizz.sshj.sftp.RemoteFile

/** Plays files straight off the comma over SFTP. URIs look like comma:///<route>--<seg>/qcamera.ts */
@UnstableApi
class SftpDataSource(private val repo: Repo) : BaseDataSource(true) {
  private var file: RemoteFile? = null
  private var stream: InputStream? = null
  private var uri: Uri? = null
  private var remaining = 0L

  override fun open(dataSpec: DataSpec): Long {
    uri = dataSpec.uri
    transferInitializing(dataSpec)
    val path = DevicePaths.REALDATA + "/" + (dataSpec.uri.path ?: "").trimStart('/')
    try {
      val f = repo.client().openRead(path)
      file = f
      val size = f.length()
      remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
      stream = f.ReadAheadRemoteFileInputStream(16, dataSpec.position)
    } catch (e: Exception) {
      repo.dropClient()
      throw IOException("Couldn't open $path on the comma: ${e.message}", e)
    }
    transferStarted(dataSpec)
    return remaining
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (remaining == 0L) return C.RESULT_END_OF_INPUT
    val n = stream!!.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
    if (n < 0) return C.RESULT_END_OF_INPUT
    remaining -= n
    bytesTransferred(n)
    return n
  }

  override fun getUri(): Uri? = uri

  override fun close() {
    runCatching { stream?.close() }; runCatching { file?.close() }
    stream = null; file = null
    if (uri != null) { uri = null; transferEnded() }
  }

  class Factory(private val repo: Repo) : DataSource.Factory {
    override fun createDataSource(): DataSource = SftpDataSource(repo)
  }

  companion object {
    fun uri(route: String, seg: Int, file: String): Uri = Uri.parse("comma:///$route--$seg/$file")
  }
}
