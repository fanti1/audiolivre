import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release signing key stays out of the repository: keystore.properties (git-ignored) points at it.
// Every release must be signed with the same key, or phones refuse to update over the installed app.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    namespace = "io.github.fanti1.audiolivre"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.fanti1.audiolivre"
        // 26 (Android 8.0) needed for AudioTrack MODE_STREAM float encoding used by the TTS pipeline.
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            // Phones are ARM64; the x86_64 libraries only serve the emulator and would double the download.
            ndk { abiFilters += "arm64-v8a" }
        }
        debug {
            // Phones and the emulator; the translation engine is only built for these two.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    // The translation engine (slimt) is compiled from source in app/src/main/cpp. A checkout without its
    // sources still builds; the app then says translation is unavailable.
    ndkVersion = "28.0.13004108"
    if (file("src/main/cpp/third_party/slimt/CMakeLists.txt").isFile) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        // Only the app's library: slimt and SentencePiece also define command-line tools nobody ships.
        defaultConfig.externalNativeBuild.cmake.targets += "audiolivre_translate"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // The About screen shows the version name.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    implementation("androidx.media:media:1.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // sherpa-onnx voice models ship as .tar.bz2; the JDK has no built-in bzip2 support.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // android.jar's org.json is a stub that throws in JVM unit tests; the real library lets BookJson be tested.
    testImplementation("org.json:json:20240303")
}
