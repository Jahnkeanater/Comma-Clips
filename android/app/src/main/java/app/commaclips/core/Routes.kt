package app.commaclips.core

/** Where things live on the comma (openpilot / sunnypilot defaults). */
object DevicePaths {
  const val REALDATA = "/data/media/0/realdata"
  const val OPENPILOT = "/data/openpilot"
  const val PYTHONPATH = "/data/pythonpath"
  const val PYTHON = "/usr/local/venv/bin/python3"
  const val SEG_SECONDS = 60.0
  const val FPS = 20

  /** Lists every file in every segment folder: "<dir> <file> <size> <mtime>". */
  val LIST_COMMAND = "cd $REALDATA && find . -mindepth 2 -maxdepth 2 -type f -printf '%h %f %s %T@\\n'"

  fun pythonCommand(args: List<String>): String {
    val quoted = args.joinToString(" ") { shellQuote(it) }
    return "cd $OPENPILOT && PYTHONPATH=$PYTHONPATH nice -n 19 $PYTHON - $quoted"
  }
}

enum class Camera(val file: String, val label: String) {
  ROAD("fcamera.hevc", "Road"), WIDE("ecamera.hevc", "Wide"), DRIVER("dcamera.hevc", "Driver")
}

data class Segment(val n: Int, val files: Map<String, Long>, val mtime: Double)

data class Route(val name: String, val segments: List<Segment>) {
  val firstSeg get() = segments.first().n
  val lastSeg get() = segments.last().n
  /** Segment folders are last written when a segment closes, ~60 s after it started. */
  val startTime get() = segments.first().mtime - DevicePaths.SEG_SECONDS * (firstSeg + 1)
  val endTime get() = segments.last().mtime
  val minutes get() = lastSeg - firstSeg + 1
  val cameras get() = Camera.entries.filter { c -> segments.any { c.file in it.files } }
  val previewSegments get() = segments.filter { "qcamera.ts" in it.files }.map { it.n }
  fun segment(n: Int) = segments.firstOrNull { it.n == n }
  /** Drive time range in seconds (segment 0 starts at 0). */
  val minTime get() = firstSeg * DevicePaths.SEG_SECONDS
  val maxTime get() = (lastSeg + 1) * DevicePaths.SEG_SECONDS
}

object RouteParser {
  private val ROUTE_RE = Regex("^[0-9a-fA-F]{8}--[0-9a-fA-F]{10}$|^\\d{4}-\\d{2}-\\d{2}--[0-9-]+$")

  fun isRouteName(s: String) = ROUTE_RE.matches(s)

  fun parse(listing: String): List<Route> {
    val byRoute = HashMap<String, HashMap<Int, Pair<HashMap<String, Long>, DoubleArray>>>()
    for (line in listing.lineSequence()) {
      val parts = line.trim().split(" ")
      if (parts.size != 4) continue
      val dir = parts[0].removePrefix("./")
      val idx = dir.lastIndexOf("--")
      if (idx < 0 || dir.count { it == '-' } < 4) continue
      val name = dir.substring(0, idx)
      val seg = dir.substring(idx + 2).toIntOrNull() ?: continue
      if (!isRouteName(name)) continue
      val size = parts[2].toLongOrNull() ?: continue
      val mtime = parts[3].toDoubleOrNull() ?: continue
      val segs = byRoute.getOrPut(name) { HashMap() }
      val entry = segs.getOrPut(seg) { HashMap<String, Long>() to doubleArrayOf(0.0) }
      entry.first[parts[1]] = size
      if (mtime > entry.second[0]) entry.second[0] = mtime
    }
    return byRoute.map { (name, segs) ->
      Route(name, segs.toSortedMap().map { (n, e) -> Segment(n, e.first, e.second[0]) })
    }.sortedByDescending { it.startTime }
  }
}

fun shellQuote(s: String): String =
  if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "@%_-+=:,./" }) s else "'" + s.replace("'", "'\\''") + "'"
