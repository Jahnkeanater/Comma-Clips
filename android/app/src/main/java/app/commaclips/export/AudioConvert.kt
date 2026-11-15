package app.commaclips.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.media3.common.util.UnstableApi
import app.commaclips.core.AudioResample
import app.commaclips.core.Mp4Join
import java.nio.ByteOrder

/**
 * Converts the comma's 16 kHz mono AAC into standard 48 kHz stereo AAC-LC with the phone's own codecs:
 * decode to PCM, resample ([AudioResample]), encode. Google Photos won't play the original format.
 */
@UnstableApi
object AudioConvert {
  private const val TIMEOUT_US = 10_000L

  /** [frames] are raw AAC frames already on the clip timeline. Returns AAC-LC 48 kHz stereo frames. */
  fun toStereo48k(frames: List<Mp4Join.AudioSample>, csd: ByteArray, inRate: Int, inChannels: Int,
                  durationUs: Long, cancelled: () -> Boolean = { false }): List<Mp4Join.AudioSample> {
    val chunks = decode(frames, csd, inRate, inChannels, cancelled)
    // nothing decoded, or only digital silence, means the decoder couldn't read the frames: let the caller
    // fall back to the original audio instead of writing a silent track
    require(chunks.any { c -> c.second.any { it.toInt() != 0 } }) { "audio decoded to silence" }
    val mono = AudioResample.place(chunks, inRate, durationUs)
    val stereo = AudioResample.monoToStereo(mono, inRate)
    return encode(stereo, cancelled)
  }

  private fun decode(frames: List<Mp4Join.AudioSample>, csd: ByteArray, rate: Int, channels: Int,
                     cancelled: () -> Boolean): List<Pair<Long, ShortArray>> {
    val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
      setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
      setInteger(MediaFormat.KEY_IS_ADTS, 0)
    }
    val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    val out = ArrayList<Pair<Long, ShortArray>>()
    try {
      codec.configure(fmt, null, null, 0)
      codec.start()
      val info = MediaCodec.BufferInfo()
      var next = 0
      var inputDone = false
      var outChannels = channels
      while (true) {
        if (cancelled()) throw InterruptedException("cancelled")
        if (!inputDone) {
          val idx = codec.dequeueInputBuffer(TIMEOUT_US)
          if (idx >= 0) {
            if (next < frames.size) {
              val f = frames[next++]
              val buf = codec.getInputBuffer(idx)!!.apply { clear(); put(f.data) }
              codec.queueInputBuffer(idx, 0, buf.position(), f.timeUs, 0)
            } else {
              codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            }
          }
        }
        val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          outChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else if (o >= 0) {
          if (info.size > 0) {
            val sb = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
              .order(ByteOrder.nativeOrder()).asShortBuffer()
            val all = ShortArray(sb.remaining()).also { sb.get(it) }
            // keep mono: average channels if the decoder gave more than one
            val pcm = if (outChannels <= 1) all else ShortArray(all.size / outChannels) { i ->
              var s = 0; for (c in 0 until outChannels) s += all[i * outChannels + c]; (s / outChannels).toShort()
            }
            out.add(info.presentationTimeUs to pcm)
          }
          codec.releaseOutputBuffer(o, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
      }
    } finally {
      runCatching { codec.stop() }; codec.release()
    }
    return out
  }

  private fun encode(stereo: ShortArray, cancelled: () -> Boolean): List<Mp4Join.AudioSample> {
    val rate = AudioResample.OUT_RATE
    val ch = AudioResample.OUT_CHANNELS
    val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
      setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    val out = ArrayList<Mp4Join.AudioSample>()
    try {
      codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      val info = MediaCodec.BufferInfo()
      val totalFrames = stereo.size / ch
      var pos = 0  // in sample frames
      var inputDone = false
      while (true) {
        if (cancelled()) throw InterruptedException("cancelled")
        if (!inputDone) {
          val idx = codec.dequeueInputBuffer(TIMEOUT_US)
          if (idx >= 0) {
            val buf = codec.getInputBuffer(idx)!!.apply { clear() }.order(ByteOrder.nativeOrder())
            val n = minOf(totalFrames - pos, buf.capacity() / (2 * ch), 4096)
            if (n > 0) {
              buf.asShortBuffer().put(stereo, pos * ch, n * ch)
              codec.queueInputBuffer(idx, 0, n * ch * 2, pos * 1_000_000L / rate, 0)
              pos += n
            } else {
              codec.queueInputBuffer(idx, 0, 0, pos * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            }
          }
        }
        val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (o >= 0) {
          if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            val b = codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
            val data = ByteArray(info.size).also { b.get(it) }
            out.add(Mp4Join.AudioSample(data, info.presentationTimeUs, true))
          }
          codec.releaseOutputBuffer(o, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
      }
    } finally {
      runCatching { codec.stop() }; codec.release()
    }
    // the muxer needs strictly increasing times
    val clean = ArrayList<Mp4Join.AudioSample>(out.size)
    for (s in out.sortedBy { it.timeUs }) if (clean.isEmpty() || s.timeUs > clean.last().timeUs) clean.add(s)
    return clean
  }
}
