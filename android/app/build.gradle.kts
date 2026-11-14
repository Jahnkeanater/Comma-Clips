plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "app.commaclips"
  compileSdk = 37
  defaultConfig {
    applicationId = "app.commaclips"
    minSdk = 29
    targetSdk = 36
    versionCode = 4
    versionName = "0.3.0"
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging {
    resources.excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "org/bouncycastle/pqc/**")
  }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
  implementation(composeBom)
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
  implementation("androidx.core:core-ktx:1.19.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

  val media3 = "1.11.1"
  implementation("androidx.media3:media3-exoplayer:$media3")
  implementation("androidx.media3:media3-ui:$media3")
  implementation("androidx.media3:media3-transformer:$media3")
  implementation("androidx.media3:media3-effect:$media3")
  implementation("androidx.media3:media3-common:$media3")

  implementation("com.hierynomus:sshj:0.41.2")
  implementation("org.bouncycastle:bcprov-jdk18on:1.83")
  implementation("org.bouncycastle:bcpkix-jdk18on:1.83")
  implementation("org.slf4j:slf4j-nop:2.0.17")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20250517")
}
