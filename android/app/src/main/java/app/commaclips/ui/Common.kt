package app.commaclips.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.commaclips.data.Conn
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun formatTime(epochSec: Double): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((epochSec * 1000).toLong()))
fun formatTimeS(epochSec: Double): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date((epochSec * 1000).toLong()))
fun formatDay(epochSec: Double): String =
  java.text.SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(Date((epochSec * 1000).toLong()))

fun formatDur(sec: Double): String {
  val s = sec.roundToInt().coerceAtLeast(0)
  val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

fun formatSize(b: Long): String = if (b > 1_000_000_000) "%.2f GB".format(b / 1e9) else "${b / 1_000_000} MB"

@Composable
fun StatusChip(conn: Conn) {
  val (text, color) = when (conn) {
    Conn.Online -> "online" to Accent
    Conn.Connecting -> "connecting" to MaterialTheme.colorScheme.onSurfaceVariant
    Conn.NotSetUp, Conn.NoKey -> "not set up" to Warn
    is Conn.NeedsTrust -> "confirm device" to Warn
    is Conn.Failed -> "offline" to Bad
  }
  Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
  }
}

@Composable
fun SectionCard(title: String? = null, borderColor: Color? = null, content: @Composable ColumnScope.() -> Unit) {
  OutlinedCard(
    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = CardDefaults.outlinedCardBorder().let { if (borderColor != null) androidx.compose.foundation.BorderStroke(1.dp, borderColor) else it },
  ) {
    Column(Modifier.padding(14.dp)) {
      if (title != null) Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
      content()
    }
  }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
  Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = modifier)

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit) {
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
    options.forEachIndexed { i, (v, label) ->
      SegmentedButton(selected = v == selected, onClick = { onSelect(v) }, enabled = enabled(v),
        shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label, maxLines = 1) }
    }
  }
}

@Composable
fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title)
      if (hint != null) Hint(hint)
    }
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}
