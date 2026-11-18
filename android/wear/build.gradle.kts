import java.util.Properties

// Wear OS app. Same applicationId and signing key as the phone app: the Data Layer only pairs
// apps with the same package name and certificate.
val keystoreProps = Properties().apply {
  rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.grapie.pickitup.wear"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.grapie.pickitup"
    minSdk = 30  // Wear OS 3
    targetSdk = 37
    versionCode = 10
    versionName = "1.5.1"
  }

  signingConfigs {
    if (!keystoreProps.isEmpty) create("release") {
      storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
      storePassword = keystoreProps.getProperty("storePassword")
      keyAlias = keystoreProps.getProperty("keyAlias")
      keyPassword = keystoreProps.getProperty("keyPassword")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.play.services.wearable)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.zxing.core)  // QR view
}
