import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The backend URL and token used to be required here and were the only
// source. Since "Where is the butler?" (2026-09-04) the app asks on first
// start and keeps the answer in the device's encrypted store, so this file
// is optional and only prefills that screen for a development build.
//
// Optional is the point, not a convenience: an APK built without it carries
// no token at all, which is what makes one build installable on somebody
// else's phone against somebody else's butler.
//
// The values are still escaped, or a quote in the token would land verbatim
// in the generated BuildConfig.java.
val butler =
    Properties().apply {
        val file = rootProject.file("butler.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

val butlerUrl = butler.getProperty("url").orEmpty()
val butlerToken = butler.getProperty("token").orEmpty()

fun javaQuoted(value: String) =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "garden.butler.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "garden.butler.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Only what the setup screen starts filled in with. Empty in a
        // build made without butler.properties, and the app then asks.
        buildConfigField("String", "BUTLER_URL", javaQuoted(butlerUrl))
        buildConfigField("String", "BUTLER_TOKEN", javaQuoted(butlerToken))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The address and the token at rest on the phone. The token is the one
    // secret this app holds, so it goes in the encrypted store rather than
    // in plain preferences, which are a readable file to anything with root
    // or a backup of one.
    implementation("androidx.security:security-crypto:1.0.0")
    // The only picture the app loads: the care source's photograph of a
    // species, so somebody searching by common name can confirm by eye.
    implementation("io.coil-kt:coil-compose:2.7.0")
    // FileProvider, for handing the camera app somewhere to write, and
    // ExifInterface, because a phone writes the sensor's orientation into a
    // tag rather than into the pixels — re-encoding drops it, and without
    // this every portrait picture would come back on its side for good.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
