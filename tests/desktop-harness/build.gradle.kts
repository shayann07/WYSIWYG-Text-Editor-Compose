plugins {
    id("desktop-application-conventions")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    id("quality-conventions")
}
application { mainClass.set("editor.MainKt") }
kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir("src/jvmMain/kotlin") }
}
sourceSets.main { resources.srcDir("src/jvmMain/resources") }
dependencies {
    implementation(project(":apps:desktop-workspace"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:layout"))
    implementation(project(":core:ui"))
    implementation(libs.compose.foundation)
}
