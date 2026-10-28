plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds can be versioned from CI without editing this file.
// Example: -PAR_HORIZON_VERSION_NAME=0.2.0 -PAR_HORIZON_VERSION_CODE=42
val releaseVersionName =
    providers.gradleProperty("AR_HORIZON_VERSION_NAME").orNull?.takeIf { it.isNotBlank() }
val releaseVersionCode =
    providers.gradleProperty("AR_HORIZON_VERSION_CODE").orNull?.toIntOrNull()

// A production keystore is optional. Without it, release builds fall back to the
// debug keystore only when -PallowDebugSigning=true is passed explicitly.
val releaseStorePath = System.getenv("AR_HORIZON_KEYSTORE")
val releaseStorePassword = System.getenv("AR_HORIZON_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("AR_HORIZON_KEY_ALIAS")
val releaseKeyPassword = System.getenv("AR_HORIZON_KEY_PASSWORD")
val releaseStoreType =
    System.getenv("AR_HORIZON_KEYSTORE_TYPE")?.takeIf { it.isNotBlank() }
val releaseSigningConfigured = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val allowDebugSigning =
    providers.gradleProperty("allowDebugSigning").orNull == "true"

android {
    namespace = "app.vantage"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.arhorizon"
        minSdk = 28
        targetSdk = 36
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.1.0"
        // Empty on purpose: the app is standalone. Set a catalogue base only if
        // you run the optional pack service or host a static catalogue.
        buildConfigField("String", "PACK_BASE_URL", "\"\"")
        ndk {
            // ARCore-certified hardware in v1 is 64-bit; keeps the APK lean.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                if (releaseStoreType != null) storeType = releaseStoreType
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    androidResources {
        noCompress.add("sqlite")
        noCompress.add("idx")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?: if (allowDebugSigning) signingConfigs.getByName("debug") else null
        }
    }

    lint {
        // local.properties is machine-specific and managed by Gradle/Studio.
        disable += "PropertyEscape"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.ar:core:1.57.0")
    implementation("io.airlift:aircompressor:0.27")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
