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
    versionCode = 3
    versionName = "1.2"
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
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.pebblekit)
  testImplementation(libs.junit)
}
