package app.commaclips

import android.app.Application
import app.commaclips.data.Repo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class CommaApp : Application() {
  lateinit var repo: Repo
    private set

  override fun onCreate() {
    super.onCreate()
    // Android ships a cut-down "BC" provider; SSH needs the full BouncyCastle one.
    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 1)
    repo = Repo(this)
  }
}
