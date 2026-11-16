plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ilgax.utils.speaker"
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(project(":core"))
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
}
