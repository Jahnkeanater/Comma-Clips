package app.commaclips.core

/**
 * The comma records 16 kHz mono, which some players (Google Photos) won't play. Exports use the standard
 * 48 kHz stereo instead; this does the sample-rate and channel conversion on raw 16-bit PCM.
 */
object AudioResample {
  const val OUT_RATE = 48_000
  const val OUT_CHANNELS = 2

  /** AudioSpecificConfig for AAC-LC, 48 kHz, stereo (object type 2, frequency index 3, channels 2). */
  val AAC_LC_48K_STEREO_CONFIG = byteArrayOf(0x11, 0x90.toByte())

  /** Linear-interpolation resample of mono PCM to [outRate], duplicated to interleaved stereo. */
  fun monoToStereo(input: ShortArray, inRate: Int, outRate: Int = OUT_RATE): ShortArray {
    if (input.isEmpty()) return ShortArray(0)
    val outFrames = ((input.size.toLong() * outRate + inRate - 1) / inRate).toInt()
    val out = ShortArray(outFrames * 2)
    val step = inRate.toDouble() / outRate
    for (i in 0 until outFrames) {
      val pos = i * step
      val k = pos.toInt()
      val frac = pos - k
      val a = input[minOf(k, input.size - 1)].toInt()
      val b = input[minOf(k + 1, input.size - 1)].toInt()
      val v = (a + (b - a) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
      out[2 * i] = v
      out[2 * i + 1] = v
    }
    return out
  }

  /**
   * Places decoded PCM chunks (each with its start time on the clip timeline) into one continuous mono
   * buffer of [durationUs] at [rate]; gaps stay silent and anything outside the clip is dropped.
   */
  fun place(chunks: List<Pair<Long, ShortArray>>, rate: Int, durationUs: Long): ShortArray {
    val total = (durationUs * rate / 1_000_000L).toInt().coerceAtLeast(0)
    val out = ShortArray(total)
    for ((timeUs, pcm) in chunks) {
      var dst = Math.round(timeUs * rate / 1_000_000.0).toInt()
      var src = 0
      if (dst < 0) { src = -dst; dst = 0 }
      val n = minOf(pcm.size - src, total - dst)
      if (n > 0) System.arraycopy(pcm, src, out, dst, n)
    }
    return out
  }
}
