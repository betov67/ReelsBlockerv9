import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // No separate Kotlin plugin here: AGP 9.0+ bundles Kotlin support
    // directly (see https://developer.android.com/build/migrate-to-built-in-kotlin).
    // Applying org.jetbrains.kotlin.android on top of that is now a fatal
    // "no longer required" build error, not just a deprecation warning.
}

android {
    namespace = "com.reelsblocker.app"
    // API 37 only ships as minor-versioned SDK packages right now
    // (platforms;android-37.0/.1/.2, no plain "android-37"), which Gradle's
    // integer compileSdk can't address. 36 is the latest plain, unambiguous
    // platform package, and nothing in this app needs anything from 37.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reelsblocker.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "12.0"
    }

    buildTypes {
        release {
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Intentionally dependency-free. ReelsBlocker only needs the Android
    // framework itself: AccessibilityService/AccessibilityNodeInfo, and the
    // Notification/NotificationChannel APIs that have been part of the
    // platform since API 26 (our minSdk). Keeping the dependency graph at
    // zero third-party artifacts means there is nothing to version-skew
    // against future Gradle/AGP/Kotlin releases, and the APK stays tiny.
}
