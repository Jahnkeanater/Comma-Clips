package app.commaclips.core

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Joins the comma's raw HEVC minute files into one MP4 without re-encoding, using Media3's MP4 writer.
 * (Android's MediaMuxer mishandles frames made of several slices, which the comma's frames are: the
 * result decodes as a frozen first frame with flickering blocks.)
 *
 * Plain Kotlin, so it is tested on a computer against real comma video.
 */
@UnstableApi
object Mp4Join {
  class Result(val firstFrame: Int, val frames: Int, val width: Int, val height: Int, val hasAudio: Boolean)
  class AudioSample(val data: ByteArray, val timeUs: Long, val key: Boolean)

  private const val FRAME_US = 1_000_000L / DevicePaths.FPS

  /**
   * Frames are counted from the start of the first file; [fromFrame] moves back to the keyframe at or
   * before it. Audio samples must already be on the output timeline (0 = first written frame) and sorted.
   */
  fun join(files: List<File>, fromFrame: Int, toFrame: Int, out: File,
           audio: (startFrame: Int, durationUs: Long) -> Pair<Format, List<AudioSample>>? = { _, _ -> null },
           progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Result {
    require(files.isNotEmpty())
    val first = files[0].readBytes()
    val (w, h) = Hevc.pictureSize(first) ?: error("couldn't read the video size")
    val csd = Hevc.codecConfig(first) ?: error("couldn't read the video parameters")
    val firstAus = Hevc.accessUnits(first)
    var start = fromFrame.coerceIn(0, firstAus.size - 1)
    while (start > 0 && !firstAus[start].key) start--
    val durationUs = (toFrame - start) * FRAME_US
    val (audioFormat, audioSamples) = audio(start, durationUs) ?: (null to emptyList())

    out.delete()
    val muxer = Mp4Muxer.Builder(FileOutputStream(out)).build()
    val video = muxer.addTrack(Format.Builder()
      .setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(w).setHeight(h).setFrameRate(DevicePaths.FPS.toFloat())
      .setInitializationData(listOf(csd)).build())
    val audioTrack = if (audioFormat != null && audioSamples.isNotEmpty()) muxer.addTrack(audioFormat) else -1

    var nextAudio = 0
    fun writeAudioUntil(us: Long) {
      if (audioTrack < 0) return
      while (nextAudio < audioSamples.size && audioSamples[nextAudio].timeUs <= us) {
        val a = audioSamples[nextAudio++]
        muxer.writeSampleData(audioTrack, ByteBuffer.wrap(a.data), BufferInfo(a.timeUs, a.data.size, if (a.key) C.BUFFER_FLAG_KEY_FRAME else 0))
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
            writeAudioUntil(t)  // keep the tracks interleaved
            val len = au.end - au.start
            // a private copy: the muxer converts start codes to lengths in place
            val data = ByteBuffer.allocateDirect(len).put(buf, au.start, len).apply { flip() }
            muxer.writeSampleData(video, data, BufferInfo(t, len, if (au.key) C.BUFFER_FLAG_KEY_FRAME else 0))
            written++
            if (written % 40 == 0) progress(written.toFloat() / total)
          }
          global++
        }
        if (global >= toFrame) break
      }
      writeAudioUntil(durationUs)
    } finally {
      muxer.close()
    }
    return Result(start, written, w, h, audioTrack >= 0)
  }
}
