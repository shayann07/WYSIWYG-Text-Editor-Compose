import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Packages exact source bytes and license notices; no font transformation or installation. */
abstract class PinnedAndroidFontsTask : DefaultTask() {
    @get:InputFiles abstract val sourceFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
    @TaskAction fun packageFonts() {
        val directory = assetDirectory.get().asFile.resolve("dev/shayxo/composedoc/layout/fonts")
        check(directory.isDirectory || directory.mkdirs())
        sourceFiles.files.forEach { it.copyTo(directory.resolve(it.name), overwrite = true) }
    }
}
