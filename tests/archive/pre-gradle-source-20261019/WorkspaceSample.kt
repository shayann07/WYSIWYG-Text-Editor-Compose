package editor

/** Reproducible disposable writing exercise; historical v3/v5/v6 samples remain untouched. */
internal fun localWorkspaceSample():Document {
    val e=EditorEngine(structuredWritingSample())
    e.move(Selection(0,0));e.replace("مقامی تحریری ورک اسپیس — آزمائشی نمونہ\n", "WorkspaceSample",paragraphBreaks=true)
    e.move(Selection(0,0));e.structure(StructureCommand.Role(1));e.setParagraphProperties(ParagraphDirection.RTL,ParagraphAlignment.START)
    e.structure(StructureCommand.Pages(PageSettings(PaperSize.A4,false,18)))
    return e.snapshot().committed
}
