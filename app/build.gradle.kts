import java.util.Properties




plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// HONEY RELEASE SIGNING: the release keystore + its passwords live in
// keystore.properties (committed) so CI can produce SIGNED GitHub Releases
// without configuring secrets. The repo is PRIVATE — treat any leak of this
// repository as a signing-material leak. Environment variables still
// override the properties file when present.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseKeystorePath: String? =
    System.getenv("NARCIC_TUB_KEYSTORE_PATH") ?: keystoreProperties.getProperty("storeFile")
val releaseKeystorePassword: String? =
    System.getenv("NARCIC_TUB_KEYSTORE_PASSWORD") ?: keystoreProperties.getProperty("storePassword")
val releaseKeyAlias: String? =
    System.getenv("NARCIC_TUB_KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
val releaseKeyPassword: String? =
    System.getenv("NARCIC_TUB_KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
val hasReleaseSigning: Boolean =
    listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { !it.isNullOrBlank() }

if (hasReleaseSigning) {
    println("NarcicTub release signing: configured.")
} else {
    println("NarcicTub release signing: NOT configured — release artifacts will be UNSIGNED.")
}

android {
    namespace = "com.narcictub.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.narcictub.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // yt-dlp + Python + ffmpeg ship as native code per ABI. Dropping the
        // x86 (32-bit) emulator ABI keeps the APK from growing further.
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    signingConfigs {
        // PHASE 16: created only when ALL release credentials are present
        // (see the environment-variable block above the android {} section).
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    // ریلز با APK جدا برای هر پردازنده + یک universal — برای صفحه Releases گیتهاب
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            // PHASE 16: resource shrinking is safe here — the app performs no
            // dynamic resource lookups (no getIdentifier usage, audited).
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // PHASE 16: the env-injected signing config is attached when
            // present; otherwise the release build stays UNSIGNED (default)
            // — it is never silently debug-signed.
            signingConfig = signingConfigs.findByName("release")
        }
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // youtubedl-android launches Python / yt-dlp / ffmpeg as real
        // executables, so the native libraries must be extracted to disk
        // (the Gradle equivalent of android:extractNativeLibs="true").
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // yt-dlp for Android: YouTube / Instagram extraction + downloading.
    // Bundles Python + yt-dlp (GPL-3.0 — see README "Licensing") and ffmpeg
    // (needed to merge separate video + audio streams).
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
