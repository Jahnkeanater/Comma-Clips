package app.commaclips.core

import org.json.JSONArray
import org.json.JSONObject

/** Telemetry for one segment, as printed by remote_telemetry.py on the comma. */
class SegmentTelemetry(
  val seg: Int,
  val t: LongArray, val v: FloatArray, val a: FloatArray, val steer: FloatArray,
  val eng: BooleanArray, val leadOn: BooleanArray, val leadV: FloatArray, val leadD: FloatArray,
  val frames: Map<String, LongArray>, val wallOffset: Long?,
  // added in telemetry format 2 (NaN / 0 when missing)
  val ay: FloatArray = FloatArray(0), val blink: IntArray = IntArray(0), val ped: IntArray = IntArray(0),
  val lat: DoubleArray = DoubleArray(0), val lon: DoubleArray = DoubleArray(0),
  val alt: FloatArray = FloatArray(0), val hdg: FloatArray = FloatArray(0),
  val format: Int = 1,
) {
  companion object {
    fun parse(o: JSONObject): SegmentTelemetry {
      fun longs(a: JSONArray?) = LongArray(a?.length() ?: 0) { a!!.getLong(it) }
      fun floats(a: JSONArray?) = FloatArray(a?.length() ?: 0) { a!!.getDouble(it).toFloat() }
      val lead = o.optJSONArray("lead") ?: JSONArray()
      val fr = o.optJSONObject("frames") ?: JSONObject()
      val frames = buildMap { for (k in fr.keys()) put(k, longs(fr.optJSONArray(k))) }
      val eng = o.optJSONArray("eng")
      fun nfloats(a: JSONArray?) = FloatArray(a?.length() ?: 0) { if (a!!.isNull(it)) Float.NaN else a.getDouble(it).toFloat() }
      fun ndoubles(a: JSONArray?) = DoubleArray(a?.length() ?: 0) { if (a!!.isNull(it)) Double.NaN else a.getDouble(it) }
      fun ints(a: JSONArray?) = IntArray(a?.length() ?: 0) { a!!.optInt(it, 0) }
      return SegmentTelemetry(
        seg = o.getInt("seg"),
        t = longs(o.optJSONArray("t")), v = floats(o.optJSONArray("v")), a = floats(o.optJSONArray("a")),
        steer = floats(o.optJSONArray("steer")),
        eng = BooleanArray(eng?.length() ?: 0) { eng!!.getInt(it) != 0 },
        leadOn = BooleanArray(lead.length()) { lead.getJSONArray(it).getInt(0) != 0 },
        leadV = FloatArray(lead.length()) { lead.getJSONArray(it).getDouble(1).toFloat() },
        leadD = FloatArray(lead.length()) { lead.getJSONArray(it).getDouble(2).toFloat() },
        frames = frames,
        wallOffset = if (o.isNull("wall_offset")) null else o.optLong("wall_offset"),
        ay = nfloats(o.optJSONArray("ay")), blink = ints(o.optJSONArray("blink")), ped = ints(o.optJSONArray("ped")),
        lat = ndoubles(o.optJSONArray("lat")), lon = ndoubles(o.optJSONArray("lon")),
        alt = nfloats(o.optJSONArray("alt")), hdg = nfloats(o.optJSONArray("hdg")),
        format = o.optInt("v_fmt", 1),
      )
    }
  }
}

data class Sample(val speed: Float, val accel: Float, val steer: Float, val engaged: Boolean,
                  val leadOn: Boolean, val leadSpeed: Float, val leadDist: Float,
                  val latAccel: Float = 0f, val blink: Int = 0, val pedal: Int = 0,
                  val lat: Double? = null, val lon: Double? = null, val alt: Float? = null, val heading: Float? = null)

/** Overlay elements, their labels and whether they're on by default (GPS off: it shows where you were). */
enum class Element(val label: String, val default: Boolean) {
  SPEED("Speed", true), PEDALS("Gas/brake dot", true), ENGAGED("Engaged", true), CLOCK("Date & time", true),
  GPS("GPS position", false), HEADING("Heading", true), ALTITUDE("Altitude", true), BLINKERS("Turn signals", true),
  LEAD("Lead car", true), ACCEL("Acceleration", true), STEER("Steering angle", true), WHEEL("Steering wheel", true),
  GFORCE("G-force", true);
  companion object { val defaults get() = entries.filter { it.default }.toSet() }
}

