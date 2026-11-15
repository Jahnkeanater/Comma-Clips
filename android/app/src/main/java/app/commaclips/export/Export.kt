package app.commaclips.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.*
import app.commaclips.R
import app.commaclips.core.*
import app.commaclips.data.Repo
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ExportParams(
  val route: String, val start: Double, val end: Double, val camera: Camera,
  val osd: Boolean, val units: Units, val fast: Boolean, val quality: Int,
  val audio: Boolean = true,
  val elements: Set<Element> = Element.defaults,
)

data class ExportJob(
  val id: String = UUID.randomUUID().toString().take(8),
  val params: ExportParams,
  val state: String = "queued", // queued | running | done | error | cancelled
  val phase: String = "waiting",
  val progress: Float = 0f,
  val message: String = "",
  val output: Uri? = null,
  val name: String? = null,
)

/** Export queue shared by the UI and the foreground service. */
object Jobs {
  private val _list = MutableStateFlow<List<ExportJob>>(emptyList())
  val list: StateFlow<List<ExportJob>> = _list
  private val cancelled = HashSet<String>()

  fun add(p: ExportParams): ExportJob = ExportJob(params = p).also { j -> _list.update { it + j } }
  fun update(id: String, f: (ExportJob) -> ExportJob) = _list.update { l -> l.map { if (it.id == id) f(it) else it } }
  fun next(): ExportJob? = _list.value.firstOrNull { it.state == "queued" }
  fun cancel(id: String) { synchronized(cancelled) { cancelled.add(id) }; update(id) { if (it.state == "queued") it.copy(state = "cancelled") else it } }
  fun isCancelled(id: String) = synchronized(cancelled) { id in cancelled }
  fun clearFinished() = _list.update { l -> l.filter { it.state == "queued" || it.state == "running" } }
}

/** Draws the overlay for each output frame, matching frames to telemetry by their timestamp. */
@UnstableApi
class TelemetryOverlay(
  private val renderer: OverlayRenderer,
  private val tel: Telemetry,
  private val mp4FirstFrame: Int,   // global frame index of the first frame in the intermediate MP4
  private val clipStartUs: Long,    // where the clip starts inside that MP4
) : BitmapOverlay() {
  private val bitmap = Bitmap.createBitmap(renderer.width, renderer.height, Bitmap.Config.ARGB_8888)
  private val canvas = Canvas(bitmap)
  private var absolute: Boolean? = null
  private var lastBucket = Int.MIN_VALUE

  override fun getBitmap(presentationTimeUs: Long): Bitmap {
    // Depending on the Media3 version, frame times either start at 0 or keep the clip offset.
    val abs = absolute ?: (presentationTimeUs >= clipStartUs - 25_000).also { absolute = it }
    val us = if (abs) presentationTimeUs else presentationTimeUs + clipStartUs
    val frame = mp4FirstFrame + Math.round(us / (1_000_000.0 / DevicePaths.FPS)).toInt()
    val bucket = frame / 2 // redraw every 0.1 s
    if (bucket != lastBucket) {
      lastBucket = bucket
      val mono = tel.frameMono(frame)
      renderer.draw(canvas, mono?.let { tel.sample(it) }, mono?.let { tel.wallMillis(it) }, frame)
    }
    return bitmap
  }
}

class Cancelled : Exception("cancelled")

@UnstableApi
class Exporter(private val ctx: Context, private val repo: Repo) {
  /** Decoder used by the last exact export (shown on the Exports screen to help diagnose device problems). */
  @Volatile var decoderName: String? = null


