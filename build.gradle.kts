plugins {
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.binary.compatibility)
}

allprojects {
    group = "dev.shayxo.composedoc"
    version = "0.1.0-SNAPSHOT"
    // Node.js and Yarn resolve through the settings repositories above, not per-project ones.
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>().downloadBaseUrl.set(null as String?)
    }
}

apiValidation {
    ignoredProjects.addAll(listOf("desktop-workspace", "desktop-harness"))
}

val coreModules = listOf("model", "domain", "data", "layout", "ui")
val writeArchitectureModel by tasks.registering {
    val destination = layout.buildDirectory.file("verification/architecture.json")
    outputs.file(destination)
    outputs.upToDateWhen { false }
    doLast {
        val graph = subprojects.associate { project ->
            project.path to project.configurations.flatMap { configuration ->
                configuration.dependencies.withType<ProjectDependency>().map { it.dependencyProject.path }
            }.distinct().filter { it != project.path }.sorted()
        }
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.toJson(graph))
        }
    }
}
tasks.register<Exec>("architectureCheck") {
    dependsOn(writeArchitectureModel)
    commandLine("python", "tooling/architecture/check_boundaries.py")
}
tasks.register("detekt") { dependsOn(coreModules.map { ":core:$it:detekt" }, ":apps:desktop-workspace:detekt", ":tests:desktop-harness:detekt") }
tasks.register("ktlintCheck") { dependsOn(coreModules.map { ":core:$it:ktlintCheck" }, ":apps:desktop-workspace:ktlintCheck", ":tests:desktop-harness:ktlintCheck") }
tasks.named("check") {
    dependsOn("architectureCheck", "detekt", "ktlintCheck")
    dependsOn(coreModules.map { ":core:$it:apiCheck" })
    dependsOn(coreModules.map { ":core:$it:check" })
    dependsOn(":apps:desktop-workspace:check", ":tests:desktop-harness:check")
}

tasks.register("exportVerificationRuntime") {
    dependsOn(":tests:desktop-harness:jar")
    dependsOn(coreModules.map { ":core:$it:jvmJar" })
    dependsOn(":apps:desktop-workspace:jar")
    doLast {
        val destination = providers.gradleProperty("verificationOutput").orNull
            ?.let(::file) ?: layout.buildDirectory.dir("verification/runtime").get().asFile
        require(!destination.exists()) { "Use a new verification output directory: $destination" }
        val classes = destination.resolve("classes").apply { mkdirs() }
        val libraries = destination.resolve("lib").apply { mkdirs() }
        val runtime = project(":tests:desktop-harness").configurations.getByName("runtimeClasspath")
        val projectArtifacts = runtime.incoming.artifactView {
            componentFilter { it is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
        }.files.files + project(":tests:desktop-harness").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        projectArtifacts.forEach { artifact ->
            java.util.zip.ZipFile(artifact).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name != "META-INF/MANIFEST.MF" }.forEach { entry ->
                    val target = classes.resolve(entry.name).canonicalFile
                    require(target.toPath().startsWith(classes.canonicalFile.toPath()))
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    require(!target.exists() || target.readBytes().contentEquals(bytes)) { "Conflicting runtime entry: ${entry.name}" }
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
