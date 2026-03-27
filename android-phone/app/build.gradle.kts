import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import org.gradle.api.GradleException

fun releaseValue(name: String): String? =
    providers.environmentVariable(name).orNull
        ?: providers.gradleProperty(name).orNull

val ciBuild = providers.environmentVariable("CI").orNull?.equals("true", ignoreCase = true) == true
val releaseKeystorePath = releaseValue("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = releaseValue("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseValue("ANDROID_KEY_ALIAS")
val releaseKeyPassword = releaseValue("ANDROID_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val releaseVersionNameOverride = releaseValue("ROKID_LYRICS_VERSION_NAME")
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
val releaseVersionCodeOverride = releaseValue("ROKID_LYRICS_VERSION_CODE")
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
val releaseTasksRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

if (ciBuild && releaseTasksRequested && !releaseSigningReady) {
    throw GradleException(
        "Release signing is not configured. Provide ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, " +
            "ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD."
    )
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.lyrics.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokid.lyrics.phone"
        minSdk = 28
        targetSdk = 34
        versionCode = releaseVersionCodeOverride ?: 1
        versionName = releaseVersionNameOverride ?: "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../../shared-contracts/src/main/java")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local release builds can fall back to the debug keystore for validation,
            // but CI tag builds must provide a real release keystore via env/Gradle properties.
            signingConfig =
                if (releaseSigningReady) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

android {
    applicationVariants.all {
        outputs.all {
            (this as ApkVariantOutputImpl).outputFileName = "lyrics-phone-${name}.apk"
        }
    }
}
