package app.commaclips.core

/**
 * Minimal H.265 Annex-B parsing: the comma records raw HEVC elementary streams (one file per minute).
 * This splits a stream into access units (frames), finds keyframes and reads the picture size, so the
 * app can wrap the frames into an MP4 without re-encoding.
 */
object Hevc {
  const val VPS = 32
  const val SPS = 33
  const val PPS = 34
  const val AUD = 35
  const val PREFIX_SEI = 39

  class Nal(val start: Int, val end: Int, val type: Int) // start = first byte after the start code

  class AccessUnit(val start: Int, val end: Int, val key: Boolean) // byte range incl. start codes

  /** Positions of all NAL units in [buf]. */
  fun nals(buf: ByteArray, len: Int = buf.size): List<Nal> {
    val out = ArrayList<Nal>(4096)
    var i = 0
    var cur = -1
    while (i + 3 <= len) {
      if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
        if (cur >= 0) out.add(makeNal(buf, cur, trimZeros(buf, cur, i)))
        cur = i + 3
        i += 3
      } else i++
    }
    if (cur >= 0 && cur < len) out.add(makeNal(buf, cur, len))
    return out
  }

  private fun trimZeros(buf: ByteArray, from: Int, endExclusive: Int): Int {
    var e = endExclusive
    while (e > from && buf[e - 1].toInt() == 0) e-- // trailing zero of a 4-byte start code
    return e
  }

  private fun makeNal(buf: ByteArray, s: Int, e: Int) = Nal(s, e, if (e > s) (buf[s].toInt() shr 1) and 0x3f else -1)

  private fun isVcl(t: Int) = t in 0..31
  private fun isKey(t: Int) = t in 16..21

  /** Start-code position (00 00 01 or 00 00 00 01) before a NAL that begins at [nalStart]. */
  private fun scStart(buf: ByteArray, nalStart: Int): Int {
    var s = nalStart - 3
    if (s > 0 && buf[s - 1].toInt() == 0) s--
    return s
  }

  /** Splits a stream into frames. A frame starts at parameter sets / AUD / SEI or at a slice with first_slice_segment_in_pic_flag. */
  fun accessUnits(buf: ByteArray, len: Int = buf.size): List<AccessUnit> {
    val ns = nals(buf, len)
    val aus = ArrayList<AccessUnit>(1300)
    var auStart = -1
    var key = false
    var sawVcl = false
    for (n in ns) {
      val firstSlice = isVcl(n.type) && n.end > n.start + 2 && (buf[n.start + 2].toInt() and 0x80) != 0
      val prefixNal = n.type == VPS || n.type == SPS || n.type == PPS || n.type == AUD || n.type == PREFIX_SEI
      val startsNew = (sawVcl && (prefixNal || firstSlice)) || auStart < 0
      if (startsNew) {
        if (auStart >= 0) aus.add(AccessUnit(auStart, scStart(buf, n.start), key))
        auStart = scStart(buf, n.start); key = false; sawVcl = false
      }
      if (isVcl(n.type)) { sawVcl = true; if (isKey(n.type)) key = true }
    }
    if (auStart >= 0) aus.add(AccessUnit(auStart, len, key))
    return aus
  }

  /** VPS + SPS + PPS with start codes, as Android's MediaMuxer wants in "csd-0". */
  fun codecConfig(buf: ByteArray, len: Int = buf.size): ByteArray? {
    val ns = nals(buf, minOf(len, 1 shl 16))
    val parts = listOf(VPS, SPS, PPS).map { t -> ns.firstOrNull { it.type == t } ?: return null }
    val out = java.io.ByteArrayOutputStream()
    for (p in parts) { out.write(byteArrayOf(0, 0, 0, 1)); out.write(buf, p.start, p.end - p.start) }
    return out.toByteArray()
  }

  /** Displayed picture size from the SPS. */
  fun pictureSize(buf: ByteArray, len: Int = buf.size): Pair<Int, Int>? {
    val sps = nals(buf, minOf(len, 1 shl 16)).firstOrNull { it.type == SPS } ?: return null
    val r = BitReader(unescape(buf, sps.start + 2, sps.end))
    r.skip(4) // sps_video_parameter_set_id
    val maxSubLayersMinus1 = r.bits(3)
    r.skip(1)
    // profile_tier_level
    r.skip(88); r.skip(8)
    val subProfile = BooleanArray(maxSubLayersMinus1); val subLevel = BooleanArray(maxSubLayersMinus1)
    for (i in 0 until maxSubLayersMinus1) { subProfile[i] = r.bits(1) == 1; subLevel[i] = r.bits(1) == 1 }
    if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
    for (i in 0 until maxSubLayersMinus1) { if (subProfile[i]) r.skip(88); if (subLevel[i]) r.skip(8) }
    r.ue() // sps_seq_parameter_set_id
    val chroma = r.ue()
    if (chroma == 3) r.skip(1)
    var w = r.ue(); var h = r.ue()
    if (r.bits(1) == 1) {
      val sw = if (chroma == 1 || chroma == 2) 2 else 1
      val sh = if (chroma == 1) 2 else 1
      val l = r.ue(); val rt = r.ue(); val t = r.ue(); val b = r.ue()
      w -= sw * (l + rt); h -= sh * (t + b)
    }
    return w to h
  }

  private fun unescape(buf: ByteArray, s: Int, e: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(e - s)
    var zeros = 0
    for (i in s until e) {
      val b = buf[i].toInt() and 0xff
      if (zeros >= 2 && b == 3) { zeros = 0; continue }
      out.write(b)
      zeros = if (b == 0) zeros + 1 else 0
    }
    return out.toByteArray()
  }

  private class BitReader(val d: ByteArray) {
    var pos = 0
    fun bits(n: Int): Int {
      var v = 0
      repeat(n) {
        val byte = d.getOrElse(pos ushr 3) { 0 }.toInt()
        v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
        pos++
      }
      return v
    }
    fun skip(n: Int) { pos += n }
    fun ue(): Int {
      var zeros = 0
      while (bits(1) == 0 && zeros < 32) zeros++
      return ((1 shl zeros) - 1) + bits(zeros)
    }
  }
}
