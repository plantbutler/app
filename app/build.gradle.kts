import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The backend URL and token come from an untracked local file (the plan's
// "URL and token from an untracked local file"); a missing or half-edited
// file fails the build loudly instead of producing an app that talks to
// nowhere — and the values are escaped, or a quote in the token would land
// verbatim in the generated BuildConfig.java.
val butler =
    Properties().apply {
        val file = rootProject.file("butler.properties")
        require(file.exists()) {
            "copy butler.properties.sample to butler.properties and fill in url= and token="
        }
        file.inputStream().use { load(it) }
    }

val butlerUrl =
    requireNotNull(butler.getProperty("url")) { "butler.properties is missing url=" }
val butlerToken =
    requireNotNull(butler.getProperty("token")) { "butler.properties is missing token=" }

require(butlerUrl.startsWith("http")) {
    "butler.properties url= must start with http, got '$butlerUrl'"
}

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
        buildConfigField("String", "BUTLER_URL", javaQuoted(butlerUrl))
        // Unused until the water-now pitch; baked already so the properties
        // file's contract does not change under the next pitch's feet.
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
    testImplementation("junit:junit:4.13.2")
}
