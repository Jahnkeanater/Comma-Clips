package app.commaclips.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.commaclips.core.*
import app.commaclips.data.Repo
import app.commaclips.export.ExportParams
import app.commaclips.export.ExportService
import app.commaclips.export.Jobs
import app.commaclips.player.SftpDataSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@UnstableApi
@Composable
fun DriveScreen(repo: Repo, routeName: String, showExports: () -> Unit) {
  val ctx = LocalContext.current
  val routes by repo.routes.collectAsState()
  val route = routes?.firstOrNull { it.name == routeName } ?: run { Hint("Drive not found", Modifier.padding(16.dp)); return }
  val previews = remember(route) { route.previewSegments }
  val prefs = repo.prefs

  val player = remember {
    ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(SftpDataSource.Factory(repo))).build().apply {
      setMediaItems(previews.map { MediaItem.fromUri(SftpDataSource.uri(route.name, it, "qcamera.ts")) })
      // the preview files carry the cabin audio the comma records; pause other apps' audio while playing
      setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
        .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
      volume = if (prefs.muted) 0f else 1f
      prepare()
    }
  }
  DisposableEffect(Unit) { onDispose { player.release() } }

  fun playerToRoute(): Double {
    val i = player.currentMediaItemIndex.coerceIn(0, (previews.size - 1).coerceAtLeast(0))
    return (previews.getOrNull(i) ?: route.firstSeg) * DevicePaths.SEG_SECONDS + player.currentPosition / 1000.0
  }
  fun seekRoute(t: Double) {
    val n = (t / DevicePaths.SEG_SECONDS).toInt()
    var i = previews.indexOf(n)
    var off = t - n * DevicePaths.SEG_SECONDS
    if (i < 0) { i = previews.indexOfFirst { it > n }.let { if (it < 0) previews.size - 1 else it }; off = 0.0 }
    if (i >= 0) player.seekTo(i, (off * 1000).toLong())
  }

  var now by remember { mutableDoubleStateOf(route.minTime) }
  LaunchedEffect(Unit) { while (true) { now = playerToRoute(); delay(200) } }

  var start by remember { mutableDoubleStateOf(route.minTime) }
  var end by remember { mutableDoubleStateOf(minOf(route.minTime + 30, route.maxTime)) }
  fun clampSel() {
    start = start.coerceIn(route.minTime, route.maxTime - 1)
    end = end.coerceIn(start + 1, route.maxTime)
  }

  var camera by remember { mutableStateOf(if (prefs.camera in route.cameras) prefs.camera else route.cameras.firstOrNull() ?: Camera.ROAD) }
  var osd by remember { mutableStateOf(prefs.osd) }
  var units by remember { mutableStateOf(prefs.units) }
  var fast by remember { mutableStateOf(prefs.fastMode) }
  var quality by remember { mutableIntStateOf(prefs.quality) }
  var hud by remember { mutableStateOf(prefs.hud) }
  var muted by remember { mutableStateOf(prefs.muted) }
  var withAudio by remember { mutableStateOf(prefs.exportAudio) }
  var elements by remember { mutableStateOf(prefs.elements) }
  var message by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  // telemetry for the preview HUD, loaded per minute
  val tel = remember { mutableStateMapOf<Int, SegmentTelemetry?>() }
  val telErr = remember { mutableStateOf<String?>(null) }
  val curSeg = (now / DevicePaths.SEG_SECONDS).toInt()
  LaunchedEffect(hud, curSeg) {
    if (!hud) return@LaunchedEffect
    for (s in listOf(curSeg, curSeg + 1)) if (s <= route.lastSeg && !tel.containsKey(s)) {
      tel[s] = null
      runCatching { repo.telemetry(route.name, listOf(s))[s] }.onSuccess { tel[s] = it }.onFailure { telErr.value = it.message; tel.remove(s) }
    }
  }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Box(Modifier.fillMaxWidth().aspectRatio(526f / 330f).background(Color.Black)) {
      AndroidView(factory = { PlayerView(it).apply {
        this.player = player
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
      } }, modifier = Modifier.fillMaxSize())
      if (hud) PreviewHud(tel[curSeg], now, units, telErr.value, elements)
    }

    Column(Modifier.padding(16.dp)) {
      // sound toggle sits under the video so it doesn't cover the picture or the readouts
      Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Hint("Preview sound", Modifier.weight(1f))
        FilterChip(selected = !muted, onClick = { muted = !muted; prefs.muted = muted; player.volume = if (muted) 0f else 1f },
          label = { Text(if (muted) "🔇 Muted" else "🔊 Sound on") })
      }
      Timeline(route, camera, now, start, end,
        onSeek = { seekRoute(it); now = it },
        onStart = { start = it; if (start >= end) end = start + 1; clampSel() },
        onEnd = { end = it; if (end <= start) start = end - 1; clampSel() })
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Hint(formatTime(route.startTime + route.minTime))
        Hint(formatDur(now - route.minTime) + " · " + formatTimeS(route.startTime + now))
        Hint(formatTime(route.startTime + route.maxTime))
      }

      Spacer(Modifier.height(12.dp))
      SectionCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          RangeColumn("Start", start - route.minTime, route.startTime + start, Modifier.weight(1f),
            nudge = { start += it; clampSel(); seekRoute(start) }, setHere = { start = now; if (end <= start) end = start + 30; clampSel() })
          RangeColumn("End", end - route.minTime, route.startTime + end, Modifier.weight(1f),
            nudge = { end += it; clampSel(); seekRoute(end) }, setHere = { end = now; if (end <= start) start = end - 30; clampSel() })
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          AssistChip(onClick = { start = now - 30; end = now; clampSel() }, label = { Text("30 s before") })
          AssistChip(onClick = { start = now - 15; end = now + 15; clampSel() }, label = { Text("±15 s") })
          AssistChip(onClick = { start = route.minTime; end = route.maxTime; clampSel() }, label = { Text("Whole drive") })
        }
        Text("Clip length ${formatDur(end - start)}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
      }

      SectionCard("Export") {
        Hint("Camera")
        Segmented(Camera.entries.map { it to it.label }, camera, enabled = { it in route.cameras }) { camera = it; prefs.camera = it }
        SwitchRow("Telemetry overlay", "Speed, lead car, acceleration and steering wheel on the video", osd) {
          osd = it; prefs.osd = it; if (it) { fast = false; prefs.fastMode = false }
        }
        SwitchRow("Include audio", "The cabin microphone audio the comma records (turn off for privacy when sharing)", withAudio) {
          withAudio = it; prefs.exportAudio = it
        }
        if (osd || hud) {
          Hint("Show on the video", Modifier.padding(top = 4.dp))
          ElementChecklist(elements) { elements = it; prefs.elements = it }
          Hint("GPS position is off by default: it shows exactly where you were.")
          Hint("Units", Modifier.padding(top = 4.dp))
          Segmented(listOf(Units.MPH to "mph", Units.KMH to "km/h"), units) { units = it; prefs.units = it }
        }
        Hint("Mode", Modifier.padding(top = 8.dp))
        Segmented(listOf(false to "Exact cut", true to "Fast copy"), fast, enabled = { !(it && osd) }) { fast = it; prefs.fastMode = it }
        Hint(when {
          osd -> "The overlay needs Exact cut (re-encoded to H.264 on the phone)."
          fast -> "Original HEVC, no quality loss, very quick. Starts at the nearest keyframe."
          else -> "Cut to the exact second and re-encoded to H.264 so it plays everywhere."
        })
        if (!fast) {
          Hint("Quality", Modifier.padding(top = 8.dp))
          Segmented(listOf(0 to "High", 1 to "Medium", 2 to "Small"), quality) { quality = it; prefs.quality = it }
        }
        SwitchRow("Show telemetry on preview", "Reads the drive logs from the comma (only while parked)", hud) { hud = it; prefs.hud = it }
        Button(onClick = {
          Jobs.add(ExportParams(route.name, start, end, camera, osd, units, fast && !osd, quality, withAudio, elements))
          ExportService.start(ctx)
          message = "Export started"
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Export full-resolution clip") }
        if (message != null) TextButton(onClick = showExports, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("$message · see progress") }
      }
    }
  }
}

