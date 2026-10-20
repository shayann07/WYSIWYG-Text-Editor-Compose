plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("quality-conventions")
}
android {
    namespace = "dev.shayxo.composedoc.androidsample"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.shayxo.composedoc.androidsample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-integration"
        testInstrumentationRunner = "dev.shayxo.composedoc.androidsample.AdapterInstrumentation"
    }
    buildFeatures { compose = true }
    providers.gradleProperty("composedocDebugKeystore").orNull?.let { path ->
        signingConfigs.getByName("debug").storeFile = file(path)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
}
