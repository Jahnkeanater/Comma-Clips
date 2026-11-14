package app.commaclips

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.util.UnstableApi
import app.commaclips.data.Conn
import app.commaclips.export.Jobs
import app.commaclips.ui.*

sealed interface Screen {
  data object Drives : Screen
  data object Exports : Screen
  data object Connection : Screen
  data class Drive(val route: String) : Screen
}

@UnstableApi
class MainActivity : ComponentActivity() {
  private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    val repo = (application as CommaApp).repo
    val start = if (intent?.getStringExtra("screen") == "exports") Screen.Exports else Screen.Drives
    setContent {
      CommaTheme {
        var screen by remember { mutableStateOf<Screen>(start) }
        val conn by repo.conn.collectAsState()
        val jobs by Jobs.list.collectAsState()
        LaunchedEffect(conn) {
          if ((conn is Conn.NotSetUp || conn is Conn.NoKey) && screen !is Screen.Connection) screen = Screen.Connection
        }
        BackHandler(enabled = screen != Screen.Drives) { screen = Screen.Drives }
        Scaffold(
          topBar = {
            TopAppBar(
              title = {
                Text(when (val s = screen) {
                  Screen.Drives -> "Comma Clips"; Screen.Exports -> "Exports"; Screen.Connection -> "Connection"
                  is Screen.Drive -> repo.routes.value?.firstOrNull { it.name == s.route }?.let { formatDay(it.startTime) + " · " + formatTime(it.startTime) } ?: "Drive"
                }, maxLines = 1, overflow = TextOverflow.Ellipsis)
              },
              navigationIcon = {
                if (screen != Screen.Drives) IconButton(onClick = { screen = Screen.Drives }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
              },
              actions = {
                StatusChip(conn)
                IconButton(onClick = { screen = Screen.Connection }) { Icon(Icons.Default.Settings, "Connection") }
              },
            )
          },
          bottomBar = {
            if (screen == Screen.Drives || screen == Screen.Exports) NavigationBar {
              NavigationBarItem(selected = screen == Screen.Drives, onClick = { screen = Screen.Drives },
                icon = { Icon(Icons.Default.Home, null) }, label = { Text("Drives") })
              val active = jobs.count { it.state == "running" || it.state == "queued" }
              NavigationBarItem(selected = screen == Screen.Exports, onClick = { screen = Screen.Exports },
                icon = { BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) { Icon(Icons.AutoMirrored.Filled.List, null) } },
                label = { Text("Exports") })
            }
          },
        ) { pad ->
          Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = screen) {
              Screen.Drives -> DrivesScreen(repo) { screen = Screen.Drive(it) }
              Screen.Exports -> ExportsScreen()
              Screen.Connection -> ConnectionScreen(repo) { screen = Screen.Drives }
              is Screen.Drive -> DriveScreen(repo, s.route) { screen = Screen.Exports }
            }
          }
        }
      }
    }
  }
}
