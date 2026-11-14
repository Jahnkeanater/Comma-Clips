package app.commaclips.export

import android.graphics.*
import app.commaclips.core.Element
import app.commaclips.core.Sample
import app.commaclips.core.Units
import app.commaclips.core.compass
import app.commaclips.core.signed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * Draws the telemetry overlay (same layout as the desktop app). Each element can be switched off:
 * top-left speed (+ gas/brake dot), engaged, clock, GPS, heading/altitude; top-centre turn signals;
 * top-right lead / accel / steer with the g-force dot and comma's steering wheel underneath.
 */
class OverlayRenderer(val width: Int, val height: Int, private val wheel: Bitmap, private val units: Units,
                      private val el: Set<Element> = Element.defaults) {
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
  private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
  private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val clockFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
  private val green = Color.rgb(0x7a, 0xe0, 0x66)
  private val red = Color.rgb(0xff, 0x5a, 0x5a)
  private val amber = Color.rgb(0xf0, 0xb4, 0x4a)
  private val grey = Color.rgb(0xb0, 0xb0, 0xb0)
  private val light = Color.rgb(0xd0, 0xd0, 0xd0)

  private fun on(e: Element) = e in el
  private fun width(s: String, size: Float): Float { text.textSize = size; return text.measureText(s) }
  private fun f0(x: Float) = String.format(Locale.US, "%.0f", x)

  /** [frame] is the output frame number (used to flash the turn signals). */
  fun draw(c: Canvas, s: Sample?, wallMillis: Long?, frame: Int = 0) {
    c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    // ---------------- left box
    data class Line(val h: Float, val w: Float, val draw: (Float, Float) -> Unit) // draw(x, top)
    val lines = ArrayList<Line>()
    if (on(Element.SPEED)) {
      val sp = s?.let { f0(it.speed * units.speedK) } ?: "--"
      val unit = " " + units.speedU
      val dotSpace = if (on(Element.PEDALS)) fs * 1.3f else 0f
      lines += Line(big * 1.08f, width(sp, big) + width(unit, fs) + dotSpace) { x, top ->
        val base = top + big * 0.9f
        text.color = Color.WHITE; text.textSize = big; c.drawText(sp, x, base, text)
        text.textSize = fs; c.drawText(unit, x + width(sp, big), base, text)
        if (on(Element.PEDALS)) pedalDot(c, s, x + width(sp, big) + width(unit, fs) + fs * 0.75f, top + big * 0.62f)
      }
    } else if (on(Element.PEDALS)) {
      val label = when (s?.pedal) { 2 -> "brake"; 1 -> "gas"; else -> "" }
      lines += Line(fs * 1.25f, fs * 1.3f + width(label, fs)) { x, top ->
        pedalDot(c, s, x + fs * 0.45f, top + fs * 0.6f)
        text.color = Color.WHITE; text.textSize = fs; c.drawText(label, x + fs * 1.2f, top + fs * 0.95f, text)
      }
    }
    if (on(Element.ENGAGED)) {
      val t = when { s == null -> "no telemetry"; s.engaged -> "● engaged"; else -> "○ not engaged" }
      lines += Line(fs * 1.25f, width(t, fs)) { x, top ->
        text.color = if (s?.engaged == true) green else grey; text.textSize = fs; c.drawText(t, x, top + fs * 0.95f, text)
      }
    }
    fun smallLine(t: String) = Line(small * 1.25f, width(t, small)) { x, top ->
      text.color = light; text.textSize = small; c.drawText(t, x, top + small * 0.95f, text)
    }
    if (on(Element.CLOCK)) lines += smallLine(wallMillis?.let { clockFmt.format(Date(it)) } ?: "")
    if (on(Element.GPS)) lines += smallLine(if (s?.lat != null && s.lon != null) String.format(Locale.US, "%.6f, %.6f", s.lat, s.lon) else "no GPS fix")
    if (on(Element.HEADING) || on(Element.ALTITUDE)) {
      val parts = ArrayList<String>()
      if (on(Element.HEADING)) parts += s?.heading?.let { "${compass(it)} ${f0(it)}°" } ?: "--"
      if (on(Element.ALTITUDE)) parts += s?.alt?.let { "${f0(it * units.distK)} ${units.distU}" } ?: "--"
      lines += smallLine(parts.joinToString(" · "))
    }
    if (lines.isNotEmpty()) {
      val lw = lines.maxOf { it.w } + 2 * ip
      val lh = lines.sumOf { it.h.toDouble() }.toFloat() + 2 * ip
      c.drawRect(pad, pad, pad + lw, pad + lh, boxPaint)
      var y = pad + ip
      for (l in lines) { l.draw(pad + ip, y); y += l.h }
    }
    if (s == null) return

    // ---------------- turn signals (flash ~1.5 times a second while on)
    if (on(Element.BLINKERS)) {
      val aw = fs * 1.4f; val ah = fs * 1.6f; val gap = fs * 1.2f; val top = pad + ip
      val lit = (frame / 7) % 2 == 0
      for ((left, bit) in listOf(true to 1, false to 2)) {
        val x = if (left) width / 2f - gap / 2 - aw else width / 2f + gap / 2
        val p = Path().apply {
          if (left) { moveTo(x + aw, top); lineTo(x, top + ah / 2); lineTo(x + aw, top + ah) }
          else { moveTo(x, top); lineTo(x + aw, top + ah / 2); lineTo(x, top + ah) }
          close()
        }
        shape.style = Paint.Style.FILL
        shape.color = if ((s.blink and bit) != 0 && lit) green else Color.argb(80, 0, 0, 0)
        c.drawPath(p, shape)
      }
    }

    // ---------------- right box: lead, accel, steer
    val right = ArrayList<Pair<String, Int>>() // text, colour of the value part (accel only)
    if (on(Element.LEAD)) right += (if (s.leadOn) "LEAD ${f0(s.leadSpeed * units.speedK)} ${units.speedU} · ${f0(s.leadDist * units.distK)} ${units.distU}" else "LEAD —") to Color.WHITE
    if (on(Element.ACCEL)) right += "ACCEL ${signed(s.accel, 1)} m/s²" to when { s.accel > 0.3f -> green; s.accel < -0.3f -> red; else -> Color.WHITE }
    if (on(Element.STEER)) right += "STEER ${signed(s.steer, 0)}°" to Color.WHITE
    var rh = 0f
    if (right.isNotEmpty()) {
      val rw = maxOf(right.maxOf { width(it.first, fs) }, width("LEAD 00 mph · 000 ft", fs)) + 2 * ip
      rh = fs * 1.25f * right.size + 2 * ip - fs * 0.25f
      c.drawRect(width - pad - rw, pad, width - pad, pad + rh, boxPaint)
      val rx = width - pad - ip
      var ry = pad + ip + fs * 0.85f
      text.textSize = fs
      for ((t, col) in right) {
        if (t.startsWith("ACCEL ")) {
          val label = "ACCEL "; val value = t.removePrefix(label); val ax = rx - width(t, fs)
          text.color = Color.WHITE; c.drawText(label, ax, ry, text)
          text.color = col; c.drawText(value, ax + width(label, fs), ry, text)
        } else { text.color = Color.WHITE; c.drawText(t, rx - width(t, fs), ry, text) }
        ry += fs * 1.25f
      }
    }

    // ---------------- gauges under the right box: g-force (left) and steering wheel (right)
    val cy = pad + (if (right.isNotEmpty()) rh + pad * 0.5f else 0f) + radius + ip
    val wheelCx = width - pad - radius - ip
    val gCx = if (on(Element.WHEEL)) wheelCx - 2 * (radius + ip) - pad * 0.5f else wheelCx
    if (on(Element.GFORCE)) {
      c.drawCircle(gCx, cy, radius + ip, boxPaint)
      shape.style = Paint.Style.STROKE; shape.strokeWidth = 1.5f; shape.color = Color.argb(110, 255, 255, 255)
      c.drawLine(gCx - radius, cy, gCx + radius, cy, shape)
      c.drawLine(gCx, cy - radius, gCx, cy + radius, shape)
      c.drawCircle(gCx, cy, radius / 2, shape)
      // the dot shows the force you feel: turning left pushes it right, braking pushes it up; edge = 0.6 g
      val latG = s.latAccel / 9.81f; val lonG = s.accel / 9.81f
      val scale = radius / 0.6f
      val dx = (latG * scale).coerceIn(-radius, radius); val dy = (lonG * scale).coerceIn(-radius, radius)
      val mag = hypot(latG, lonG)
      shape.style = Paint.Style.FILL; shape.color = when { mag < 0.3f -> green; mag < 0.5f -> amber; else -> red }
      c.drawCircle(gCx + dx, cy + dy, maxOf(3f, radius * 0.16f), shape)
      val label = String.format(Locale.US, "%.2f g", mag)
      text.color = Color.WHITE; text.textSize = small
      c.drawText(label, gCx - width(label, small) / 2, cy + radius + ip + small * 1.1f, text)
    }
    if (on(Element.WHEEL)) {
      c.drawCircle(wheelCx, cy, radius + ip, boxPaint)
      c.save()
      c.rotate(-s.steer, wheelCx, cy) // steering left (+) turns the wheel anticlockwise
      c.drawBitmap(wheel, null, RectF(wheelCx - radius, cy - radius, wheelCx + radius, cy + radius), wheelPaint)
      c.restore()
    }
  }

  /** Green while the gas is pressed, red while braking, a faint ring otherwise. */
  private fun pedalDot(c: Canvas, s: Sample?, x: Float, y: Float) {
    val r = fs * 0.38f
    when (s?.pedal) {
      2 -> { shape.style = Paint.Style.FILL; shape.color = red; c.drawCircle(x, y, r, shape) }
      1 -> { shape.style = Paint.Style.FILL; shape.color = green; c.drawCircle(x, y, r, shape) }
      else -> { shape.style = Paint.Style.STROKE; shape.strokeWidth = 1.5f; shape.color = Color.argb(128, 255, 255, 255); c.drawCircle(x, y, r, shape) }
    }
  }
}
