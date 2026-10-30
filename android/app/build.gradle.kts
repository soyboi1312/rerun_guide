plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rerunguide.app"
    compileSdk = 36

    defaultConfig {
        // One APK for phones, tablets, Google TV and Fire TV (the manifest makes touch and leanback both optional).
        applicationId = "com.rerunguide.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.3"
    }

    // Upload key comes from ~/.gradle/gradle.properties, -P, or ORG_GRADLE_PROJECT_<name> env vars; never from the repo.
    val upload = listOf("RERUN_UPLOAD_STORE_FILE", "RERUN_UPLOAD_STORE_PASSWORD", "RERUN_UPLOAD_KEY_ALIAS", "RERUN_UPLOAD_KEY_PASSWORD")
        .associateWith { providers.gradleProperty(it).orNull }
    val uploadSigning = if (upload.values.all { !it.isNullOrBlank() }) signingConfigs.create("upload") {
        storeFile = file(upload.getValue("RERUN_UPLOAD_STORE_FILE")!!)
        storePassword = upload.getValue("RERUN_UPLOAD_STORE_PASSWORD")
        keyAlias = upload.getValue("RERUN_UPLOAD_KEY_ALIAS")
        keyPassword = upload.getValue("RERUN_UPLOAD_KEY_PASSWORD")
    } else null
    if (uploadSigning == null) {
        logger.warn("WARNING: RERUN_UPLOAD_* properties not set; release builds will be UNSIGNED (Play rejects them).")
    }

    buildTypes {
        release {
            signingConfig = uploadSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Channel 99's GIF on Fire TV (Scramble.kt `ScrambleGif`) is the web build's file, so it exists once in the repo.
    sourceSets["main"].assets.srcDir("../../web/assets")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")   // LocalLifecycleOwner (Scramble.kt)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Playback: ExoPlayer plays the servers' HLS transcodes (and far more direct-play formats than AVPlayer).
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")

    // :core is the port of Core/ (plain JVM).
    implementation(project(":core"))

    // The real org.json for JVM unit tests (android.jar's is a stub that throws).
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
