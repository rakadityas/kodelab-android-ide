import java.util.Properties

// Release signing. The keystore and its passwords live outside git (see
// .gitignore); a clone without keystore.properties still builds every other
// variant, and only assembleRelease comes out unsigned.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = signingProps.getProperty("storeFile") != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.kodelab.ide"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kodelab.ide"
        minSdk = 26
        // targetSdk is intentionally 28: from API 29 the platform forbids
        // execve()/mmap(PROT_EXEC) of files in app storage (W^X), which breaks
        // proot and its ptrace loader — i.e. the whole "install anything" Linux
        // terminal (REQ 5). Targeting 28 keeps the Termux-style sandbox working
        // (verified path). Distribution is F-Droid / direct APK, not Play, so the
        // "built for an older Android" store warning doesn't apply — see
        // docs/architecture.md and the README status table.
        targetSdk = 28
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    lint {
        // targetSdk 28 is a hard requirement, not neglect — see the comment on
        // targetSdk above (W^X from API 29 breaks proot). This check is fatal
        // by default and would fail every release build for that one choice.
        disable += "ExpiredTargetSdkVersion"
    }

    ndkVersion = "26.3.11579264"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // for the version shown at the bottom of Settings → About
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Store .so files uncompressed and page-aligned so a 16 KB-page device
        // can map them straight out of the APK (see CMakeLists for the matching
        // linker flags). This is AGP's default, pinned here so it stays true.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Apache-2.0 / public domain — used to unpack the runtime-downloaded
    // sandbox artifacts (.deb = ar + tar.xz, rootfs = tar.gz). See NOTICE.
    implementation(libs.commons.compress)
    implementation(libs.xz)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // Real org.json on the JVM test classpath (android.jar only stubs it), so
    // the theme-import parser can be unit-tested off-device.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
}

/**
 * The editor web app lives in src/main/assets/webapp (first-party, committed).
 * Its third-party deps (Monaco + xterm.js, both MIT) are vendored into
 * assets/webapp/vendor by `scripts/build-web.sh` — fetched from npm, served from
 * the app's own origin at runtime, never from a CDN. That folder is git-ignored.
 */
tasks.named("preBuild") {
    doFirst {
        val vendor = file("src/main/assets/webapp/vendor/monaco")
        if (!vendor.exists()) {
            logger.warn("Kodelab: Monaco not vendored — run scripts/build-web.sh. " +
                "Editor will use the textarea fallback.")
        }
    }
}