private val COMPASS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
fun compass(deg: Float): String = COMPASS[(((deg % 360 + 360) % 360) / 22.5f + 0.5f).toInt() % 16]

/** Several segments merged, with per-frame timestamps for one camera. */
class Telemetry(segs: Map<Int, SegmentTelemetry>, order: List<Int>, camera: String) {
  private val t: LongArray
  private val idx: IntArray
  private val src: Array<SegmentTelemetry>
  private val frames: List<LongArray> = order.map { segs[it]?.frames?.get(camera) ?: LongArray(0) }
  val wallOffset: Long? = order.firstNotNullOfOrNull { segs[it]?.wallOffset }

  init {
    val ts = ArrayList<Long>(); val ix = ArrayList<Int>(); val ss = ArrayList<SegmentTelemetry>()
    for (s in order) {
      val d = segs[s] ?: continue
      for (i in d.t.indices) { ts.add(d.t[i]); ix.add(i); ss.add(d) }
    }
    t = ts.toLongArray(); idx = ix.toIntArray(); src = ss.toTypedArray()
  }

  val ok get() = t.isNotEmpty()

  /** Monotonic time (ns) of a frame counted from the start of the first segment in [order]. */
  fun frameMono(globalFrame: Int): Long? {
    var i = globalFrame
    for ((k, fr) in frames.withIndex()) {
      val n = if (fr.isNotEmpty()) fr.size else DevicePaths.FPS * 60
      if (i < n) {
        if (fr.isNotEmpty()) return fr[i]
        val base = segBase(k) ?: return null
        return base + i * 1_000_000_000L / DevicePaths.FPS
      }
      i -= n
    }
    return null
  }

  private fun segBase(k: Int): Long? {
    for (j in k - 1 downTo 0) if (frames[j].isNotEmpty()) return frames[j][0] + (k - j) * 60_000_000_000L
    for (j in k + 1 until frames.size) if (frames[j].isNotEmpty()) return frames[j][0] - (j - k) * 60_000_000_000L
    return null
  }

  fun sample(mono: Long): Sample? {
    if (t.isEmpty()) return null
    var lo = 0; var hi = t.size - 1
    while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (t[mid] <= mono) lo = mid else hi = mid - 1 }
    if (kotlin.math.abs(t[lo] - mono) > 1_500_000_000L) return null
    val d = src[lo]; val i = idx[lo]
    return Sample(d.v[i], d.a[i], d.steer[i], d.eng.getOrElse(i) { false },
      d.leadOn.getOrElse(i) { false }, d.leadV.getOrElse(i) { 0f }, d.leadD.getOrElse(i) { 0f },
      latAccel = d.ay.getOrElse(i) { 0f }.let { if (it.isNaN()) 0f else it },
      blink = d.blink.getOrElse(i) { 0 }, pedal = d.ped.getOrElse(i) { 0 },
      lat = d.lat.getOrElse(i) { Double.NaN }.takeIf { !it.isNaN() }, lon = d.lon.getOrElse(i) { Double.NaN }.takeIf { !it.isNaN() },
      alt = d.alt.getOrElse(i) { Float.NaN }.takeIf { !it.isNaN() }, heading = d.hdg.getOrElse(i) { Float.NaN }.takeIf { !it.isNaN() })
  }

  fun wallMillis(mono: Long): Long? = wallOffset?.let { (mono + it) / 1_000_000L }
}

enum class Units(val speedK: Float, val speedU: String, val distK: Float, val distU: String) {
  MPH(2.23694f, "mph", 3.28084f, "ft"), KMH(3.6f, "km/h", 1f, "m")
}

fun signed(x: Float, decimals: Int): String {
  val r = String.format(java.util.Locale.US, "%.${decimals}f", x)
  val zero = r.trimStart('-').all { it == '0' || it == '.' }
  return when { zero -> r.trimStart('-'); x > 0 -> "+$r"; else -> r }
}
