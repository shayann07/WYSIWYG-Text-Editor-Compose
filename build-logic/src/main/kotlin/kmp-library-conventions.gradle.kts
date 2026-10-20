
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("quality-conventions")
    id("maven-publish")
}

group = "dev.shayxo.composedoc"
version = "0.1.0-SNAPSHOT"

kotlin {
    android {
        namespace = "dev.shayxo.composedoc.${project.name}"
        compileSdk = 37
        minSdk = 24
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        withHostTestBuilder {}.configure {}
    }
    jvm()
    js {
        nodejs {
            // Mocha's 2 s default is too tight for the 1,093-case Unicode suite on cold CI runners.
            testTask { useMocha { timeout = "30s" } }
        }
    }
    // Apple compilation/qualification is deliberately hosted on macOS.
    if (System.getProperty("os.name").contains("Mac")) {
        iosArm64()
        iosSimulatorArm64()
    }
    jvmToolchain(21)
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

// Verification-only repository. Never publish these snapshots remotely or into normal Maven local.
publishing.repositories.maven {
    name = "ConsumerVerification"
    url = uri(providers.gradleProperty("consumerRepository").getOrElse(
        rootProject.layout.buildDirectory.dir("consumer-artifacts").get().asFile.absolutePath,
    ))
}
tasks.matching { it.name == "publishToMavenLocal" || it.name.endsWith("ToMavenLocal") }.configureEach {
    enabled = false
}
