plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // npm publishing disabled for now: broken locally on Windows (Kotlin/npm-publish #187), registry
    // configured later. The ESM package + .d.mts come from the Kotlin/JS plugin (jsNodeProductionLibrary
    // Distribution), so this is only needed to run publishJsPackageToDefaultRegistry from CI. Re-enable then.
    // kotlin("npm-publish") version "3.7.0"
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
        useEsModules()
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
        compilerOptions {
            target.set("es2015") // ES6+ output
            freeCompilerArgs.add("-Xes-long-as-bigint") // Long -> TS bigint; es2015 alone is insufficient
        }
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
                implementation(npm("protobufjs", "8.6.0"))
                implementation(npm("long", "5.3.2")) // enables exact 64-bit via protobuf.util.Long
            }
        }
        jvmMain {
            dependencies {
                compileOnly(project(":gi"))
                compileOnly(project(":base"))
                compileOnly("org.slf4j:slf4j-api:1.7.36")
                compileOnly("io.github.oshai:kotlin-logging-jvm:7.0.6")
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

// npm publishing (ESM library). Re-enable together with the kotlin("npm-publish") plugin above when
// publishing from CI. Registry URL/token via Gradle properties (npm.registry.url / npm.auth.token).
/*
npmPublish {
    registries {
        register("default") {
            uri.set(uri(providers.gradleProperty("npm.registry.url").getOrElse("https://registry.npmjs.org")))
            authToken.set(providers.gradleProperty("npm.auth.token").getOrElse(""))
        }
    }
}
*/