  suspend fun run(job: ExportJob) {
    val p = job.params
    val id = job.id
    fun up(phase: String, progress: Float, msg: String = "") = Jobs.update(id) { it.copy(phase = phase, progress = progress, message = msg) }
    fun check() { if (Jobs.isCancelled(id)) throw Cancelled() }

    val route = repo.routes.value?.firstOrNull { it.name == p.route } ?: repo.refreshRoutes().first { it.name == p.route }
    val segLen = DevicePaths.SEG_SECONDS
    val k0 = (p.start / segLen).toInt()
    val k1 = kotlin.math.ceil(p.end / segLen).toInt() - 1
    val segs = (k0..k1).toList()
    val missing = segs.filter { route.segment(it)?.files?.containsKey(p.camera.file) != true }
    require(missing.isEmpty()) { "Minutes $missing have no ${p.camera.label.lowercase()} video on the comma (deleted or not recorded)." }

    val wFetch = if (p.fast) 0.85f else 0.4f
    val wTel = if (p.osd) 0.1f else 0f
    val wMux = if (p.fast) 0.15f else 0.1f

    // 1) copy full-resolution video from the comma
    val sizes = segs.map { route.segment(it)!!.files[p.camera.file]!! }
    val total = sizes.sum().coerceAtLeast(1)
    val local = ArrayList<File>()
    var before = 0L
    for ((i, s) in segs.withIndex()) {
      check()
      val f = File(repo.segDir, "${p.route}/$s/${p.camera.file}")
      if (!(f.exists() && f.length() == sizes[i])) {
        f.parentFile!!.mkdirs()
        val tmp = File(f.path + ".part")
        val b = before
        repo.withComma { c ->
          tmp.outputStream().use { out ->
            c.download("${DevicePaths.REALDATA}/${p.route}--$s/${p.camera.file}", out,
              progress = { n -> up("Copying video from comma", wFetch * (b + n) / total, "${(b + n) / 1_000_000} / ${total / 1_000_000} MB") },
              cancelled = { Jobs.isCancelled(id) })
          }
        }
        tmp.renameTo(f)
      }
      before += sizes[i]
      local.add(f)
    }

    // 2) telemetry
    var tel: Telemetry? = null
    if (p.osd) {
      check()
      if (repo.deviceOnroad()) throw IllegalStateException("The comma is driving right now. Telemetry is only read while it's parked.")
      val data = repo.telemetry(p.route, segs) { done, n -> up("Reading telemetry", wFetch + wTel * done / n.coerceAtLeast(1), "minute $done/$n") }
      tel = Telemetry(data, segs, p.camera.name.lowercase())
    }

    // 2b) cabin audio: it lives in the small preview files (the full-resolution video has none)
    val audioFiles: List<File?> = if (!p.audio) emptyList() else segs.map { s ->
      check()
      val size = route.segment(s)?.files?.get("qcamera.ts") ?: return@map null
      val f = File(repo.segDir, "${p.route}/$s/qcamera.ts")
      if (!(f.exists() && f.length() == size)) runCatching {
        f.parentFile!!.mkdirs()
        val tmp = File(f.path + ".part")
        up("Copying audio from comma", wFetch, "minute ${s - k0 + 1}/${segs.size}")
        repo.withComma { c -> tmp.outputStream().use { out -> c.download("${DevicePaths.REALDATA}/${p.route}--$s/qcamera.ts", out, cancelled = { Jobs.isCancelled(id) }) } }
        tmp.renameTo(f)
      }
      f.takeIf { it.exists() && it.length() == size }
    }

    // 3) join into an MP4 (cut at the keyframe before the start)
    check()
    val startFrame = Math.round((p.start - k0 * segLen) * DevicePaths.FPS).toInt()
    val endFrame = Math.round((p.end - k0 * segLen) * DevicePaths.FPS).toInt()
    val work = File(ctx.cacheDir, "export").apply { mkdirs() }
    val joined = File(work, "$id-joined.mp4")
    val mux = withContext(Dispatchers.IO) {
      HevcMuxer.mux(local, startFrame, endFrame, joined, audio = audioFiles,
        progress = { f -> up("Joining video", wFetch + wTel + wMux * f) }, cancelled = { Jobs.isCancelled(id) })
    }

    val clipStartUs = (startFrame - mux.firstFrame) * 1_000_000L / DevicePaths.FPS
    val clipEndUs = (endFrame - mux.firstFrame) * 1_000_000L / DevicePaths.FPS
    val wall = tel?.frameMono(startFrame)?.let { tel.wallMillis(it) }
      ?: ((route.startTime + k0 * segLen + (if (p.fast) mux.firstFrame else startFrame) / DevicePaths.FPS.toDouble()) * 1000).toLong()
    val seconds = if (p.fast) (mux.frames / DevicePaths.FPS) else ((endFrame - startFrame) / DevicePaths.FPS)
    val name = "comma_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(wall))}_${seconds}s_${p.camera.name.lowercase()}${if (p.osd) "_osd" else ""}.mp4"

    val finalFile: File
    if (p.fast) {
      finalFile = joined
    } else {
      // 4) exact cut, H.264, optional overlay
      check()
      val out = File(work, "$id-out.mp4")
      // the comma records 16 kHz mono; some players (e.g. Google Photos) skip audio that unusual, so convert
      // it to the standard 48 kHz stereo that phones and cameras use
      val audioFx: List<androidx.media3.common.audio.AudioProcessor> = if (mux.hasAudio) listOf(
        androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) },
        androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
          putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(1, 2))
          putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.createForConstantGain(2, 2))
        }) else emptyList()
      val effects = if (tel != null) {
        val wheel = BitmapFactory.decodeResource(ctx.resources, R.drawable.chffr_wheel)
        val overlay = TelemetryOverlay(OverlayRenderer(mux.width, mux.height, wheel, p.units, p.elements), tel, mux.firstFrame, clipStartUs)
        Effects(audioFx, listOf(OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(overlay))))
      } else Effects(audioFx, emptyList())
      val item = MediaItem.Builder().setUri(Uri.fromFile(joined))
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
          .setStartPositionUs(clipStartUs).setEndPositionUs(clipEndUs).build())
        .build()
      val edited = EditedMediaItem.Builder(item).setRemoveAudio(!mux.hasAudio).setEffects(effects).build()
      val bitrate = when (p.quality) { 0 -> 16_000_000; 1 -> 8_000_000; else -> 4_000_000 } * mux.width * mux.height / (1344 * 760)
      transform(edited, out, bitrate) { f -> up("Encoding", wFetch + wTel + wMux + (1 - wFetch - wTel - wMux) * f) }
      joined.delete()
      finalFile = out
    }

    // 5) save to the gallery (Movies/Comma Clips)
    up("Saving", 0.99f)
    val uri = saveToGallery(finalFile, name)
    finalFile.delete()
    Jobs.update(id) { it.copy(state = "done", phase = "done", progress = 1f, output = uri, name = name,
      message = if (p.fast) "" else "decoder: ${decoderName ?: "?"}") }
  }

  private suspend fun transform(item: EditedMediaItem, out: File, bitrate: Int, progress: (Float) -> Unit) =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { cont ->
        // Decode with Android's software HEVC decoder first: some phones' hardware decoders hand back a padded
        // buffer (e.g. 1920x1088) without the crop for the comma's 1344x760 picture, which shows up as the
        // picture shrunk into a corner with grey/garbage around it. The hardware decoder stays as a fallback.
        val decoders = DefaultDecoderFactory.Builder(ctx)
          .setMediaCodecSelector(androidx.media3.exoplayer.mediacodec.MediaCodecSelector.PREFER_SOFTWARE)
          .setEnableDecoderFallback(true)
          .setListener { name, _ -> decoderName = name }
          .build()
        val assets = DefaultAssetLoaderFactory(ctx, decoders, androidx.media3.common.util.Clock.DEFAULT,
          androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx), androidx.media3.datasource.DataSourceBitmapLoader(ctx))
        val transformer = Transformer.Builder(ctx)
          .setAssetLoaderFactory(assets)
          .setVideoMimeType(MimeTypes.VIDEO_H264)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .setEncoderFactory(DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build())
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
              if (cont.isActive) cont.resumeWithException(RuntimeException("Encoding failed: ${exportException.message}", exportException))
            }
          })
          .build()
        transformer.start(item, out.absolutePath)
        val holder = ProgressHolder()
        val poll = CoroutineScope(Dispatchers.Main).launch {
          while (isActive) {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
            delay(500)
          }
        }
        cont.invokeOnCancellation { poll.cancel(); transformer.cancel() }
        cont.context[kotlinx.coroutines.Job]?.invokeOnCompletion { poll.cancel() }
      }
    }

  private fun saveToGallery(f: File, name: String): Uri {
    val values = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, name)
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Comma Clips")
      put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("couldn't create the file in Movies")
    resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    return uri
  }
}
