plugins {
    kotlin("multiplatform")
}
group = "org.anime_game_servers.multi_proto"
version = "0.1"

kotlin {
    jvmToolchain(17)
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation("com.google.devtools.ksp:symbol-processing-api:2.2.20-2.0.3")
            }
        }
        val jvmTest by getting
    }
}
