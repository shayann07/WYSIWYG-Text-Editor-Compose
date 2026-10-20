pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(providers.gradleProperty("consumerRepository").get()) } }
            filter { includeGroup("dev.shayxo.composedoc") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "IndependentComposeDocConsumer"
