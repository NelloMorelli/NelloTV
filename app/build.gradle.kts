import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun localProp(name: String) = "\"" + local.getProperty(name, "") + "\""

android {
    namespace = "com.nello.tvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nello.tvhome"
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TMDB_KEY", localProp("tmdb.key"))
        buildConfigField("String", "TOPX_BASE", localProp("topx.base"))
        buildConfigField("String", "SPORTS_BASE", localProp("sports.base"))
        buildConfigField("String", "NUVIO_KEY", localProp("nuvio.key"))
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
