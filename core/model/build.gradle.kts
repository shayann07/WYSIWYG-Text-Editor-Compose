plugins { id("kmp-library-conventions") }
kotlin.compilerOptions {
    optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.io.encoding.ExperimentalEncodingApi")
}