@Composable
private fun RangeColumn(label: String, rel: Double, wall: Double, modifier: Modifier, nudge: (Double) -> Unit, setHere: () -> Unit) {
  Column(modifier) {
    Hint(label)
    Text(formatDur(rel), fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Hint("≈ " + formatTimeS(wall))
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      for (d in listOf(-5.0, -1.0, 1.0, 5.0)) {
        FilledTonalButton(onClick = { nudge(d) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).height(36.dp)) {
          Text((if (d > 0) "+" else "−") + "${abs(d).toInt()}s", fontSize = 12.sp)
        }
      }
    }
    OutlinedButton(onClick = setHere, modifier = Modifier.fillMaxWidth()) { Text("Set ${label.lowercase()} here", maxLines = 1) }
  }
}

@Composable
private fun Timeline(route: Route, camera: Camera, now: Double, start: Double, end: Double,
                     onSeek: (Double) -> Unit, onStart: (Double) -> Unit, onEnd: (Double) -> Unit) {
  val sel = MaterialTheme.colorScheme.primary
  val bg = MaterialTheme.colorScheme.surfaceVariant
  val head = MaterialTheme.colorScheme.onSurface
  val span = route.maxTime - route.minTime
  val missing = remember(route, camera) { (route.firstSeg..route.lastSeg).filter { route.segment(it)?.files?.containsKey(camera.file) != true } }
  var widthPx by remember { mutableFloatStateOf(1f) }
  fun toTime(x: Float) = route.minTime + (x / widthPx).coerceIn(0f, 1f) * span
  fun toX(t: Double) = ((t - route.minTime) / span * widthPx).toFloat()
  val curStart by rememberUpdatedState(start); val curEnd by rememberUpdatedState(end)
  Canvas(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp))
    .pointerInput(route) { detectTapGestures { onSeek(toTime(it.x)) } }
    .pointerInput(route) {
      var mode = 0
      detectDragGestures(onDragStart = { o ->
        mode = when { abs(o.x - toX(curStart)) < 48f -> 1; abs(o.x - toX(curEnd)) < 48f -> 2; else -> 0 }
        if (mode == 0) onSeek(toTime(o.x))
      }) { change, _ ->
        val t = toTime(change.position.x)
        when (mode) { 1 -> onStart(t); 2 -> onEnd(t); else -> onSeek(t) }
      }
    }) {
    widthPx = size.width
    drawRect(bg)
    for (n in missing) {
      val x0 = toX(n * DevicePaths.SEG_SECONDS); val w = (DevicePaths.SEG_SECONDS / span * size.width).toFloat()
      drawRect(Color(0x55E0605A), Offset(x0, 0f), Size(w, size.height))
    }
    val sx = toX(start); val ex = toX(end)
    drawRect(sel.copy(alpha = 0.3f), Offset(sx, 0f), Size((ex - sx).coerceAtLeast(2f), size.height))
    drawRect(sel, Offset(sx - 3f, 0f), Size(6f, size.height))
    drawRect(sel, Offset(ex - 3f, 0f), Size(6f, size.height))
    drawRect(head, Offset(toX(now) - 1.5f, 0f), Size(3f, size.height))
  }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ElementChecklist(selected: Set<Element>, onChange: (Set<Element>) -> Unit) {
  FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    for (e in Element.entries) {
      FilterChip(selected = e in selected, onClick = { onChange(if (e in selected) selected - e else selected + e) },
        label = { Text(e.label, fontSize = 13.sp) })
    }
  }
}

