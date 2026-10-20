plugins { id("kmp-library-conventions") }
kotlin.sourceSets.getByName("commonMain").dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
}

// Java IO snapshot implementation shared by the two JVM-backed platform compilations only.
// It is deliberately not common metadata or a dependency of native/JS source sets.
kotlin.sourceSets {
    getByName("jvmMain").kotlin.srcDir("src/javaMain/kotlin")
    getByName("androidMain").kotlin.srcDir("src/javaMain/kotlin")
}
