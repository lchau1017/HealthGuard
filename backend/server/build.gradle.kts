import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.healthguard.server.MainKt")
}

// Let `run` work straight from the IDE run button: load KEY=VALUE pairs from
// the git-ignored backend/server/.env when they aren't already set in the
// environment. Done in doFirst (execution time) so a changed .env is always
// picked up.
tasks.named<JavaExec>("run") {
    doFirst {
        val envFile = layout.projectDirectory.file(".env").asFile
        if (envFile.exists()) {
            envFile.readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
                .forEach { line ->
                    val key = line.substringBefore("=").trim()
                    val value = line.substringAfter("=").trim()
                    if (System.getenv(key) == null) environment(key, value)
                }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}
