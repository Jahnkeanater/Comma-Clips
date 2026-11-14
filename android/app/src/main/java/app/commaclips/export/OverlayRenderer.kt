package app.commaclips.export

import android.graphics.*
import app.commaclips.core.Sample
import app.commaclips.core.Units
import app.commaclips.core.signed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws the telemetry overlay (same layout as the desktop app): speed / engaged / clock top-left,
 * lead / accel / steer top-right, comma's steering wheel icon turning underneath.
 */
class OverlayRenderer(val width: Int, val height: Int, private val wheel: Bitmap, private val units: Units) {
  private val fs = height * 0.034f
  private val big = height * 0.085f
  private val small = fs * 0.8f
  private val pad = height * 0.025f
  private val ip = fs * 0.45f
  private val radius = height * 0.06f
  private val boxPaint = Paint().apply { color = Color.argb(97, 0, 0, 0) }
  private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    color = Color.WHITE
    setShadowLayer(fs * 0.12f, 0f, fs * 0.05f, Color.argb(200, 0, 0, 0))
  }
  private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val clockFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
  private val green = Color.rgb(0x7a, 0xe0, 0x66)
  private val red = Color.rgb(0xff, 0x5a, 0x5a)
  private val grey = Color.rgb(0xb0, 0xb0, 0xb0)

  private fun width(s: String, size: Float): Float { text.textSize = size; return text.measureText(s) }

  fun draw(c: Canvas, s: Sample?, wallMillis: Long?) {
    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    val clock = wallMillis?.let { clockFmt.format(Date(it)) } ?: ""

    // ---- left box: speed, engaged, clock
    val speed = s?.let { String.format(Locale.US, "%.0f", it.speed * units.speedK) } ?: "--"
    val eng = when { s == null -> "no telemetry"; s.engaged -> "● engaged"; else -> "○ not engaged" }
    val line1W = width(speed, big) + width(" " + units.speedU, fs)
    val lw = maxOf(line1W, width(eng, fs), width(clock, small)) + 2 * ip
    val lh = big * 1.05f + fs * 1.25f + small * 1.25f + 2 * ip
    c.drawRect(pad, pad, pad + lw, pad + lh, boxPaint)
    var y = pad + ip + big * 0.9f
    text.textSize = big; text.color = Color.WHITE
    c.drawText(speed, pad + ip, y, text)
    text.textSize = fs
    c.drawText(" " + units.speedU, pad + ip + width(speed, big), y, text.apply { textSize = fs })
    y += fs * 1.3f
    text.color = when { s == null -> grey; s.engaged -> green; else -> grey }
    c.drawText(eng, pad + ip, y, text)
    y += small * 1.3f
    text.textSize = small; text.color = Color.rgb(0xd0, 0xd0, 0xd0)
    c.drawText(clock, pad + ip, y, text)

    if (s == null) return
    // ---- right box: lead, accel, steer (right aligned)
    val lead = if (s.leadOn) "LEAD ${String.format(Locale.US, "%.0f", s.leadSpeed * units.speedK)} ${units.speedU} · " +
      "${String.format(Locale.US, "%.0f", s.leadDist * units.distK)} ${units.distU}" else "LEAD —"
    val accel = "ACCEL ${signed(s.accel, 1)} m/s²"
    val steer = "STEER ${signed(s.steer, 0)}°"
    val rw = maxOf(width(lead, fs), width(accel, fs), width(steer, fs), width("LEAD 00 mph · 000 ft", fs)) + 2 * ip
    val rh = fs * 1.25f * 3 + 2 * ip - fs * 0.25f
    val rx = width - pad - rw
    c.drawRect(rx, pad, width - pad, pad + rh, boxPaint)
    text.textSize = fs
    val right = width - pad - ip
    var ry = pad + ip + fs * 0.85f
    text.color = Color.WHITE
    c.drawText(lead, right - width(lead, fs), ry, text)
    ry += fs * 1.25f
    val aCol = when { s.accel > 0.3f -> green; s.accel < -0.3f -> red; else -> Color.WHITE }
    val label = "ACCEL "
    val value = "${signed(s.accel, 1)} m/s²"
    val ax = right - width(label + value, fs)
    c.drawText(label, ax, ry, text)
    text.color = aCol
    c.drawText(value, ax + width(label, fs), ry, text)
    ry += fs * 1.25f
    text.color = Color.WHITE
    c.drawText(steer, right - width(steer, fs), ry, text)

    // ---- wheel under the right box
    val cx = width - pad - radius - ip
    val cy = pad + rh + pad * 0.5f + radius + ip
    c.drawCircle(cx, cy, radius + ip, boxPaint)
    c.save()
    c.rotate(-s.steer, cx, cy) // steering left (+) turns the wheel anticlockwise
    c.drawBitmap(wheel, null, RectF(cx - radius, cy - radius, cx + radius, cy + radius), wheelPaint)
    c.restore()
  }
}
