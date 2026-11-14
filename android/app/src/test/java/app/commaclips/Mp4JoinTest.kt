package app.commaclips

import app.commaclips.core.Mp4Join
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Joins real comma minutes (COMMA_TEST_SEGMENTS = comma-separated fcamera.hevc paths, in order) and
 * writes the MP4 to COMMA_TEST_OUT so it can be checked frame by frame with ffmpeg.
 */
@androidx.media3.common.util.UnstableApi
class Mp4JoinTest {
  @Test fun joinsAcrossMinuteBoundary() {
    val segs = System.getenv("COMMA_TEST_SEGMENTS")?.split(",")?.map(::File)
    val out = System.getenv("COMMA_TEST_OUT")?.let(::File)
    assumeTrue("set COMMA_TEST_SEGMENTS and COMMA_TEST_OUT", segs != null && segs.all { it.exists() } && out != null)
    val r = Mp4Join.join(segs!!, fromFrame = 1105, toFrame = 1500, out = out!!)
    println("joined: first frame ${r.firstFrame}, frames ${r.frames}, ${r.width}x${r.height}, ${out.length() / 1_000_000} MB")
    assertEquals(1100, r.firstFrame)   // keyframe every 20 frames
    assertEquals(400, r.frames)
    assertEquals(1344 to 760, r.width to r.height)
  }
}
