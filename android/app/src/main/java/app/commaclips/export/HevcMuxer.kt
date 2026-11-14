package app.commaclips.export

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import app.commaclips.core.DevicePaths
import app.commaclips.core.Mp4Join
import java.io.File
import java.nio.ByteBuffer

/**
 * Builds the joined MP4 (see [Mp4Join]) and adds the cabin audio from the preview files (qcamera.ts),
 * copied as-is. Each minute's audio is placed relative to that minute's first video frame using the
 * file's own timestamps, so it stays in sync over long clips.
 */
@UnstableApi
object HevcMuxer {
  private const val FRAME_US = 1_000_000L / DevicePaths.FPS
  private const val SEG_US = (DevicePaths.SEG_SECONDS * 1_000_000).toLong()

  fun mux(files: List<File>, fromFrame: Int, toFrame: Int, out: File,
          audio: List<File?> = emptyList(),  // preview file per segment (same order as files), or null
          progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Mp4Join.Result {
    val format = audio.firstNotNullOfOrNull { f -> f?.let { audioFormat(it) } }
    return Mp4Join.join(files, fromFrame, toFrame, out, format,
      audio = { start, dur -> collectAudio(audio, start, dur) }, progress = progress, cancelled = cancelled)
  }

  private fun audioTrack(ex: MediaExtractor): Pair<Int, Int> {
    var v = -1; var a = -1
    for (i in 0 until ex.trackCount) {
      val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith("video/") && v < 0) v = i
      if (mime.startsWith("audio/") && a < 0) a = i
    }
    return v to a
  }

  /** The preview files carry AAC; describe it the way Media3's MP4 writer wants. */
  private fun audioFormat(f: File): Format? = runCatching {
    if (!f.exists()) return null
    val ex = MediaExtractor()
    try {
      ex.setDataSource(f.absolutePath)
      val (_, a) = audioTrack(ex)
      if (a < 0) return null
      val mf = ex.getTrackFormat(a)
      if (mf.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return null
      val csd = mf.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } } ?: return null
      Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setSampleRate(mf.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        .setChannelCount(mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        .setInitializationData(listOf(csd)).build()
    } finally { ex.release() }
  }.getOrNull()

  /** AAC samples from the preview files, re-timed onto the clip's timeline. */
  private fun collectAudio(audio: List<File?>, startFrame: Int, durationUs: Long): List<Mp4Join.AudioSample> {
    val samples = ArrayList<Mp4Join.AudioSample>()
    val clipStartUs = startFrame * FRAME_US
    for ((k, f) in audio.withIndex()) {
      if (f == null || !f.exists()) continue
      val ex = MediaExtractor()
      try {
        ex.setDataSource(f.absolutePath)
        val (vIdx, aIdx) = audioTrack(ex)
        if (vIdx < 0 || aIdx < 0) continue
        ex.selectTrack(vIdx)                 // first video frame time: the anchor for this minute
        val vStart = ex.sampleTime.takeIf { it >= 0 } ?: continue
        ex.unselectTrack(vIdx)
        ex.selectTrack(aIdx)
        ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buf = ByteBuffer.allocate(1 shl 16)
        while (true) {
          val n = ex.readSampleData(buf, 0)
          if (n < 0) break
          val rel = ex.sampleTime - vStart                    // time after this minute's first frame
          val outUs = k * SEG_US + rel - clipStartUs           // position in the clip
          if (rel in 0 until SEG_US && outUs in 0 until durationUs) {
            val data = ByteArray(n); buf.position(0); buf.get(data, 0, n)
            samples.add(Mp4Join.AudioSample(data, outUs, ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0))
          }
          buf.clear()
          ex.advance()
        }
      } catch (e: Exception) {
        // a broken preview file just means silence for that minute
      } finally {
        ex.release()
      }
    }
    samples.sortBy { it.timeUs }
    val dedup = ArrayList<Mp4Join.AudioSample>(samples.size)
    for (s in samples) if (dedup.isEmpty() || s.timeUs > dedup.last().timeUs) dedup.add(s)
    return dedup
  }
}
