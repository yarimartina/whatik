import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Numero di build della CI (-PbuildNumber=N), mostrato nel versionName.
val buildNumber: String = (project.findProperty("buildNumber") as String?) ?: "local"

// Screenshot JVM delle schermate (Roborazzi + Robolectric, test in com.whatik.screenshots).
// Restano fuori da `testDebugUnitTest` (quindi dalla CI): si attivano solo invocando uno dei task di Roborazzi
//   ./gradlew --no-daemon recordRoborazziDebug     salva i PNG in app/build/outputs/roborazzi/
//   ./gradlew --no-daemon compareRoborazziDebug    rifà gli scatti e li confronta con quelli salvati (aggiunge *_compare.png)
//   ./gradlew --no-daemon verifyRoborazziDebug     come compare, ma fallisce se cambia qualcosa
// oppure con -Pscreenshots (testDebugUnitTest esegue allora SOLO i test degli screenshot).
val screenshotsEnabled: Boolean = project.hasProperty("screenshots") ||
    gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
        .any { it.startsWith("recordRoborazzi") || it.startsWith("compareRoborazzi") || it.startsWith("verifyRoborazzi") }

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

        // L'app esiste solo in italiano e inglese: le altre lingue arrivano dalle librerie androidx (92 configurazioni
        // in resources.arsc) e pesano 187 KB nell'APK release. Da AGP 8.8 si scrive androidResources { localeFilters += ... }.
        resourceConfigurations += listOf("it", "en")
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

    testOptions {
        unitTests {
            // Robolectric legge risorse e manifest reali (servono solo ai test degli screenshot)
            isIncludeAndroidResources = true
            all { test ->
                if (screenshotsEnabled) {
                    test.include("**/screenshots/**")
                    test.maxHeapSize = "2g"
                    // Facoltativo: mirror di Maven Central da cui Robolectric scarica gli android-all
                    // (-Probolectric.dependency.repo.url=<url>), utile se repo1.maven.org risponde 429.
                    (project.findProperty("robolectric.dependency.repo.url") as String?)
                        ?.let { url -> test.systemProperty("robolectric.dependency.repo.url", url) }
                } else {
                    test.exclude("**/screenshots/**")
                }
            }
        }
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
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
