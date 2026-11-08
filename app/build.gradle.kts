import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Signing config is read from keystore.properties when present so that the
// same APK identity can be rebuilt locally. Release stays unsigned when these
// properties are absent; publishing a debug-signed update would strand users.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.aura.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.villamediana.launcher"
        minSdk = 26
        targetSdk = 36
        // Must increase on every release. Android refuses to install an APK
        // whose versionCode is lower than the installed one, and the whole
        // point of matching applicationId + signing key is that a new APK
        // updates in place, keeping all settings.
        versionCode = 6
        versionName = "1.2.3"

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        vectorDrawables { useSupportLibrary = true }
        androidResources.localeFilters += listOf("en", "pt-rBR", "es")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Request every supported scheme. AGP may omit v1 because the
                // minimum is API 26 (which supports v2); v2 and v3 are the
                // schemes required by every device Aura supports.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never create something called "release" with the debug key.
            // Such an APK installs on a fresh device but can never update the
            // real app, which turns a missing local secret into a broken
            // public release. With no release key this remains unsigned and
            // the release script's expected signed APK check fails safely.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // The checked-in AVD is x86_64. Keep release arm64-only, but make
            // the development APK installable in the emulator used by this repo.
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Robolectric loads a full android.jar per test class and the
            // wallpaper framing tests allocate real Matrix work on top of it.
            // At Gradle's default heap the suite started dying with an
            // OutOfMemoryError in whichever test happened to run last, which
            // reads as a broken feature and is nothing of the sort.
            all {
                it.maxHeapSize = "2g"
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
        // Native libraries compressed, extracted once at install (the map's).
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        // A compatibility or manifest error must stop a build instead of being
        // buried in an HTML report beside a successfully produced APK.
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Sideloaded apps never get Play's cloud profiles, so Compose ran
    // interpreted until the phone got round to compiling it — days of jank.
    // This installs the libraries' baseline profiles (and ours, in
    // src/main/baseline-prof.txt) on first launch.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.exifinterface)

    implementation(libs.okhttp)
    // The map widget: vector tiles, 3D buildings. See ui/home/MapWidget.kt.
    implementation(libs.maplibre)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // The parser and the search index are plain Kotlin, but they sit in a module
    // that touches the framework, so tests run under Robolectric to get a real
    // android.jar rather than "not mocked" stubs.
    testImplementation(libs.robolectric)
    // Compose rendered in Robolectric's native graphics.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    // ApplicationProvider, for tests that need a real Context.
    testImplementation(libs.androidx.test.core)
}

