package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaperSize
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorViewModel

/** Reproducible disposable writing exercise; historical v3/v5/v6 samples remain untouched. */
fun localWorkspaceSample(): Document {
    val e = EditorViewModel(structuredWritingSample())
    e.dispatch(EditorIntent.Select(Selection(0, 0)))
    e.dispatch(EditorIntent.Type("مقامی تحریری ورک اسپیس — آزمائشی نمونہ\n", "WorkspaceSample", paragraphBreaks = true))
    e.dispatch(EditorIntent.Select(Selection(0, 0)))
    e.dispatch(EditorIntent.Structure(StructureCommand.Role(1)))
    e.dispatch(EditorIntent.SetParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START))
    e.dispatch(EditorIntent.Structure(StructureCommand.Pages(PageSettings(PaperSize.A4, false, 18))))
    return e.snapshot().committed
}
