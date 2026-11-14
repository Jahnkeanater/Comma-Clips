package app.commaclips.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.commaclips.core.Route
import app.commaclips.data.Conn
import app.commaclips.data.Repo
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivesScreen(repo: Repo, open: (String) -> Unit) {
  val routes by repo.routes.collectAsState()
  val thumbs by repo.thumbs.collectAsState()
  val conn by repo.conn.collectAsState()
  var error by remember { mutableStateOf<String?>(null) }
  var refreshing by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  suspend fun load() {
    refreshing = true
    error = runCatching { repo.loadThumbs(repo.refreshRoutes()) }.exceptionOrNull()?.message
    refreshing = false
  }
  LaunchedEffect(Unit) { if (routes == null) load() else repo.loadThumbs(routes!!) }

  PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { load() } }, modifier = Modifier.fillMaxSize()) {
    LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize()) {
      if (error != null && routes.isNullOrEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
        SectionCard(borderColor = Warn) {
          Text(if (conn is Conn.NeedsTrust) "Open Connection to confirm this device." else "Couldn't reach the comma: $error")
        }
      }
      val list = routes.orEmpty()
      if (routes != null && list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Hint("No drives on the device.") }
      var lastDay = ""
      for (r in list) {
        val day = formatDay(r.startTime)
        if (day != lastDay) {
          lastDay = day
          item(span = { GridItemSpan(maxLineSpan) }, key = "day-$day") {
            Text(day.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
          }
        }
        item(key = r.name) { RouteCard(r, thumbs[r.name]) { open(r.name) } }
      }
    }
  }
}

@Composable
private fun RouteCard(r: Route, thumb: File?, onClick: () -> Unit) {
  OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp)) {
    val bmp = remember(thumb) { thumb?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
      if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)))
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
      Text("${formatTime(r.startTime)} – ${formatTime(r.endTime)}", fontWeight = FontWeight.SemiBold)
      val cams = r.cameras.joinToString(" · ") { it.label }
      Hint("${r.minutes} min · ${cams.ifEmpty { "no video" }}${if (r.firstSeg > 0) " · partly deleted" else ""}")
    }
  }
}
