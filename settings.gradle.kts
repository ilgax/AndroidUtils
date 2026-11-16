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
include(":app", ":core", ":speaker", ":hotspot", ":remote", ":math", ":protocol", ":wear")

// Project paths keep their historic names; the folders are app/, wear/ and modules/*.
project(":core").projectDir = file("modules/core")
project(":speaker").projectDir = file("modules/speaker")
project(":hotspot").projectDir = file("modules/hotspot")
project(":remote").projectDir = file("modules/remote")
project(":math").projectDir = file("modules/math")
// Pure-JVM wire protocol shared by the tablet side and the watch app.
project(":protocol").projectDir = file("modules/protocol")
// The Wear OS app; built here to bundle its APK into :remote (see that module's build file).
project(":wear").projectDir = file("wear")
