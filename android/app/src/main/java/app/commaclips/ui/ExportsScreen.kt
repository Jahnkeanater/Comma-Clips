package app.commaclips.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.commaclips.export.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SavedClip(val uri: Uri, val name: String, val size: Long, val date: Long)

@Composable
fun ExportsScreen() {
  val ctx = LocalContext.current
  val jobs by Jobs.list.collectAsState()
  var clips by remember { mutableStateOf<List<SavedClip>>(emptyList()) }
  var reload by remember { mutableIntStateOf(0) }
  val doneCount = jobs.count { it.state == "done" }
  LaunchedEffect(reload, doneCount) {
    clips = withContext(Dispatchers.IO) {
      val out = ArrayList<SavedClip>()
      val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED)
      ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/Comma Clips%"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
        while (c.moveToNext()) out.add(SavedClip(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
          c.getString(1), c.getLong(2), c.getLong(3)))
      }
      out
    }
  }
  val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload++ }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val active = jobs.filter { it.state != "done" }
    items(active, key = { it.id }) { j ->
      SectionCard(borderColor = if (j.state == "error") Bad else null) {
        val p = j.params
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("${p.camera.label} · ${formatDur(p.end - p.start)}${if (p.osd) " · overlay" else ""}${if (p.audio) " · audio" else ""}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
          if (j.state == "running" || j.state == "queued") TextButton(onClick = { Jobs.cancel(j.id) }) { Text("Cancel") }
        }
        if (j.state == "running" || j.state == "queued") LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Hint(when (j.state) {
          "error" -> "Failed: ${j.message}"
          "cancelled" -> "Cancelled"
          else -> "${j.phase}${if (j.message.isNotEmpty()) " · ${j.message}" else ""} · ${(j.progress * 100).toInt()}%"
        })
      }
    }
    item { Text("Saved clips", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
    if (clips.isEmpty()) item { Hint("No exported clips yet. They're saved to Movies/Comma Clips.") }
    items(clips, key = { it.uri.toString() }) { c ->
      OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
          val thumb by produceState<android.graphics.Bitmap?>(null, c.uri) {
            value = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(c.uri, Size(320, 180), null) }.getOrNull() }
          }
          Box(Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
          }
          Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(c.name, maxLines = 2)
            Hint("${formatSize(c.size)} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(c.date * 1000))}")
          }
          IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(c.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }) {
            Icon(Icons.Default.PlayArrow, "Play")
          }
          IconButton(onClick = {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, c.uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clip"))
          }) { Icon(Icons.Default.Share, "Share") }
          IconButton(onClick = {
            try { ctx.contentResolver.delete(c.uri, null, null); reload++ }
            catch (e: SecurityException) {
              // Clips made before a reinstall belong to the old install: Android asks the user to confirm.
              val sender = when {
                android.os.Build.VERSION.SDK_INT >= 30 -> MediaStore.createDeleteRequest(ctx.contentResolver, listOf(c.uri)).intentSender
                e is android.app.RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
              }
              sender?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            }
          }) { Icon(Icons.Default.Delete, "Delete") }
        }
      }
    }
  }
}
