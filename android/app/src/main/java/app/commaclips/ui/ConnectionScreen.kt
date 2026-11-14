package app.commaclips.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.commaclips.data.Conn
import app.commaclips.data.Repo
import app.commaclips.data.TestResult
import app.commaclips.ssh.Found
import app.commaclips.ssh.UnknownHostKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectionScreen(repo: Repo, done: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  val conn by repo.conn.collectAsState()
  val prefs = repo.prefs
  var keyMode by remember { mutableStateOf(prefs.keyMode) }
  var pubKey by remember { mutableStateOf(repo.keys.publicKeyLine()) }
  var keyMsg by remember { mutableStateOf<String?>(null) }
  var address by remember { mutableStateOf(prefs.address) }
  var result by remember { mutableStateOf<TestResult?>(null) }
  var busy by remember { mutableStateOf(false) }
  var found by remember { mutableStateOf<List<Found>?>(null) }
  var scanErr by remember { mutableStateOf<String?>(null) }
  var scanning by remember { mutableStateOf(false) }
  var trustAsk by remember { mutableStateOf<UnknownHostKey?>((conn as? Conn.NeedsTrust)?.err) }

  val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    keyMsg = runCatching {
      val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
      repo.keys.import(bytes)
      prefs.keyMode = "imported"; keyMode = "imported"; repo.keyChanged()
      "Using the imported key."
    }.getOrElse { it.message }
  }

  fun test(save: Boolean) = scope.launch {
    busy = true
    val r = repo.test(address)
    result = r
    if (r.trust != null) trustAsk = r.trust
    if (r.ok && save) { repo.saveAddress(address); runCatching { repo.refreshRoutes() }; done() }
    busy = false
  }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    if (prefs.address.isBlank() || conn is Conn.NoKey) SectionCard("Welcome to Comma Clips", borderColor = Accent) {
      Hint("Connect to your comma in three steps:")
      Text("1. Create this phone's key below and add it to your GitHub account (github.com → Settings → SSH and GPG keys).", fontSize = 14.sp)
      Text("2. On the comma, turn on SSH and enter your GitHub username (re-enter it after adding a new key so the comma picks it up).", fontSize = 14.sp)
      Text("3. Make sure the phone is on the same Wi-Fi, then search for the comma or type its IP.", fontSize = 14.sp)
    }

    SectionCard("This phone's SSH key") {
      Segmented(listOf("generated" to "Phone's own key", "imported" to "Imported key"), keyMode) {
        keyMode = it; prefs.keyMode = it; repo.keyChanged()
      }
      Spacer(Modifier.height(8.dp))
      if (keyMode == "generated") {
        if (pubKey == null) {
          Hint("The phone makes its own key. The private half never leaves the phone.")
          Button(onClick = { scope.launch { pubKey = withContext(Dispatchers.Default) { repo.keys.generate() }; repo.keyChanged() } },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Create key") }
        } else {
          Hint("Add this public key to your GitHub account, then re-enter your GitHub username on the comma:")
          SelectionContainer {
            Text(pubKey!!, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
          }
          Hint(repo.keys.fingerprint().orEmpty())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = {
              ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ssh key", pubKey))
              keyMsg = "Copied"
            }, modifier = Modifier.weight(1f)) { Text("Copy") }
            OutlinedButton(onClick = {
              ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pubKey), "Send key"))
            }, modifier = Modifier.weight(1f)) { Text("Share") }
          }
        }
      } else {
        Hint("Pick a private key file the comma already accepts (for example one copied from your computer). Keys with a passphrase aren't supported.")
        Hint(if (repo.keys.importedFile.exists()) "A key is imported." else "No key imported yet.", Modifier.padding(top = 4.dp))
        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Choose key file") }
      }
      keyMsg?.let { Hint(it, Modifier.padding(top = 6.dp)) }
    }

    SectionCard("Comma address") {
      Hint("Now: " + (prefs.address.ifBlank { "not set up" }))
      OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
        label = { Text("IP address") }, placeholder = { Text("192.168.1.50") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { test(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Test") }
        Button(onClick = { test(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save & connect") }
      }
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
      result?.let { r ->
        Text((if (r.ok) "✓ " else "✗ ") + r.message, color = if (r.ok) Accent else if (r.trust != null) Warn else Bad,
          fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
      }
      Hint("If the router gives the comma a new IP later, the app finds it again by its SSH key.", Modifier.padding(top = 6.dp))
    }

    SectionCard("Find my comma") {
      Hint("Looks for devices on your Wi-Fi that accept SSH, and marks ones named comma-… or whose key you already trust.")
      OutlinedButton(onClick = {
        scope.launch {
          scanning = true; scanErr = null
          found = runCatching { repo.scan() }.onFailure { scanErr = it.message }.getOrNull()
          scanning = false
        }
      }, enabled = !scanning, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (scanning) "Searching…" else "Search network") }
      scanErr?.let { Hint(it) }
      found?.let { list ->
        val likely = list.filter { it.comma || it.trusted }
        if (likely.isEmpty()) Hint("No comma found. Check it's switched on and on the same Wi-Fi.", Modifier.padding(top = 6.dp))
        for (f in likely + list.filter { !(it.comma || it.trusted) }.take(12)) {
          Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(f.name?.substringBefore('.') ?: f.ip, fontWeight = if (f.comma || f.trusted) FontWeight.SemiBold else FontWeight.Normal)
              Hint(f.ip + (if (f.comma) " · comma" else "") + (if (f.trusted) " · trusted key" else ""))
            }
            TextButton(onClick = { address = f.ip; test(false) }) { Text("Use") }
          }
        }
      }
    }
  }

  trustAsk?.let { t ->
    AlertDialog(onDismissRequest = { trustAsk = null },
      title = { Text("Trust this device?") },
      text = {
        Column {
          Text("${t.address} has an SSH key the app hasn't seen before. Only trust it if this is your comma.")
          Text("${t.type}\n${t.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
          Hint("You can compare it on a computer with: ssh-keyscan ${t.address} | ssh-keygen -lf -", Modifier.padding(top = 8.dp))
        }
      },
      confirmButton = { TextButton(onClick = { repo.trust(t); trustAsk = null; address = t.address; test(false) }) { Text("Trust") } },
      dismissButton = { TextButton(onClick = { trustAsk = null }) { Text("Cancel") } })
  }
}
