import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("com.diffplug.spotless")
}

val versions = extensions.getByType<VersionCatalogsExtension>().named("libs")
extensions.configure<SpotlessExtension> {
    // `.gitattributes` disables conversion, so pin LF; otherwise Windows and Linux disagree.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/generated/**", "**/Unicode16ConformanceTest.kt")
        ktlint(versions.findVersion("ktlint").get().requiredVersion)
            .setEditorConfigPath(rootProject.file(".editorconfig"))
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(versions.findVersion("ktlint").get().requiredVersion)
            .setEditorConfigPath(rootProject.file(".editorconfig"))
    }
}
extensions.configure<DetektExtension> {
    toolVersion = versions.findVersion("detekt").get().requiredVersion
    buildUponDefaultConfig = true
    // Pre-migration prototype findings are recorded per module; new findings fail the build.
    baseline = file("detekt-baseline.xml")
    config.setFrom(rootProject.file("build-logic/config/detekt.yml"))
    // Generated sources (Unicode tables and the pinned conformance test) keep generator output.
    source.setFrom(fileTree("src") { include("**/*.kt"); exclude("**/generated/**", "**/Unicode16ConformanceTest.kt") })
}
tasks.register("ktlintCheck") { dependsOn("spotlessCheck") }
tasks.matching { it.name == "check" }.configureEach { dependsOn("spotlessCheck", "detekt") }
