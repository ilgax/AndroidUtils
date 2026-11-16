pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Utils"
include(":app", ":core", ":feature-speaker", ":feature-hotspot", ":feature-remote", ":feature-math", ":protocol", ":wear")

// Project paths keep their historic names; the folders are app/, wear/ and modules/*.
project(":core").projectDir = file("modules/core")
project(":feature-speaker").projectDir = file("modules/speaker")
project(":feature-hotspot").projectDir = file("modules/hotspot")
project(":feature-remote").projectDir = file("modules/remote")
project(":feature-math").projectDir = file("modules/math")
// Pure-JVM wire protocol shared by the tablet side and the watch app.
project(":protocol").projectDir = file("modules/protocol")
// The Wear OS app; built here to bundle its APK into :feature-remote (see that module's build file).
project(":wear").projectDir = file("wear")
