import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

android {
    namespace = "com.gios.brightcollect"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.brightcollect"
        // Matches the rest of the family. The LPIII is on Android 14; nothing here needs to
        // run lower, and holding the floor at 33 keeps the Compose and CameraX code the same
        // shape as Roll's.
        minSdk = 33
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml.
        //
        // Only the major.minor here is read — the workflow takes them and appends the run number
        // as the patch. So a release whose notes announce a new minor has to have this bumped
        // first, or the tag says 1.0.2 while RELEASE_NOTES.md says v1.1 and nothing complains.
        versionCode = 1
        versionName = "1.1.0"

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        // The LPIII is arm64 only. It matters more here than in the other apps: ONNX Runtime
        // ships a ~26 MB native library per ABI, so four ABIs would be a 100 MB APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key is a CI secret, decoded to `keystore/brightcollect.jks`, and that path
    // is git-ignored so a local build cannot put one back by accident.
    //
    // A build without the secret still works and still produces an installable APK — signed
    // with the local debug key instead, and it will not update over a release. A build that
    // announces it is not the real one beats one that silently signs itself with a shared key.
    //
    // The fallback has to be the debug config and not `null`. `signingConfig = null` produces
    // an *unsigned* release, which Android will not install at all, and AGP names it
    // `app-release-unsigned.apk` — so every path and glob written for `app-release.apk`
    // quietly refers to a file that is not there.
    val keystoreFile = rootProject.file("keystore/brightcollect.jks")
    val keystorePassword: String = System.getenv("KEYSTORE_PASSWORD") ?: ""
    val canSignRelease = keystoreFile.exists() && keystorePassword.isNotEmpty()

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "brightcollect"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSignRelease) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // The model is already compressed as far as it goes and the APK is large enough without
    // the packer having a run at it. Leaving it uncompressed also means it can be mapped
    // straight out of the APK rather than inflated to a temp file on every cold start.
    androidResources {
        noCompress += "onnx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            // android.jar on the unit-test classpath is a stub whose every method throws
            // "not mocked". The store's index is JSON, so its tests would all die on the
            // first org.json call without this and the real implementation below.
            isReturnDefaultValues = true
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // The wheel, the LPIII key map, shake-to-report and the LightSync backup provider, shared
    // with every other Bright* app rather than pasted into each of them.
    //
    // 1.4.1 and not 1.5.0: v1.5.0 is tagged in BrightCommon but was never published to GitHub
    // Packages, so it resolves to "Could not find com.gios:light-common:1.5.0" — which reads
    // exactly like a credentials problem and is not one. `maven-metadata.xml` on the package
    // repository is the list that matters, not the repository's tags.
    implementation("com.gios:light-common:1.4.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Shake-to-report posts a GitHub issue. The only network this app does, and only ever
    // after you have tapped SEND on a report you wrote yourself.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    /*
     * The cutout.
     *
     * ONNX Runtime rather than ML Kit, and not as a preference. ML Kit's Subject Segmentation
     * is its *unbundled* API — `play-services-mlkit-subject-segmentation`, delivered through
     * Play Services, which LightOS does not have. It would bind and never answer, exactly like
     * the barcode reader Roll had to drop for ZXing. ML Kit's only bundled segmenter is
     * selfie segmentation, which finds people and nothing else, and this app is for objects.
     *
     * ONNX Runtime needs nothing from the platform and reads the model straight out of assets,
     * so `u2netp.onnx` is the whole dependency chain. It costs ~26 MB of native library for
     * arm64; see `abiFilters` above for why that number is not multiplied by four.
     */
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.25.1")

    /*
     * Guessing what the thing is, to prefill its name. The *bundled* labeller — the model is in
     * the APK — for exactly the reason the cutout is not ML Kit: the unbundled artifact
     * (`play-services-mlkit-image-labeling`) is delivered through Play Services, which LightOS
     * does not have. 5.7 MB, 400-odd everyday labels. See cut/Namer.kt for why this rather than
     * an ImageNet model through the ONNX Runtime already here.
     */
    implementation("com.google.mlkit:image-labeling:17.0.9")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The mask arithmetic and the store are deliberately free of Android imports, so they can
    // be tested on the JVM rather than on a phone.
    testImplementation("junit:junit:4.13.2")
    // Shadows android.jar's stubbed org.json so the index round-trip is actually exercised.
    testImplementation("org.json:json:20240303")
}
