package app.commaclips.data

import android.content.Context
import app.commaclips.core.Camera
import app.commaclips.core.Units

/** Small persistent settings (address, trusted device keys, export choices). */
class Prefs(context: Context) {
  private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var address: String
    get() = p.getString("address", "") ?: ""
    set(v) = p.edit().putString("address", v).apply()
  var user: String
    get() = p.getString("user", "comma") ?: "comma"
    set(v) = p.edit().putString("user", v).apply()
  /** "generated" = the app's own key, "imported" = a key file the user picked. */
  var keyMode: String
    get() = p.getString("keyMode", "generated") ?: "generated"
    set(v) = p.edit().putString("keyMode", v).apply()
  var trustedKeys: Set<String>
    get() = p.getStringSet("trusted", emptySet()) ?: emptySet()
    set(v) = p.edit().putStringSet("trusted", v).apply()

  var camera: Camera
    get() = runCatching { Camera.valueOf(p.getString("camera", "ROAD")!!) }.getOrDefault(Camera.ROAD)
    set(v) = p.edit().putString("camera", v.name).apply()
  var osd: Boolean
    get() = p.getBoolean("osd", true)
    set(v) = p.edit().putBoolean("osd", v).apply()
  var units: Units
    get() = runCatching { Units.valueOf(p.getString("units", "MPH")!!) }.getOrDefault(Units.MPH)
    set(v) = p.edit().putString("units", v.name).apply()
  var fastMode: Boolean
    get() = p.getBoolean("fast", false)
    set(v) = p.edit().putBoolean("fast", v).apply()
  var quality: Int  // 0 high, 1 medium, 2 small
    get() = p.getInt("quality", 0)
    set(v) = p.edit().putInt("quality", v).apply()
  var exportAudio: Boolean
    get() = p.getBoolean("exportAudio", true)
    set(v) = p.edit().putBoolean("exportAudio", v).apply()
  var muted: Boolean
    get() = p.getBoolean("muted", false)
    set(v) = p.edit().putBoolean("muted", v).apply()
  var hud: Boolean
    get() = p.getBoolean("hud", false)
    set(v) = p.edit().putBoolean("hud", v).apply()
}
