package app.commaclips

import app.commaclips.core.AudioResample
import app.commaclips.core.Mp4Join
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Joins real comma video with AAC-LC 48 kHz stereo frames (COMMA_TEST_AAC: an ADTS file) and writes COMMA_TEST_OUT_AUDIO. */
@androidx.media3.common.util.UnstableApi
class Mp4JoinAudioTest {
  @Test fun addsStandardAudioTrack() {
    val segs = System.getenv("COMMA_TEST_SEGMENTS")?.split(",")?.map(::File)
    val adts = System.getenv("COMMA_TEST_AAC")?.let(::File)
    val out = System.getenv("COMMA_TEST_OUT_AUDIO")?.let(::File)
    assumeTrue(segs != null && segs.all { it.exists() } && adts?.exists() == true && out != null)
    // split ADTS into raw AAC frames (strip the 7/9-byte headers), 1024 samples each
    val b = adts!!.readBytes(); var i = 0; var n = 0
    val frames = ArrayList<Mp4Join.AudioSample>()
    while (i + 7 <= b.size) {
      val prot = (b[i + 1].toInt() and 1) == 0
      val len = ((b[i + 3].toInt() and 3) shl 11) or ((b[i + 4].toInt() and 0xff) shl 3) or ((b[i + 5].toInt() and 0xff) ushr 5)
      val h = if (prot) 9 else 7
      frames.add(Mp4Join.AudioSample(b.copyOfRange(i + h, i + len), n * 1024L * 1_000_000 / 48_000, true))
      i += len; n++
    }
    val fmt = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(48_000).setChannelCount(2)
      .setInitializationData(listOf(AudioResample.AAC_LC_48K_STEREO_CONFIG)).build()
    val r = Mp4Join.join(segs!!, 1105, 1500, out!!, audio = { _, dur -> fmt to frames.filter { it.timeUs < dur } })
    println("audio frames ${frames.size}, hasAudio ${r.hasAudio}, ${out.length() / 1_000_000} MB")
    assertTrue(r.hasAudio)
  }
}
