import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("dev.shayxo.composedoc:ui:0.1.0-SNAPSHOT")
    implementation("dev.shayxo.composedoc:data:0.1.0-SNAPSHOT")
    implementation(compose.desktop.currentOs)
}
application { mainClass.set("consumer.MainKt") }

// cmd.exe cannot expand the generated dependency list beyond 8191 characters.
// Java expands this library wildcard itself; all dependency jars remain in the distribution.
tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    doLast {
        val original = windowsScript.readText()
        val declaration = Regex("(?m)^set CLASSPATH=.*$")
        check(declaration.findAll(original).count() == 1)
        windowsScript.writeText(original.replace(declaration) { "set CLASSPATH=%APP_HOME%\\lib\\*" })
    }
}
tasks.register("resolvedConsumerDependencies") {
    doLast {
        configurations.runtimeClasspath.get().incoming.artifacts.artifacts.forEach {
            println("RESOLVED " + it.id.componentIdentifier + " " + it.file.absolutePath)
        }
    }
}

// Retain dependency metadata; omit only metadata-only forwarding stubs colliding with a real jar.
// Never hide collisions between implementation or native/resource jars.
val consumerRuntime = configurations.named("runtimeClasspath")
distributions.named("main") {
    contents {
        exclude { element ->
            val file = element.file
            val siblings = consumerRuntime.get().filter { it.name == file.name }
            file.name.endsWith(".jar") &&
                siblings.files.size > 1 &&
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().all {
                        it.isDirectory ||
                            (it.name.startsWith("META-INF/") && !it.name.endsWith(".class"))
                    }
                }
        }
    }
}
