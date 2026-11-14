# Comma Clips for Android

A standalone Android app: the phone talks to the comma directly over Wi-Fi. No computer or server is needed.

- **Drives list:** every drive with thumbnails from the comma's own logs.
- **Preview player:** streams straight off the comma, with the same timeline and range picker as the desktop app.
- **Export:**
  - copies the full-resolution road, wide or driver video to the phone and joins the 1-minute segments
  - **Exact cut** re-encodes to H.264 on the phone's hardware encoder
  - **Fast copy** keeps the original HEVC
  - optional telemetry overlay (speed, lead car, acceleration, steering with comma's wheel icon)
  - clips are saved to **Movies/Comma Clips** and can be shared from the app
  - exports keep running with the screen off
- **Connection:**
  - the phone makes its own SSH key, or you can import one
  - search the network for the comma
  - new devices are confirmed by fingerprint
  - if the comma's IP changes, the app finds it again by its SSH key

Like the desktop app, it only **reads** from the comma. Telemetry is read while the comma is parked.

Requires Android 10 or newer.

## Install

1. Get `app-release.apk`: build it (below) or download it from the repo's Releases page if there is one.
2. On the phone, open the APK and allow installing from that source when Android asks.

## First run

1. **Connection → This phone's SSH key → Create key.** Copy or share the public key and add it to your GitHub account (**github.com → Settings → SSH and GPG keys**).
2. On the comma, turn on **SSH** and enter your **GitHub username**. If it was already set, enter it again so the comma picks up the new key.
3. Back in the app, use **Search network** (or type the comma's IP), tap **Use**, confirm the device's fingerprint, then **Save & connect**.

You can also choose **Imported key** and pick a private key file the comma already accepts (without a passphrase).

## Build

Needs JDK 17+ and the Android SDK (platform 37):

```bash
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with your local debug key, so an update must be built on the same computer. Otherwise uninstall the old version first.

### Tests

The JVM tests can run against a real comma (read-only):

```bash
COMMA_TEST_HOST=192.168.1.50 COMMA_TEST_KEY=~/.ssh/id_ed25519 COMMA_TEST_HEVC=/path/to/fcamera.hevc ./gradlew testDebugUnitTest
```

Without those variables, the device tests are skipped.
