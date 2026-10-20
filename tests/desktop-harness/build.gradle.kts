import java.util.zip.ZipFile

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

tasks.register("exportVerificationRuntime") {
    dependsOn(":tests:desktop-harness:jar")
    dependsOn(listOf("model", "domain", "data", "layout", "ui").map { ":core:$it:jvmJar" })
    dependsOn(":apps:desktop-workspace:jar")
    doLast {
        val destination = providers.gradleProperty("verificationOutput").orNull
            ?.let(rootProject::file) ?: rootProject.layout.buildDirectory.dir("verification/runtime").get().asFile
        require(!destination.exists()) { "Use a new verification output directory: $destination" }
        val classes = destination.resolve("classes").apply { mkdirs() }
        val libraries = destination.resolve("lib").apply { mkdirs() }
        val runtime = configurations.getByName("runtimeClasspath")
        val projectArtifacts = runtime.incoming.artifactView {
            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
        }.files.files + tasks.named<Jar>("jar").get().archiveFile.get().asFile
        projectArtifacts.forEach { artifact ->
            ZipFile(artifact).use { zip ->
                zip.entries().asSequence().filter {
                    !it.isDirectory && it.name != "META-INF/MANIFEST.MF"
                }.forEach { entry ->
                    val target = classes.resolve(entry.name).canonicalFile
                    require(target.toPath().startsWith(classes.canonicalFile.toPath()))
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    require(!target.exists() || target.readBytes().contentEquals(bytes)) {
                        "Conflicting runtime entry: ${entry.name}"
                    }
                    target.parentFile.mkdirs()
                    target.writeBytes(bytes)
                }
            }
        }
        runtime.incoming.artifactView {
            componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        }.artifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
            artifact.file.copyTo(libraries.resolve("${id.group}.${artifact.file.name}"))
        }
        println("Exported identified Gradle runtime to $destination")
    }
}
