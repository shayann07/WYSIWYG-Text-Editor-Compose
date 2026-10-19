import java.util.zip.ZipFile

plugins { application }

// JetBrains Compose publishes class-free redirect jars named exactly like the
// androidx jars they forward to. Omit only those colliding stubs so every
// distribution keeps one real jar per file name; class-free native/resource
// jars without a name collision are retained.
fun File.isClassFreeJar(): Boolean =
    ZipFile(this).use { zip -> zip.entries().asSequence().none { it.name.endsWith(".class") } }

val runtimeJars = configurations.named("runtimeClasspath")
distributions.named("main") {
    contents {
        exclude { element ->
            val file = element.file
            file.name.endsWith(".jar") &&
                runtimeJars.get().count { it.name == file.name } > 1 &&
                file.isClassFreeJar()
        }
    }
}
