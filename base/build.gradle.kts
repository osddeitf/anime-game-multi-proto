plugins {
    kotlin("multiplatform")
}

group = "org.anime_game_servers.multi_proto"
version = libs.versions.anime.game.multi.proto.get()

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
        val commonMain by getting {
            dependencies{
                api(libs.bundles.common.ags.base)
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
        }
        val jvmTest by getting
        val jsMain by getting
        val jsTest by getting
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            artifactId = "base"
        }
    }
}
