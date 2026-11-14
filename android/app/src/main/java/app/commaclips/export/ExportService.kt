package app.commaclips.export

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import app.commaclips.CommaApp
import app.commaclips.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Runs queued exports in the foreground so they keep going with the screen off. */
@UnstableApi
class ExportService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var worker: kotlinx.coroutines.Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    ensureChannel(this)
    startForeground(NOTIFY_ID, notification("Preparing export", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    if (worker?.isActive != true) worker = scope.launch { work() }
    return START_NOT_STICKY
  }

  private suspend fun work() {
    val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "commaclips:export")
    wake.acquire(3 * 60 * 60 * 1000L)
    val nm = getSystemService(NotificationManager::class.java)
    val progressJob = scope.launch {
      Jobs.list.collectLatest { list ->
        list.firstOrNull { it.state == "running" }?.let { nm.notify(NOTIFY_ID, notification(it.phase, (it.progress * 100).toInt())) }
        delay(700)
      }
    }
    try {
      val exporter = Exporter(applicationContext, (application as CommaApp).repo)
      while (true) {
        val job = Jobs.next() ?: break
        Jobs.update(job.id) { it.copy(state = "running") }
        try {
          exporter.run(job)
          done(nm, "Clip saved to Movies/Comma Clips", job.id)
        } catch (e: Cancelled) {
          Jobs.update(job.id) { it.copy(state = "cancelled", message = "cancelled") }
        } catch (e: Throwable) {
          Jobs.update(job.id) { it.copy(state = "error", message = e.message ?: e.javaClass.simpleName) }
          done(nm, "Export failed: ${e.message ?: ""}", job.id)
        }
      }
    } finally {
      progressJob.cancel()
      if (wake.isHeld) wake.release()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private fun done(nm: NotificationManager, text: String, id: String) {
    nm.notify(id.hashCode(), NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Comma Clips").setContentText(text)
      .setContentIntent(openApp()).setAutoCancel(true).build())
  }

  private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("screen", "exports"),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  private fun notification(text: String, pct: Int) = NotificationCompat.Builder(this, CHANNEL)
    .setSmallIcon(android.R.drawable.stat_sys_download)
    .setContentTitle("Exporting clip").setContentText(text)
    .setProgress(100, pct, pct == 0)
    .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp()).build()

  override fun onDestroy() { scope.cancel(); super.onDestroy() }

  companion object {
    const val CHANNEL = "exports"
    const val NOTIFY_ID = 7
    fun ensureChannel(ctx: Context) {
      val nm = ctx.getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(CHANNEL) == null)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW))
    }
    fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ExportService::class.java))
  }
}
