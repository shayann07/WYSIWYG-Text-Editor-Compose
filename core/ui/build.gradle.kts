plugins { id("compose-multiplatform-conventions") }
kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(project(":core:model"))
        api(project(":core:domain"))
        api(project(":core:layout"))
        api(libs.lifecycle.viewmodel)
        api(libs.coroutines.core)
    }
    getByName("jvmMain").dependencies {
        implementation(libs.compose.foundation)
        api(libs.compose.ui.awt)
    }
}

kotlin.sourceSets.getByName("androidMain").dependencies {
    implementation(libs.compose.foundation)
    api(libs.compose.ui)
}
