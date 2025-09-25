plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.google.protobuf:protobuf-java:4.31.1")
}

kotlin {}

sourceSets.main {
    java.srcDir("generated/java")
    resources.srcDir("generated/resources")
}
