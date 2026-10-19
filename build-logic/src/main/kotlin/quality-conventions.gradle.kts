import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("com.diffplug.spotless")
}

val versions = extensions.getByType<VersionCatalogsExtension>().named("libs")
extensions.configure<SpotlessExtension> {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/generated/**")
        ktlint(versions.findVersion("ktlint").get().requiredVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(versions.findVersion("ktlint").get().requiredVersion)
    }
}
extensions.configure<DetektExtension> {
    toolVersion = versions.findVersion("detekt").get().requiredVersion
    buildUponDefaultConfig = true
    // Pre-migration prototype findings are recorded per module; new findings fail the build.
    baseline = file("detekt-baseline.xml")
    source.setFrom(fileTree("src") { include("**/*.kt"); exclude("**/generated/**") })
}
tasks.register("ktlintCheck") { dependsOn("spotlessCheck") }
tasks.matching { it.name == "check" }.configureEach { dependsOn("spotlessCheck", "detekt") }
