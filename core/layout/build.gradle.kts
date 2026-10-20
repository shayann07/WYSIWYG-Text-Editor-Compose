import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension

plugins { id("compose-multiplatform-conventions") }
kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(project(":core:model"))
        api(project(":core:domain"))
    }
    getByName("jvmMain").dependencies {
        api(libs.compose.ui)
        api(libs.skiko.awt)
    }
}

// Package the existing SHA-pinned candidates and their original OFL notice.
// Consumers load these resources from this library jar, never from a checkout.
tasks.named<ProcessResources>("jvmProcessResources") {
    val target = "dev/shayxo/composedoc/layout/fonts"
    from(rootProject.file("tests/suites/urdu/u0/fonts.json")) { into(target) }
    from(rootProject.file("tests/suites/urdu/u0/fonts")) {
        include("*.ttf", "OFL.txt")
        into(target)
    }
}

kotlin { android { androidResources.enable = true } }
kotlin.sourceSets.getByName("androidMain").dependencies { api(libs.compose.ui) }
val packageAndroidFonts by tasks.registering(PinnedAndroidFontsTask::class) {
    sourceFiles.from(rootProject.file("tests/suites/urdu/u0/fonts.json"))
    sourceFiles.from(rootProject.fileTree("tests/suites/urdu/u0/fonts") { include("*.ttf", "OFL.txt") })
    assetDirectory.set(layout.buildDirectory.dir("generated/pinned-font-assets"))
}
extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packageAndroidFonts) { it.assetDirectory }
    }
}
