import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
    // npm publishing disabled for now: broken locally on Windows (Kotlin/npm-publish #187), registry
    // configured later. The ESM package + .d.mts come from the Kotlin/JS plugin (jsNodeProductionLibrary
    // Distribution), so this is only needed to run publishJsPackageToDefaultRegistry from CI. Re-enable then.
    // kotlin("npm-publish") version "3.7.0"
}

val isDynamicRuntime = providers
    .gradleProperty("org.anime_game_servers.dynamicRuntime")
    .orElse("false")

// until the rework for proto handling is done, we use this to compile packages for specific game versions
group = "org.anime_game_servers.multi_proto"
version = if (isDynamicRuntime.get() != "true") {
    val protoVersion = 32
    libs.versions.anime.game.multi.proto.get()+".$protoVersion"
}
else {
    "0.3.0-SNAPSHOT"
}

// Optional npm scope for the published JS package: -Pnpm.scope=my-org (or in gradle.properties) ->
// "@my-org/multi-proto-gi". Leave unset for an unscoped name. The leading "@" is optional.
val npmScope = providers.gradleProperty("npm.scope").orNull?.removePrefix("@")

ksp {
    arg("basePacket", "org.anime_game_servers.multi_proto.gi")
}

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
        val commonMain by getting {
            dependencies {
                api(project(":base"))
                api(libs.bundles.common.ags.gi)
                // pbandk is only used by the static runtime (the excluded protos/** sources);
                // dynamic mode delegates to ProtoRuntimeProvider, so keep it off the JS bundle.
                if (isDynamicRuntime.get() != "true") {
                    implementation(libs.bundles.proto.parsing)
                }
            }
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin/")
            // protos/** are the checked-in static-runtime sources (gitignored); in dynamic mode we use
            // the KSP-generated models + descriptor runtime instead, so exclude them (and pbandk above).
            if (isDynamicRuntime.get() == "true") {
                kotlin.exclude("protos/**/*")
            }
            sourceSets.configureEach {
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            getTasksByName("jvmJar", true).forEach{
                it.setProperty("zip64", true)
            }
            dependencies {
                compileOnly("org.slf4j:slf4j-api:1.7.36")
                implementation("io.github.oshai:kotlin-logging:7.0.6")
            }
        }
        val jvmTest by getting
        val jsMain by getting
        val jsTest by getting
    }
}
dependencies {
    add("kspCommonMainMetadata", project(":processor"))
    //add("kspJvm", project(":processor"))
    //add("kspJs", project(":processor"))
}
tasks {
    sourcesJar{
        dependsOn("kspCommonMainKotlinMetadata")
    }
    getTasksByName("jvmSourcesJar", false).forEach {
        it.dependsOn("kspCommonMainKotlinMetadata")
    }
    getTasksByName("jsSourcesJar", false).forEach {
        it.dependsOn("kspCommonMainKotlinMetadata")
    }
    getTasksByName("nativeSourcesJar", false).forEach {
        it.dependsOn("kspCommonMainKotlinMetadata")
    }
    withType<KotlinCompilationTask<*>> {
        if (name != "kspCommonMainKotlinMetadata")
            dependsOn("kspCommonMainKotlinMetadata")
    }
}

// The bundled packet-id CSVs (commonMain resources) are only consumed by the static runtime; the dynamic
// runtime reads packets.csv from config/<version>/ at runtime. Keep them out of every packaged artifact:
// the *ProcessResources tasks (JVM jar, metadata) and the JS library distribution are all Copy tasks.
if (isDynamicRuntime.get() == "true") {
    tasks.withType<Copy>().configureEach {
        exclude("package_ids/**")
    }
}

ksp {
    arg("isDynamicRuntime", isDynamicRuntime.get())
    // Opt-in: emit the pure-JS gi data package (index.d.ts + registry.js) for the plain-object JS approach.
    // -Porg.anime_game_servers.emitTypescript=true
    arg("emitTypescript", providers.gradleProperty("org.anime_game_servers.emitTypescript").orElse("false").get())
}

// Assemble the pure-JS gi data package (generated index.d.ts + registry.js + package.json) for the
// plain-object approach. Registered only when emitTypescript is on (KSP then emits the files):
//   ./gradlew :gi:assembleJsData -Porg.anime_game_servers.emitTypescript=true [-Pnpm.scope=my-org]
if (providers.gradleProperty("org.anime_game_servers.emitTypescript").orNull == "true") {
    val jsDataDir = layout.buildDirectory.dir("dist/js-data")
    val pkgName = (if (npmScope != null) "@$npmScope/" else "") + "multi-proto-gi"
    tasks.register<Copy>("assembleJsData") {
        group = "distribution"
        description = "Assemble the pure-JS gi data package: index.d.ts + registry.js + package.json."
        dependsOn("kspCommonMainKotlinMetadata")
        from(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/resources")) {
            include("index.d.ts", "registry.js")
        }
        into(jsDataDir)
        doLast {
            jsDataDir.get().file("package.json").asFile.writeText(
                """
                {
                  "name": "$pkgName",
                  "version": "${project.version}",
                  "type": "module",
                  "main": "registry.js",
                  "types": "index.d.ts"
                }
                """.trimIndent() + "\n"
            )
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            artifactId = "gi-multi-proto"
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
