plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ilgax.utils.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ilgax.utils.wear"
        minSdk = 31
        targetSdk = 35
        versionCode = 9
        versionName = "0.5.1"
    }

    // Fixed debug key (the one the first sideloaded build was signed with): Utils bundles this APK as an
    // update, and Android only accepts an update signed with the same certificate as the installed app.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/watch-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // The build Utils embeds and ships to the watch: R8-shrunk, same key as debug (update continuity).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    androidResources {
        localeFilters += "en"
    }
    packaging {
        // AGP stores dex uncompressed for minSdk >= 28; deflating it makes the APK that Utils pushes over Wi-Fi ~2.5x smaller.
        dex.useLegacyPackaging = true
        resources {
            excludes += setOf(
                "/META-INF/*.version", "/META-INF/*.kotlin_module", "/META-INF/androidx/**", "/META-INF/com/android/build/gradle/**",
                "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/DebugProbesKt.bin", "/kotlin/**.kotlin_builtins",
                "/kotlin-tooling-metadata.json",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.concurrent:concurrent-futures:1.1.0") // ResolvableFuture for the tile (replaces Guava)
}
