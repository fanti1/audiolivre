import java.util.Properties
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

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
        versionCode = 3
        versionName = "1.1"
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
            // R8 drops the unused parts of the libraries (pdfbox, BouncyCastle, commons-compress): a smaller APK
            // to make room for the narrator's voice. proguard-rules.pro keeps what native code calls by name.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
            // BouncyCastle (pulled in by pdfbox for encrypted PDFs) carries 8 MB of post-quantum key tables
            // that PDF encryption never uses.
            excludes += "/org/bouncycastle/pqc/**/*.properties"
        }
    }

    // The bundled voice model is read straight from the APK; stored uncompressed it loads without inflating.
    androidResources {
        noCompress += "onnx"
    }
}

// The narrator's voice ships inside the APK, so the first book reads without a download. The archive is the
// same one the app downloads the other voices from (VoiceModel.downloadUrl), pinned by its hash so every build
// bundles the same bytes. The pronunciation data goes in whole: the Portuguese dictionary hands words to other
// languages' dictionaries, and espeak-ng crashes on Android when one of them is missing.
val narratorArchive = "vits-piper-pt_BR-faber-medium.tar.bz2"
val narratorArchiveSha256 = "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83"

androidComponents {
    onVariants { variant ->
        val bundle = tasks.register<BundleNarratorVoice>("bundle${variant.name.replaceFirstChar(Char::uppercase)}NarratorVoice") {
            url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$narratorArchive")
            sha256.set(narratorArchiveSha256)
            voiceId.set("faber")
            archive.set(rootProject.layout.projectDirectory.file(".gradle/voices/$narratorArchive"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleNarratorVoice::outputDir)
    }
}

// Writes assets/tts/<voice>/model.onnx, assets/tts/<voice>/tokens.txt and assets/tts/espeak-ng-data/.
abstract class BundleNarratorVoice : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val voiceId: Property<String>
    // A download cache outside build/, so a clean build doesn't fetch 67 MB again.
    @get:Internal abstract val archive: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun bundle() {
        val file = archive.get().asFile
        if (!file.isFile || sha256Of(file) != sha256.get()) {
            file.parentFile.mkdirs()
            val partial = File(file.path + ".part")
            logger.lifecycle("Downloading ${url.get()}")
            URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = sha256Of(partial)
            if (actual != sha256.get()) {
                partial.delete()
                throw GradleException("${file.name} has SHA-256 $actual, expected ${sha256.get()}")
            }
            partial.renameTo(file.also { it.delete() })
        }
        val voice = voiceId.get()
        fileSystem.sync {
            // The .tar.bz2 extension tells Gradle how to unpack it.
            from(archives.tarTree(file))
            into(outputDir)
            includeEmptyDirs = false
            eachFile {
                val inside = relativePath.segments.drop(1)
                val target = when {
                    inside.firstOrNull() == "espeak-ng-data" -> listOf("tts") + inside
                    inside.size == 1 && name.endsWith(".onnx") -> listOf("tts", voice, "model.onnx")
                    inside.size == 1 && name == "tokens.txt" -> listOf("tts", voice, "tokens.txt")
                    else -> null
                }
                if (target == null) exclude() else relativePath = RelativePath(true, *target.toTypedArray())
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
