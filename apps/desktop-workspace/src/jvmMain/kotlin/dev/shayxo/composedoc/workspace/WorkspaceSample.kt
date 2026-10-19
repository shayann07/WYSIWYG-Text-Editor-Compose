package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.domain.EditorEngine
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaperSize
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.Selection

/** Reproducible disposable writing exercise; historical v3/v5/v6 samples remain untouched. */
fun localWorkspaceSample(): Document {
    val e = EditorEngine(structuredWritingSample())
    e.move(Selection(0, 0))
    e.replace("مقامی تحریری ورک اسپیس — آزمائشی نمونہ\n", "WorkspaceSample", paragraphBreaks = true)
    e.move(Selection(0, 0))
    e.structure(StructureCommand.Role(1))
    e.setParagraphProperties(ParagraphDirection.RTL, ParagraphAlignment.START)
    e.structure(StructureCommand.Pages(PageSettings(PaperSize.A4, false, 18)))
    return e.snapshot().committed
}
