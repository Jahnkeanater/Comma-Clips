package app.commaclips.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import app.commaclips.core.DevicePaths
import app.commaclips.core.Hevc
import java.io.File
import java.nio.ByteBuffer

/**
 * Wraps the comma's raw HEVC segment files into one MP4 (no re-encode). Frames are counted from the
 * start of the first file; [fromFrame] is moved back to the keyframe at or before it.
 */
object HevcMuxer {
  class Result(val firstFrame: Int, val frames: Int, val width: Int, val height: Int)

  private const val FRAME_US = 1_000_000L / DevicePaths.FPS

  fun mux(files: List<File>, fromFrame: Int, toFrame: Int, out: File,
          progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Result {
    require(files.isNotEmpty())
    val first = files[0].readBytes()
    val (w, h) = Hevc.pictureSize(first) ?: error("couldn't read the video size")
    val csd = Hevc.codecConfig(first) ?: error("couldn't read the video parameters")

    // keyframe at or before fromFrame (inside the first file)
    val firstAus = Hevc.accessUnits(first)
    var start = fromFrame.coerceIn(0, firstAus.size - 1)
    while (start > 0 && !firstAus[start].key) start--

    val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
      setByteBuffer("csd-0", ByteBuffer.wrap(csd))
      setInteger(MediaFormat.KEY_FRAME_RATE, DevicePaths.FPS)
    }
    out.delete()
    val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val track = muxer.addTrack(fmt)
    muxer.start()
    val info = MediaCodec.BufferInfo()
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
            info.set(0, au.end - au.start, (global - start) * FRAME_US, if (au.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, ByteBuffer.wrap(buf, au.start, au.end - au.start).slice(), info)
            written++
            if (written % 40 == 0) progress(written.toFloat() / total)
          }
          global++
        }
        if (global >= toFrame) break
      }
    } finally {
      runCatching { muxer.stop() }
      muxer.release()
    }
    return Result(start, written, w, h)
  }
}
