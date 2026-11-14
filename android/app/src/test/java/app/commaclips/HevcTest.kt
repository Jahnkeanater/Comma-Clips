package app.commaclips

import app.commaclips.core.Hevc
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Uses a real comma segment if COMMA_TEST_HEVC points at one (e.g. a cached fcamera.hevc). */
class HevcTest {
  private val file = System.getenv("COMMA_TEST_HEVC")?.let(::File)

  @Test fun parsesRealSegment() {
    assumeTrue("set COMMA_TEST_HEVC to run", file?.exists() == true)
    val buf = file!!.readBytes()
    val size = Hevc.pictureSize(buf)
    println("picture size $size")
    assertNotNull(size)
    val aus = Hevc.accessUnits(buf)
    val keys = aus.withIndex().filter { it.value.key }.map { it.index }
    println("frames ${aus.size}, keyframes ${keys.size} at ${keys.take(5)}")
    assertTrue("about 1200 frames per minute", aus.size in 1150..1250)
    assertTrue(aus.first().key)
    assertEquals(0, aus.first().start)
    assertEquals(buf.size, aus.last().end)
    for (i in 1 until aus.size) assertEquals(aus[i - 1].end, aus[i].start)
    val csd = Hevc.codecConfig(buf)
    assertNotNull(csd)
  }

  @Test fun syntheticStream() {
    // VPS, SPS(minimal), PPS, IDR slice (first_slice flag), TRAIL slice
    fun nal(type: Int, vararg payload: Int) = byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1) + payload.map { it.toByte() }.toByteArray()
    val s = nal(32, 0x0c) + nal(33, 0x01) + nal(34, 0xc1) + nal(19, 0x80, 0x11) + nal(1, 0x80, 0x22) + nal(1, 0x80, 0x33)
    val aus = Hevc.accessUnits(s)
    assertEquals(3, aus.size)
    assertTrue(aus[0].key); assertFalse(aus[1].key)
  }
}
