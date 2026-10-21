plugins {
    base
    id("quality-conventions")
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
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

// Kotlin/JS tests run under mocha; force its transitive dev dependencies onto patched
// releases (Dependabot cannot update the Kotlin-generated kotlin-js-store/yarn.lock).
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().apply {
        resolution("serialize-javascript", "7.0.5")
        resolution("diff", "8.0.3")
    }
}

apiValidation {
    ignoredProjects.addAll(listOf("desktop-workspace", "desktop-harness", "android-sample"))
}

val coreModules = listOf("model", "domain", "data", "layout", "ui")
val writeArchitectureModel by tasks.registering {
    val destination = layout.buildDirectory.file("verification/architecture.json")
    outputs.file(destination)
    outputs.upToDateWhen { false }
    doLast {
        val graph = subprojects.associate { project ->
            project.path to project.configurations.flatMap { configuration ->
                configuration.dependencies.withType<ProjectDependency>().map { it.path }
            }.distinct().filter { it != project.path }.sorted()
        }
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.toJson(graph))
        }
    }
}
val architecturePolicySelfTest by tasks.registering(Exec::class) {
    commandLine("python", "-m", "unittest", "discover", "-s", "tooling/architecture", "-p", "test_*.py")
}
val androidEvidenceSelfTest by tasks.registering(Exec::class) {
    commandLine("python", "-m", "unittest", "discover", "-s", "tooling/verification", "-p", "test_android*.py")
}
val androidApiCheck by tasks.registering(Exec::class) {
    dependsOn(
        ":core:data:bundleAndroidMainClassesToCompileJar",
        ":core:layout:bundleAndroidMainClassesToCompileJar",
        ":core:ui:bundleAndroidMainClassesToCompileJar",
    )
    commandLine("python", "tooling/verification/android_api.py")
}

tasks.register<Exec>("architectureCheck") {
    dependsOn(writeArchitectureModel, architecturePolicySelfTest)
    commandLine("python", "tooling/architecture/check_boundaries.py")
}
tasks.named("detekt") {
    dependsOn(
        coreModules.map { ":core:$it:detekt" },
        ":apps:desktop-workspace:detekt",
        ":tests:desktop-harness:detekt",
        ":apps:android-sample:detekt",
    )
}
tasks.named("ktlintCheck") {
    dependsOn(
        coreModules.map {
            ":core:$it:ktlintCheck"
        },
        ":apps:desktop-workspace:ktlintCheck",
        ":tests:desktop-harness:ktlintCheck",
        ":apps:android-sample:ktlintCheck",
    )
}
tasks.named("check") {
    dependsOn("architectureCheck", "detekt", "ktlintCheck", androidEvidenceSelfTest, androidApiCheck)
    dependsOn(coreModules.map { ":core:$it:apiCheck" })
    dependsOn(coreModules.map { ":core:$it:check" })
    dependsOn(
        ":apps:desktop-workspace:check",
        ":tests:desktop-harness:check",
        ":apps:android-sample:check",
        ":apps:android-sample:assembleDebug",
    )
}

tasks.register("exportVerificationRuntime") {
    dependsOn(":tests:desktop-harness:exportVerificationRuntime")
}

// The independent application keeps its own settings; verify its sources without joining the root build.
extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    // Rooted trees retain the same checks without walking machine-local emulator/evidence directories.
    kotlin {
        target(fileTree("apps/independent-consumer/src") { include("**/*.kt") })
        targetExclude(
            fileTree("apps/independent-consumer/src") {
                include("**/generated/**", "**/Unicode16ConformanceTest.kt")
            },
        )
    }
    kotlinGradle {
        target(
            fileTree(rootDir) { include("*.gradle.kts") },
            fileTree("apps/independent-consumer") { include("*.gradle.kts") },
        )
    }
}
extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    source.setFrom(fileTree("apps/independent-consumer/src") { include("**/*.kt") })
}
