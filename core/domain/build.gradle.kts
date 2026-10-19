plugins { id("kmp-library-conventions") }
kotlin.sourceSets.getByName("commonMain").dependencies { api(project(":core:model")) }
