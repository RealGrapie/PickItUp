import java.util.Properties

// Release key: android/keystore.properties (never in git) with storeFile, storePassword, keyAlias,
// keyPassword. Without it (CI, someone else's clone) release builds get the debug key.
val keystoreProps = Properties().apply {
  rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.grapie.pickitup"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.grapie.pickitup"
    minSdk = 31  // Android 12: the Pebble (Core Devices) app needs it anyway
    targetSdk = 37
    // Set by hand for every release; it must match the GitHub tag ("1.6-beta.1" <-> v1.6-beta.1),
    // the updater compares the two. versionCode comes from the build time (BuildTime below).
    versionName = "1.6-beta.3"
  }

  signingConfigs {
    if (!keystoreProps.isEmpty) create("release") {
      storeFile = file(keystoreProps.getProperty("storeFile"))
      storePassword = keystoreProps.getProperty("storePassword")
      keyAlias = keystoreProps.getProperty("keyAlias")
      keyPassword = keystoreProps.getProperty("keyPassword")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
    }
    // Release that also trusts user CA certificates (src/sniff): to watch Ozon traffic with a
    // proxy like Reqable. Same key and id, so it installs over a release without losing the login.
    create("sniff") {
      initWith(getByName("release"))
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.webkit)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.pebblekit)
  implementation(libs.zxing.core)
  testImplementation(libs.junit)
  testImplementation(libs.org.json)
}

/**
 * The build's time, read when the APK is made (worked out in defaultConfig the configuration cache
 * would keep it, and builds days apart would get one versionCode).
 */
abstract class BuildTime : ValueSource<Long, ValueSourceParameters.None> {
  override fun obtain(): Long = System.currentTimeMillis()
}

// versionCode = minutes since 2026-01-01 UTC: every build is newer than the one before, so a beta
// and the release after it never need a hand-picked code. Was 10 by hand up to 1.5.1; fits an Int
// (and Play's 2 100 000 000 limit) until about the year 6000.
androidComponents {
  onVariants { variant ->
    val time = providers.of(BuildTime::class.java) {}
    variant.outputs.forEach { out ->
      out.versionCode.set(time.map { ((it - 1_767_225_600_000L) / 60_000L).toInt() })
    }
  }
}
