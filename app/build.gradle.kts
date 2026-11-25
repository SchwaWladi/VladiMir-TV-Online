plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vladimir.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vladimirtv.online"
        minSdk = 23
        targetSdk = 28
        versionCode = 4
        versionName = "3.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}
