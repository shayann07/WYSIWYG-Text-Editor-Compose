plugins {
    id("kmp-library-conventions")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val composeCatalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
kotlin.sourceSets.getByName("commonMain").dependencies {
    implementation(composeCatalog.findLibrary("compose-runtime").get())
}
