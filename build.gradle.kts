
import com.github.jengelman.gradle.plugins.shadow.transformers.XmlAppendingTransformer
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    idea
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

ksp { arg("verbose", "true") }

group = "io.github.footermandev.tritium"
version = "1.0-SNAPSHOT"

val os: OperatingSystem = OperatingSystem.current()
val arch: String = System.getProperty("os.arch").lowercase()
val isArm64: Boolean = arch.contains("aarch64") || arch.contains("arm64")
val qtOs = when {
    os.isWindows -> if (isArm64) "windows-arm64" else "windows-x64"
    os.isMacOsX -> "macos"
    os.isLinux -> if (isArm64) "linux-arm64" else "linux-x64"
    else -> "unknown"
}

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.hocon)
    implementation(libs.kotlinx.coroutines.core)
    implementation("io.ktor:ktor-client-cio-jvm:3.3.1")

    // KSP
    ksp(libs.autoservice.ksp)
    implementation(libs.autoservice.annotations)
    compileOnly(libs.koin)
    implementation(libs.koin.slf4j)
    implementation(libs.koin.annotations)

    // QtJambi
    implementation(libs.qtjambi)
    implementation(libs.qtjambi.svg)
    if(qtOs != "unknown") {
        runtimeOnly("io.qtjambi:qtjambi-native-$qtOs:${libs.versions.qt.get()}")
        runtimeOnly("io.qtjambi:qtjambi-svg-native-$qtOs:${libs.versions.qt.get()}")
    }

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

    implementation(ktor.server.core)
    implementation(ktor.server.netty)

    // MSAL4j
    implementation(libs.msal4j)

    // Logback
    implementation(libs.logback.classic)
    implementation(libs.kotlin.reflect)

    // LSP
    implementation(libs.lsp4j)

    // JNA
    implementation(libs.jna)
    implementation(libs.jna.platform)

    /* Test */

    testImplementation(libs.bundles.test)
}

sourceSets["main"].java.srcDirs("src/main/kotlin")

idea {
    module {
        sourceDirs = sourceDirs + file("build/generated/ksp/main/kotlin")
        generatedSourceDirs = generatedSourceDirs + file("build/generated/ksp/main/kotlin")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.github.footermandev.tritium.Main"
    }
}

tasks.shadowJar {
    archiveBaseName.set("tritium")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "io.github.footermandev.tritium.Main"
    }
    transform(XmlAppendingTransformer::class.java){
        resource = "META-INF/qtjambi-deployment.xml"
    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xcontext-parameters"))
}
