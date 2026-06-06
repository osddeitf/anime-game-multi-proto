
pluginManagement {
    plugins {
        //id("com.google.devtools.ksp") version "2.3.3"
        //kotlin("jvm") version "1.9.22" apply false
        //kotlin("multiplatform") version "2.2.21" apply false
    }
    repositories {
        gradlePluginPortal()
        google()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "multi-proto"

include(":processor")
include(":base")
include(":gi")
include(":runtime")

project(":processor").projectDir = File("codeGenerator/processor")
