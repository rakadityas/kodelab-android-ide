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
        minSdk = 29
        // targetSdk 28 on purpose (the Termux approach): apps targeting 29+ are
        // denied execve() on files in app storage, which would make the runtime-
        // downloaded proot/rootfs sandbox impossible. Distribution channel is
        // F-Droid / direct APK (see docs/ROADMAP.md "Play Store policy" risk).
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0-m0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
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
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
