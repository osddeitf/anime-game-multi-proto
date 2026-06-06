plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "org.anime_game_servers.multi_proto"
version = "0.3.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    js(IR) {
        nodejs()
    }
    mingwX64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":base"))
                implementation(project(":gi"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
            }
        }
        jsMain {
            dependencies {
                implementation(npm("protobufjs", "7.4.0"))
                implementation(npm("long", "5.2.3")) // enables exact 64-bit via protobuf.util.Long
            }
        }
        jvmMain {
            dependencies {
                compileOnly(project(":gi"))
                compileOnly(project(":base"))
                compileOnly("org.slf4j:slf4j-api:1.7.36")
                compileOnly("io.github.oshai:kotlin-logging-jvm:7.0.6")
                implementation("org.anime_game_servers.core:gi:0.2")
                implementation("org.jetbrains.kotlin:kotlin-reflect")
                implementation("com.google.protobuf:protobuf-java:4.31.1")
                implementation("io.netty:netty-buffer:4.2.9.Final")
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            artifactId = "runtime"
        }
    }
}
