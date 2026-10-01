import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Numero di build della CI (-PbuildNumber=N), mostrato nel versionName.
val buildNumber: String = (project.findProperty("buildNumber") as String?) ?: "local"

// versionCode crescente nel tempo (minuti dal 1 gennaio 2026), uguale per CI e build locali:
// qualunque build più recente aggiorna quella installata, purché firmata con la stessa chiave.
val computedVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    ?: ((Instant.now().epochSecond - 1_767_225_600L) / 60L).toInt().coerceAtLeast(1)

android {
    namespace = "com.whatik"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whatik"
        minSdk = 26
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Chiave fissa per il sideload: la chiave di debug dei runner CI cambia a ogni build e
        // Android rifiuterebbe gli aggiornamenti. Non e' una chiave da Play Store.
        create("sideload") {
            storeFile = file("keystore/whatik-sideload.jks")
            storePassword = "whatik-sideload"
            keyAlias = "whatik"
            keyPassword = "whatik-sideload"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
