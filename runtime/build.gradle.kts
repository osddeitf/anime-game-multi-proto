plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    alias(libs.plugins.kover) // test coverage (JVM run; covers the shared commonMain engine)
    // npm publishing disabled for now: broken locally on Windows (Kotlin/npm-publish #187), registry
    // configured later. The ESM package + .d.mts come from the Kotlin/JS plugin (jsNodeProductionLibrary
    // Distribution), so this is only needed to run publishJsPackageToDefaultRegistry from CI. Re-enable then.
    // kotlin("npm-publish") version "3.7.0"
}

group = "org.anime_game_servers.multi_proto"
version = "0.3.0-SNAPSHOT"

// Optional npm scope for the published JS package: -Pnpm.scope=my-org (or in gradle.properties) ->
// "@my-org/multi-proto-runtime". Leave unset for an unscoped name. The leading "@" is optional.
val npmScope = providers.gradleProperty("npm.scope").orNull?.removePrefix("@")

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
        if (npmScope != null) {
            // Compute the scoped name from the project (don't read-modify-write packageJson.name): the
            // packageJson {} block runs for several package.json files, so a self-referential prepend would
            // double-scope. "${rootProject.name}-${project.name}" is the Kotlin/JS default base name.
            val scopedName = "@$npmScope/${rootProject.name}-${project.name}"
            compilations.named("main") {
                packageJson { name = scopedName }
            }
        }
    }
    mingwX64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain {
            dependencies {
                // base only — runtime no longer depends on gi (consumer bridges via ProtoModelRegistry),
                // so gi's model types stay out of runtime's published package/typings.
                implementation(project(":base"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                // real gi models + ProtoModelRegistry.getModels()/getEnums(); test scope only, so gi
                // does NOT re-enter the published runtime artifact.
                implementation(project(":gi"))
            }
        }
        jsMain {
            dependencies {
                implementation(npm("protobufjs", "8.6.0"))
                implementation(npm("long", "5.3.2")) // enables exact 64-bit via protobuf.util.Long
                // No gi dependency: the plain-object approach ships gi as a separate pure-JS data package and
                // passes its registry in as plain data (PlainProtoRuntime), so nothing Kotlin crosses packages.
            }
        }
        jvmMain {
            dependencies {
                implementation("io.github.oshai:kotlin-logging-jvm:7.0.6")
                implementation("com.google.protobuf:protobuf-java:4.31.1")
                implementation("io.netty:netty-buffer:4.2.9.Final")
            }
        }
    }
}

// Tests read the fixtures under <repoRoot>/config/test/ via relative paths; pin the JVM test working
// dir to the repo root so they resolve the same way the app reads config/<version>/ at runtime.
tasks.withType<org.gradle.api.tasks.testing.Test> {
    workingDir = rootProject.projectDir
}

// Coverage of the shared engine. `./gradlew :runtime:koverHtmlReport` (runs jvmTest first) ->
// runtime/build/reports/kover/html/index.html; `:runtime:koverLog` prints a summary line.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "*.runtime.descriptor.*", // ProtoRuntimeImpl + KLoggerEngineLogger (JVM wiring, not under test)
                    "*.NettyProto*",           // JVM buffer (NettyProtoWriter/Reader/Factory); tests use the common ByteArrayProtoBuffer
                    "*.common.HelpersKt",      // JVM-only helpers (CSV stream, etc.)
                )
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
