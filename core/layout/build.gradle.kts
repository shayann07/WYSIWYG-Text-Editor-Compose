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
