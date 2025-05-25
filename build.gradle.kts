plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.0.21"
    id("com.google.devtools.ksp") version "2.1.20-1.0.32"
    idea
}

ksp { arg("verbose", "true") }

group = "io.github.footermandev.tritium"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
dependencies {
    // Kotlin standard library
    implementation(libs.kotlin.stdlib)

    // KSP
    ksp(libs.autoservice.ksp)
    implementation(libs.autoservice.annotations)
    compileOnly(libs.koin)
    implementation(libs.koin.slf4j)
    implementation(libs.koin.annotations)

    // FlatLaf
    implementation(libs.flatlaf)
    implementation(libs.flatlaf.extras)
    implementation(libs.jsvg)

    // Ktor
    val ktor = libs.ktor
    implementation(ktor.client.core)
    implementation(ktor.client.cio)
    implementation(ktor.client.auth)
    implementation(ktor.client.json)
    implementation(ktor.client.logging)
    implementation(ktor.client.content.negotiation)
    implementation(ktor.client.serialization)
    implementation(ktor.serialization.kotlinx.json)

    // Toml serialization
    val kToml = libs.ktoml
    implementation(kToml.core)
    implementation(kToml.file)

    // MSAL4j
    implementation(libs.msal4j)

    // Logback
    implementation(libs.logback.classic)
    implementation(libs.kotlin.reflect)
}

sourceSets["main"].java.srcDirs("src/main/kotlin")

idea {
    module {
        sourceDirs = sourceDirs + file("build/generated/ksp/main/kotlin")
        generatedSourceDirs = generatedSourceDirs + file("build/generated/ksp/main/kotlin")
    }
}