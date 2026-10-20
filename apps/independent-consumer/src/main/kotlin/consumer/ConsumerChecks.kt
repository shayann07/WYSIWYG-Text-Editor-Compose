package consumer

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.domain.DocumentRepository
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.DesktopEditor
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorPlatformServices
import dev.shayxo.composedoc.ui.EditorViewModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

private const val URDU_WORD_UNITS = 4

// Type, Select, Format, Undo and Redo each publish observable state.
private const val MIN_PUBLICATIONS = 5
private const val EXPECTED_TEXT = "کتاب العربية English 42\n"

internal data class EditorExamples(
    val restored: EditorViewModel,
    val other: EditorViewModel,
    val document: Document,
    val profile: TypographyProfile,
)

internal fun prepareExamples(root: Path): EditorExamples {
    val first = EditorViewModel()
    val other = EditorViewModel()
    val observed = mutableListOf<String>()
    first.onStatePublished = { observed += it.committed.text }
    first.dispatch(EditorIntent.Type(EXPECTED_TEXT, paragraphBreaks = true))
    first.dispatch(EditorIntent.Select(Selection(0, URDU_WORD_UNITS)))
    first.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
    val formatted = checkAttributedHistory(first)
    other.dispatch(EditorIntent.Type("Independent second owner"))
    check(first.snapshot().committed.same(formatted))
    check(other.snapshot().committed.text == "Independent second owner" && observed.size >= MIN_PUBLICATIONS)
    val file = root.resolve("consumer.astra")
    check(Files.notExists(file)) { "Use a new example directory; existing files are preserved" }
    val loaded = offUi {
        val repository: DocumentRepository = DocumentFiles
        repository.save(file.toAbsolutePath().toString(), formatted)
        repository.load(file.toAbsolutePath().toString()) to TypographyProfile.load("noto-nastaliq")
    }
    check(loaded.first.same(formatted))
    check(loaded.second.requestedFamilyName.contains("Nastaliq"))
    val restored = EditorViewModel(loaded.first)
    check(restored.snapshot().committed.same(formatted) && restored.snapshot().undoCount == 0)
    first.dispatch(EditorIntent.CancelPendingOnTeardown)
    first.detach()
    return EditorExamples(restored, other, formatted, loaded.second)
}

private fun checkAttributedHistory(model: EditorViewModel): Document {
    val formatted = model.snapshot().committed
    check(formatted.text == EXPECTED_TEXT)
    check(formatted.units.take(URDU_WORD_UNITS).all { it.style.bold })
    check(formatted.units.drop(URDU_WORD_UNITS).all { !it.style.bold })
    model.dispatch(EditorIntent.Command(EditAction.Undo))
    check(model.snapshot().committed.units.none { it.style.bold })
    model.dispatch(EditorIntent.Command(EditAction.Redo))
    check(model.snapshot().committed.same(formatted))
    return formatted
}

internal fun checkHostLifecycle(examples: EditorExamples) {
    checkMountedCanvas(examples)
    val host = DesktopEditor(
        examples.restored,
        examples.profile,
        EditorPlatformServices(DocumentFiles, FragmentCodec),
    )
    check(host.model === examples.restored && host.inputSurface.isTimerRunning)
    host.close()
    check(!host.inputSurface.isTimerRunning)
    val recreated = DesktopEditor(examples.restored, examples.profile)
    check(recreated.model.snapshot().committed.same(examples.document))
    recreated.close()
    checkConsumerToolbar(examples)
    examples.restored.detach()
    examples.other.detach()
}

private fun <T> offUi(action: () -> T): T {
    val work = FutureTask(Callable { action() })
    Thread(work, "consumer-startup-io").start()
    return work.get()
}
