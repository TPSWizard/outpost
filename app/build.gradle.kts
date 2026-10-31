plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.conza.outpost"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.conza.outpost"
        minSdk = 24
        targetSdk = 36
        versionCode = 8
        versionName = "1.2.0"
    }

    // One universal APK (emulators, any device) plus a slim arm64 APK for modern phones.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload-friendly: signed with the debug key until you add a real keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            // We only use libzim (reading + search). libkiwix's server/library layer is ~28 MB per ABI.
            excludes += listOf("**/libkiwix.so", "**/libkiwix_wrapper.so")
            // libzim (Kiwix) and our llama.cpp build both use the NDK r27 shared C++ runtime.
            pickFirsts += "**/libc++_shared.so"
            // Native libraries in jniLibs are prebuilt and already stripped (scripts/build-native.sh).
            keepDebugSymbols += "**/*.so"
            // Compress native libs in the APK (much smaller download; extracted on install).
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.maplibre.android)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.libkiwix) {
        exclude(group = "androidx.databinding")
        exclude(group = "com.getkeepsafe.relinker")
    }

    // PDF text for page-cited search (Apache-2.0). Text extraction only; rendering stays on PdfRenderer.
    implementation(libs.pdfbox.android)
    // Phone-to-phone sharing over Bluetooth/Wi-Fi with no network (Nearby Connections).
    implementation(libs.play.services.nearby)
    testImplementation(libs.junit)
    testImplementation(libs.pdfbox.desktop)
}