@Composable
private fun BoxScope.PreviewHud(seg: SegmentTelemetry?, now: Double, units: Units, err: String?, el: Set<Element>) {
  val style = androidx.compose.ui.text.TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
  val box = Modifier.padding(8.dp).background(Color(0x61000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
  if (seg == null) {
    Text(err ?: "loading telemetry…", style = style, modifier = box.align(Alignment.TopStart))
    PreviewGauges(el, Modifier.align(Alignment.TopEnd).padding(8.dp))
    return
  }
  val n = (now / DevicePaths.SEG_SECONDS).toInt()
  val t = Telemetry(mapOf(seg.seg to seg), listOf(seg.seg), "road")
  val frame = ((now - n * DevicePaths.SEG_SECONDS) * DevicePaths.FPS).toInt()
  val s = t.frameMono(frame)?.let { t.sample(it) } ?: return
  val green = Color(0xFF7AE066)
  Column(box.align(Alignment.TopStart)) {
    if (Element.SPEED in el) Row(verticalAlignment = Alignment.CenterVertically) {
      Text("${"%.0f".format(s.speed * units.speedK)} ${units.speedU}", style = style.copy(fontSize = 22.sp))
      if (Element.PEDALS in el) {
        val c = when (s.pedal) { 2 -> Color(0xFFFF5A5A); 1 -> green; else -> Color.Transparent }
        Box(Modifier.padding(start = 6.dp).size(10.dp).background(c, androidx.compose.foundation.shape.CircleShape)
          .border(1.5.dp, if (s.pedal == 0) Color(0x80FFFFFF) else c, androidx.compose.foundation.shape.CircleShape))
      }
    }
    if (Element.ENGAGED in el) Text(if (s.engaged) "● engaged" else "○ not engaged", style = style.copy(color = if (s.engaged) green else Color(0xFFB0B0B0)))
    if (Element.GPS in el) Text(if (s.lat != null && s.lon != null) "%.6f, %.6f".format(java.util.Locale.US, s.lat, s.lon) else "no GPS fix", style = style)
    val ha = buildList {
      if (Element.HEADING in el) add(s.heading?.let { "${compass(it)} ${"%.0f".format(it)}°" } ?: "--")
      if (Element.ALTITUDE in el) add(s.alt?.let { "${"%.0f".format(it * units.distK)} ${units.distU}" } ?: "--")
    }
    if (ha.isNotEmpty()) Text(ha.joinToString(" · "), style = style)
  }
  if (Element.BLINKERS in el) {
    val flash = (System.currentTimeMillis() / 350) % 2 == 0L
    Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Text("◀", style = style.copy(fontSize = 22.sp, color = if ((s.blink and 1) != 0 && flash) green else Color(0x40FFFFFF)))
      Text("▶", style = style.copy(fontSize = 22.sp, color = if ((s.blink and 2) != 0 && flash) green else Color(0x40FFFFFF)))
    }
  }
  val right = buildList {
    if (Element.LEAD in el) add(if (s.leadOn) "LEAD ${"%.0f".format(s.leadSpeed * units.speedK)} ${units.speedU} · ${"%.0f".format(s.leadDist * units.distK)} ${units.distU}" else "LEAD —")
    if (Element.ACCEL in el) add("ACCEL ${signed(s.accel, 1)} m/s²")
    if (Element.STEER in el) add("STEER ${signed(s.steer, 0)}°")
    if (Element.GFORCE in el) add("%.2f g".format(kotlin.math.hypot(s.latAccel / 9.81f, s.accel / 9.81f)))
  }
  Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
    if (right.isNotEmpty()) Column(box, horizontalAlignment = Alignment.End) {
      for (t in right) Text(t, style = style)
    }
    PreviewGauges(el, Modifier.padding(end = 8.dp, top = if (right.isEmpty()) 8.dp else 0.dp))
  }
}

/** Static stand-ins for the g-force dot and steering wheel, so the preview shows where they'll sit in the export. */
@Composable
private fun PreviewGauges(el: Set<Element>, modifier: Modifier = Modifier) {
  if (Element.GFORCE !in el && Element.WHEEL !in el) return
  val disc = Color(0x61000000)
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (Element.GFORCE in el) Canvas(Modifier.size(44.dp)) {
      val r = size.minDimension / 2
      drawCircle(disc, r)
      val inner = r * 0.78f
      val line = Color(0x6EFFFFFF)
      drawLine(line, center.copy(x = center.x - inner), center.copy(x = center.x + inner), 1.5f)
      drawLine(line, center.copy(y = center.y - inner), center.copy(y = center.y + inner), 1.5f)
      drawCircle(line, inner / 2, style = Stroke(1.5f))
      drawCircle(Color(0xFF7AE066), r * 0.13f)
    }
    if (Element.WHEEL in el) Box(Modifier.size(44.dp).background(disc, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
      androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(app.commaclips.R.drawable.chffr_wheel), contentDescription = null,
        modifier = Modifier.size(34.dp))
    }
  }
}
