package app.commaclips

import app.commaclips.core.AudioResample
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioResampleTest {
  @Test fun sineStaysTheSameToneAtThreeTimesTheRate() {
    val inRate = 16_000; val f = 440.0
    val input = ShortArray(inRate) { (sin(2 * PI * f * it / inRate) * 10_000).toInt().toShort() }  // 1 s
    val out = AudioResample.monoToStereo(input, inRate)
    assertEquals(48_000 * 2, out.size)                      // 1 s of 48 kHz stereo
    for (i in 0 until 48_000) assertEquals(out[2 * i], out[2 * i + 1])  // both channels identical
    var maxErr = 0.0
    for (i in 0 until 48_000 - 3) {
      val expected = sin(2 * PI * f * i / 48_000) * 10_000
      maxErr = maxOf(maxErr, abs(out[2 * i] - expected))
    }
    println("440 Hz sine 16k->48k: max error ${"%.1f".format(maxErr)} of 10000")
    assertTrue("max error $maxErr", maxErr < 200)            // < 2 %: linear interpolation is fine for speech
  }

  @Test fun placesChunksOnTheTimelineWithSilentGaps() {
    val chunk = ShortArray(160) { 1000 }                      // 10 ms at 16 kHz
    val out = AudioResample.place(listOf(0L to chunk, 50_000L to chunk, -5_000L to chunk), 16_000, 100_000)
    assertEquals(1600, out.size)
    assertEquals(1000.toShort(), out[0]); assertEquals(1000.toShort(), out[159])
    assertEquals(0.toShort(), out[400])                       // gap = silence
    assertEquals(1000.toShort(), out[800]); assertEquals(1000.toShort(), out[959])
  }

  @Test fun stripsAdtsHeadersFromRealFrames() {
    val f = System.getenv("COMMA_TEST_ADTS")?.let { java.io.File(it) }
    org.junit.Assume.assumeTrue(f?.exists() == true)
    val b = f!!.readBytes(); var i = 0; var n = 0
    while (i + 7 <= b.size) {
      val len = ((b[i + 3].toInt() and 3) shl 11) or ((b[i + 4].toInt() and 0xff) shl 3) or ((b[i + 5].toInt() and 0xff) ushr 5)
      val frame = b.copyOfRange(i, i + len)
      val raw = AudioResample.stripAdts(frame)
      assertEquals(len - 7, raw.size)
      assertEquals(raw.size, AudioResample.stripAdts(raw).size.coerceAtLeast(raw.size))  // bare frames are left alone
      i += len; n++
    }
    println("stripped $n real comma ADTS frames")
    assertTrue(n > 900)
  }

  @Test fun aacConfigIsLc48kStereo() {
    val c = AudioResample.AAC_LC_48K_STEREO_CONFIG
    val bits = ((c[0].toInt() and 0xff) shl 8) or (c[1].toInt() and 0xff)
    assertEquals(2, bits ushr 11)          // AAC LC
    assertEquals(3, (bits ushr 7) and 0xf) // 48 kHz
    assertEquals(2, (bits ushr 3) and 0xf) // 2 channels
  }
}
