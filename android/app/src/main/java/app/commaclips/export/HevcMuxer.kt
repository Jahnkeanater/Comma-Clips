package app.commaclips.export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.commaclips.core.DevicePaths
import app.commaclips.core.Hevc
import java.io.File
import java.nio.ByteBuffer

/**
 * Wraps the comma's raw HEVC segment files into one MP4 (no re-encode). Frames are counted from the
 * start of the first file; [fromFrame] is moved back to the keyframe at or before it.
 *
 * Optionally adds the cabin audio from the preview files (qcamera.ts), copied as-is. Each minute's
 * audio is placed relative to that minute's first video frame using the file's own timestamps, so it
 * stays in sync over long clips.
 */
object HevcMuxer {
  class Result(val firstFrame: Int, val frames: Int, val width: Int, val height: Int, val hasAudio: Boolean)

  private const val FRAME_US = 1_000_000L / DevicePaths.FPS
  private const val SEG_US = (DevicePaths.SEG_SECONDS * 1_000_000).toLong()

  private class AudioSample(val data: ByteArray, val timeUs: Long, val flags: Int)

  fun mux(files: List<File>, fromFrame: Int, toFrame: Int, out: File,
          audio: List<File?> = emptyList(),  // preview file per segment (same order as files), or null
          progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Result {
    require(files.isNotEmpty())
    val first = files[0].readBytes()
    val (w, h) = Hevc.pictureSize(first) ?: error("couldn't read the video size")
    val csd = Hevc.codecConfig(first) ?: error("couldn't read the video parameters")

    // keyframe at or before fromFrame (inside the first file)
    val firstAus = Hevc.accessUnits(first)
    var start = fromFrame.coerceIn(0, firstAus.size - 1)
    while (start > 0 && !firstAus[start].key) start--
    val durationUs = (toFrame - start) * FRAME_US

    val (audioFormat, audioSamples) = collectAudio(audio, start, durationUs)

    val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
      setByteBuffer("csd-0", ByteBuffer.wrap(csd))
      setInteger(MediaFormat.KEY_FRAME_RATE, DevicePaths.FPS)
    }
    out.delete()
    val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val track = muxer.addTrack(fmt)
    val audioTrack = if (audioFormat != null && audioSamples.isNotEmpty()) muxer.addTrack(audioFormat) else -1
    muxer.start()
    val info = MediaCodec.BufferInfo()
    var nextAudio = 0
    fun writeAudioUntil(us: Long) {
      if (audioTrack < 0) return
      while (nextAudio < audioSamples.size && audioSamples[nextAudio].timeUs <= us) {
        val a = audioSamples[nextAudio++]
        info.set(0, a.data.size, a.timeUs, a.flags)
        muxer.writeSampleData(audioTrack, ByteBuffer.wrap(a.data), info)
      }
    }
    var global = 0
    var written = 0
    val total = (toFrame - start).coerceAtLeast(1)
    try {
      for ((k, f) in files.withIndex()) {
        val buf = if (k == 0) first else f.readBytes()
        val aus = if (k == 0) firstAus else Hevc.accessUnits(buf)
        for (au in aus) {
          if (cancelled()) throw InterruptedException("cancelled")
          if (global >= toFrame) break
          if (global >= start) {
            val t = (global - start) * FRAME_US
            writeAudioUntil(t)  // keep the two tracks interleaved
            info.set(0, au.end - au.start, t, if (au.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, ByteBuffer.wrap(buf, au.start, au.end - au.start).slice(), info)
            written++
            if (written % 40 == 0) progress(written.toFloat() / total)
          }
          global++
        }
        if (global >= toFrame) break
      }
      writeAudioUntil(durationUs)
    } finally {
      runCatching { muxer.stop() }
      muxer.release()
    }
    return Result(start, written, w, h, audioTrack >= 0)
  }

  /** AAC samples from the preview files, re-timed onto the clip's timeline. */
  private fun collectAudio(audio: List<File?>, startFrame: Int, durationUs: Long): Pair<MediaFormat?, List<AudioSample>> {
    var format: MediaFormat? = null
    val samples = ArrayList<AudioSample>()
    val clipStartUs = startFrame * FRAME_US
    for ((k, f) in audio.withIndex()) {
      if (f == null || !f.exists()) continue
      val ex = MediaExtractor()
      try {
        ex.setDataSource(f.absolutePath)
        var vIdx = -1; var aIdx = -1
        for (i in 0 until ex.trackCount) {
          val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
          if (mime.startsWith("video/") && vIdx < 0) vIdx = i
          if (mime.startsWith("audio/") && aIdx < 0) aIdx = i
        }
        if (vIdx < 0 || aIdx < 0) continue
        // first video frame time: the anchor for this minute
        ex.selectTrack(vIdx)
        val vStart = ex.sampleTime.takeIf { it >= 0 } ?: continue
        ex.unselectTrack(vIdx)
        ex.selectTrack(aIdx)
        ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (format == null) format = ex.getTrackFormat(aIdx)
        val buf = ByteBuffer.allocate(1 shl 16)
        while (true) {
          val n = ex.readSampleData(buf, 0)
          if (n < 0) break
          val rel = ex.sampleTime - vStart                       // time after this minute's first frame
          val outUs = k * SEG_US + rel - clipStartUs              // position in the clip
          if (rel in 0 until SEG_US && outUs in 0 until durationUs) {
            val data = ByteArray(n); buf.position(0); buf.get(data, 0, n)
            samples.add(AudioSample(data, outUs, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0))
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
    // drop samples that would collide at the same time (overlap at minute edges)
    val dedup = ArrayList<AudioSample>(samples.size)
    for (s in samples) if (dedup.isEmpty() || s.timeUs > dedup.last().timeUs) dedup.add(s)
    return format to dedup
  }
}
