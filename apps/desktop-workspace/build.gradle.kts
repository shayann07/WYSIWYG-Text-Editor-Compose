plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    id("desktop-application-conventions")
    id("quality-conventions")
}
kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir("src/jvmMain/kotlin") }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:layout"))
    implementation(project(":core:ui"))
    implementation(libs.compose.foundation)
    runtimeOnly(
        when {
            System.getProperty("os.name").startsWith("Windows") -> libs.skiko.runtime.windows
            System.getProperty("os.name").startsWith("Mac") -> libs.skiko.runtime.macos
            else -> libs.skiko.runtime.linux
        },
    )
}
application { mainClass.set("dev.shayxo.composedoc.workspace.MainKt") }
tasks.withType<JavaExec>().configureEach { workingDir(rootProject.projectDir) }
