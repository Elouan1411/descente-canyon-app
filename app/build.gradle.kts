import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

val versionProperties = Properties().apply {
    val versionPropertiesFile = rootProject.file("version.properties")
    if (versionPropertiesFile.exists()) {
        versionPropertiesFile.inputStream().use(::load)
    }
}

fun localPropertyOrEnv(propertyName: String, envName: String): String? {
    val propertyValue = localProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
    return propertyValue ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
}

val releaseKeystoreFile = localPropertyOrEnv("releaseKeystoreFile", "RELEASE_KEYSTORE_FILE")
val releaseStorePassword = localPropertyOrEnv("releaseStorePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localPropertyOrEnv("releaseKeyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = localPropertyOrEnv("releaseKeyPassword", "RELEASE_KEY_PASSWORD")
val playServiceAccountFile = localPropertyOrEnv("playServiceAccountFile", "PLAY_SERVICE_ACCOUNT_FILE")
val fileVersionCode = versionProperties.getProperty("VERSION_CODE")?.toIntOrNull() ?: 1
val fileVersionName = versionProperties.getProperty("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"
val ciVersionCode = providers.gradleProperty("ciVersionCode").orNull?.toIntOrNull() ?: fileVersionCode
val ciVersionName = providers.gradleProperty("ciVersionName").orNull ?: fileVersionName
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "fr.descentecanyon.app"
    compileSdk = 35

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.addAll(
                listOf(
                    "src/main/assets",
                    "../offline-data/full/room-import",
                    "../modele_statistique",
                )
            )
        }
    }

    defaultConfig {
        applicationId = "fr.descentecanyon.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName

        testInstrumentationRunner = "fr.descentecanyon.app.e2e.runner.HiltTestRunner"
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("minifiedDebug") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            matchingFallbacks += listOf("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }
}

play {
    defaultToAppBundles.set(true)
    track.set(providers.gradleProperty("playTrack").orElse("internal"))

    if (!playServiceAccountFile.isNullOrBlank()) {
        serviceAccountCredentials.set(layout.projectDirectory.file(playServiceAccountFile))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room (local database - offline storage)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (preferences)
    implementation(libs.androidx.datastore.preferences)

    // Security (encrypted credential storage)
    implementation(libs.androidx.security.crypto)

    // Hilt (dependency injection)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // JSoup (HTML parsing)
    implementation(libs.jsoup)

    // Ktor (HTTP client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // On-device ML inference
    implementation(libs.onnxruntime.android)

    // MapLibre (offline maps)
    implementation(libs.maplibre)
    implementation(libs.play.services.location)

    // Coil (image loading with caching)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
}
