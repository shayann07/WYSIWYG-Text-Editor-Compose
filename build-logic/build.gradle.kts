plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.kotlin.gradle)
    implementation(libs.android.gradle)
    implementation(libs.compose.gradle)
    implementation(libs.compose.compiler.gradle)
    implementation(libs.detekt.gradle)
    implementation(libs.spotless.gradle)
}

kotlin { jvmToolchain(21) }
