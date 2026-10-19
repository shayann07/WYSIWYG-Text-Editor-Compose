package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.domain.ClipboardCapture
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.layout.DocumentPagePlan
import dev.shayxo.composedoc.layout.PrintSnapshot
import dev.shayxo.composedoc.model.PageDigits
import dev.shayxo.composedoc.model.PaginationRules
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.model.RunningContent
import dev.shayxo.composedoc.model.RunningField
import dev.shayxo.composedoc.model.RunningFlow
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.targetParagraphs
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.ImageResources
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.print.PageFormat
import java.awt.print.PrinterJob
import javax.swing.AbstractAction
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingWorker
import javax.swing.Timer

private const val SHORT_FIELD_COLUMNS = 3
private const val MAX_WIDOW_ORPHAN_LINES = 5
private const val RUNNING_TEXT_ROWS = 3
private const val RUNNING_TEXT_COLUMNS = 36
private const val DEFAULT_RUNNING_SIZE = 18
private const val MAX_PREVIEW_PAGES = 200
private const val PREVIEW_WIDTH_PX = 850
private const val PREVIEW_HEIGHT_PX = 700
private const val OFFSCREEN_OWNER_X_PX = -10000
private const val OFFSCREEN_COORDINATE_PX = -16000
private const val PREVIEW_DPI = 96
private const val CAPTURE_TICK_MS = 10

/** Editors for one running flow (header or footer) inside the running-content dialog. */
private data class RunningControls(
    val text: JTextArea,
    val font: JComboBox<String>,
    val size: JTextField,
    val bold: JCheckBox,
    val italic: JCheckBox,
    val direction: JComboBox<ParagraphDirection>,
    val alignment: JComboBox<ParagraphAlignment>,
    val field: JComboBox<RunningField>,
)

/** All editors of the running-content dialog. */
private class RunningForm(
    val header: RunningControls,
    val footer: RunningControls,
    val first: JCheckBox,
    val digits: JComboBox<PageDigits>,
)

/** Desktop consumer. Commands own content; preview/print use a captured immutable plan. */
class PageOutputUi(
    private val window: JFrame,
    private val surface: () -> EditorSurface,
    private val busy: () -> Boolean,
) {
    val panel = JPanel(WrappingControls()).apply {
        isOpaque = false
        name = "page-output-controls"
    }
    init {
        fun button(label: String, name: String, action: () -> Unit) {
            panel.add(
                JButton(label).apply {
                    this.name = name
                    toolTipText = label
                    addActionListener { if (surface().admissionAllowed && !busy())action() }
                },
            )
        }
        button("Insert page break", "insert-page-break") {
            surface().viewModel.dispatch(EditorIntent.Structure(StructureCommand.PageBreak))
            surface().requestFocusInWindow()
        }
        button("Paragraph pagination…", "pagination-properties") { pagination() }
        button("Header / Footer…", "running-content") { running() }
        button("Print preview / Print…", "print-preview") { preview() }
        button("Layout notices…", "page-layout-notices") {
            val s = surface()
            val plan = s.geometry()?.documentPagePlan
            JOptionPane.showMessageDialog(
                window,
                s.view.layoutError
                    ?: plan?.relaxations?.joinToString("\n")?.ifEmpty {
                        "No pagination constraint was relaxed. " +
                            "Fixed paper size; fit-width and zoom are presentation only."
                    }
                    ?: "Layout is not ready",
            )
        }
        button("PDF status…", "pdf-output-status") {
            JOptionPane.showMessageDialog(
                window,
                "Semantic PDF export is not qualified. " +
                    "The pinned paragraph binding does not expose the actual positioned glyph/cluster stream; " +
                    "independent shaping failed the mixed-script witness. " +
                    "Print preview uses actual captured page pictures, not searchable PDF. " +
                    "Your document is unchanged.",
                "PDF bridge blocked",
                JOptionPane.INFORMATION_MESSAGE,
            )
        }
        panel.add(JLabel("Fixed paper layout · Fit width × Zoom · PDF gate open"))
        window.rootPane.getInputMap(
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        ).put(KeyStroke.getKeyStroke("control ENTER"), "explicit-page-break")
        window.rootPane.actionMap.put(
            "explicit-page-break",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (surface().admissionAllowed &&
                        !busy()
                    ) {
                        surface().viewModel.dispatch(EditorIntent.Structure(StructureCommand.PageBreak))
                    }
                }
            },
        )
    }
    private fun pagination() {
        val s = surface()
        val capture = s.viewModel.clipboardCapture() ?: return
        val values = capture.basis.document.targetParagraphs(capture.selection).map {
            it.properties.pagination
        }.distinct()
        val old = values.singleOrNull() ?: PaginationRules()
        val before = JCheckBox("Page break before", old.breakBefore)
        val next = JCheckBox("Keep with next", old.keepWithNext)
        val lines = JCheckBox("Keep lines together", old.keepLines)
        val widows = JTextField(old.widows.toString(), SHORT_FIELD_COLUMNS)
        val orphans = JTextField(old.orphans.toString(), SHORT_FIELD_COLUMNS)
        validatedDialog(
            window,
            if (values.size > 1)"Mixed pagination - Apply sets all targets" else "Paragraph pagination",
            "pagination-dialog",
            listOf(
                "" to before,
                "" to next,
                "" to lines,
                "Widows (last-page lines1-5)" to widows,
                "Orphans (first-page lines1-5)" to orphans,
            ),
            listOf(
                "Apply" to {
                    val w = widows.text.toIntOrNull()
                    val o = orphans.text.toIntOrNull()
                    when {
                        w == null || o == null ||
                            w !in 1..MAX_WIDOW_ORPHAN_LINES || o !in 1..MAX_WIDOW_ORPHAN_LINES ->
                            "Enter whole widow/orphan counts1-5"
                        s.viewModel.clipboardCapture() != capture -> "Document/selection changed; reopen controls"
                        else -> {
                            val rules = PaginationRules(before.isSelected, next.isSelected, lines.isSelected, w, o)
                            if (values.all { it == rules } ||
                                s.viewModel.dispatch(
                                    EditorIntent.ApplyStructure(capture, StructureCommand.Pagination(rules)),
                                )
                            ) {
                                null
                            } else {
                                "Command refused; finish input and retry"
                            }
                        }
                    }
                },
            ),
        )
        s.requestFocusInWindow()
    }
    private fun runningControls(
        rows: MutableList<Pair<String, JComponent>>,
        label: String,
        f: RunningFlow?,
    ): RunningControls {
        val text = JTextArea(f?.text ?: "", RUNNING_TEXT_ROWS, RUNNING_TEXT_COLUMNS).apply {
            lineWrap = true
            wrapStyleWord = true
            name = "running-${label.lowercase()}-text"
        }
        val font = JComboBox(StoredFont.entries.map { it.name }.toTypedArray()).apply {
            selectedIndex =
                (f?.style?.font ?: StoredFont.NOTO_NASTALIQ).ordinal
        }
        val size = JTextField(
            (f?.style?.size?.takeIf { it > 0 } ?: DEFAULT_RUNNING_SIZE).toString(),
            SHORT_FIELD_COLUMNS,
        )
        val bold = JCheckBox("Bold", f?.style?.bold == true)
        val italic = JCheckBox("Italic", f?.style?.italic == true)
        val direction = JComboBox(ParagraphDirection.entries.toTypedArray()).apply {
            selectedItem =
                f?.direction ?: ParagraphDirection.RTL
        }
        val alignment = JComboBox(ParagraphAlignment.entries.toTypedArray()).apply {
            selectedItem =
                f?.alignment ?: ParagraphAlignment.START
        }
        val field = JComboBox(RunningField.entries.toTypedArray()).apply {
            selectedItem =
                f?.field ?: RunningField.NONE
        }
        rows += label to JScrollPane(text)
        rows += "Style / field after text" to JPanel(WrappingControls()).apply {
            add(font)
            add(size)
            add(bold)
            add(italic)
            add(direction)
            add(alignment)
            add(field)
        }
        return RunningControls(text, font, size, bold, italic, direction, alignment, field)
    }
    private fun running() {
        val s = surface()
        val capture = s.viewModel.clipboardCapture() ?: return
        val old = capture.basis.document.running
        val rows = ArrayList<Pair<String, JComponent>>()
        val header = runningControls(rows, "Header", old.header)
        val footer = runningControls(rows, "Footer", old.footer)
        val first = JCheckBox("Hide running content on first page (reserve unchanged)", old.differentFirst)
        val digits = JComboBox(PageDigits.entries.toTypedArray()).apply { selectedItem = old.digits }
        rows += "" to first
        rows += "Page-number digits" to digits
        val form = RunningForm(header, footer, first, digits)
        validatedDialog(
            window,
            "Document running content - separate flows",
            "running-content-dialog",
            rows,
            listOf("Apply" to { applyRunning(s, capture, old, form) }),
        )
        s.requestFocusInWindow()
    }
    private fun runningFlow(c: RunningControls, previous: RunningFlow?): RunningFlow? {
        val field = c.field.selectedItem as RunningField
        if (c.text.text.isEmpty() && field == RunningField.NONE)return null
        val size = c.size.text.toIntOrNull() ?: error("Enter whole running font size8-32")
        return RunningFlow(
            previous?.id ?: java.util.UUID.randomUUID().toString(),
            c.text.text,
            (
                previous?.style
                    ?: TextStyleFlags()
                ).copy(
                bold = c.bold.isSelected,
                italic = c.italic.isSelected,
                font = StoredFont.entries[c.font.selectedIndex],
                size = size,
            ),
            c.direction.selectedItem as ParagraphDirection,
            c.alignment.selectedItem as ParagraphAlignment,
            field,
        )
    }
    private fun applyRunning(
        s: EditorSurface,
        capture: ClipboardCapture,
        old: RunningContent,
        form: RunningForm,
    ): String? {
        if (s.viewModel.clipboardCapture() !=
            capture
        ) {
            return "Document changed; reopen running content"
        }
        return try {
            val content =
                RunningContent(
                    runningFlow(form.header, old.header),
                    runningFlow(form.footer, old.footer),
                    form.first.isSelected,
                    form.digits.selectedItem as PageDigits,
                )
            val candidate = capture.basis.document.copy(running = content)
            s.view.measure?.invoke(s.viewModel.snapshot().copy(visible = candidate, committed = candidate))
            if (content.same(old) ||
                s.viewModel.dispatch(
                    EditorIntent.ApplyStructure(capture, StructureCommand.Running(content)),
                )
            ) {
                null
            } else {
                "Change refused; finish input and retry"
            }
        } catch (e: IllegalArgumentException) {
            e.message ?: "Invalid running content"
        } catch (e: IllegalStateException) {
            e.message ?: "Invalid running content"
        }
    }
    private fun preview() {
        val s = surface()
        val plan = currentPlan(s) ?: return
        PrintPreview(window, s, plan).open()
    }

    /** The committed, current page plan, or null after telling the user why output is unavailable. */
    private fun currentPlan(s: EditorSurface): DocumentPagePlan? {
        val frame = s.viewModel.snapshot()
        if (frame.marked != null || frame.waiting) {
            JOptionPane.showMessageDialog(
                window,
                "Finish or cancel composition before output. No provisional text is exported.",
            )
            return null
        }
        val plan = s.geometry()?.documentPagePlan
        val refusal = when {
            plan == null || plan.document !== frame.committed || plan.revision != frame.documentRevision ->
                s.view.layoutError ?: "Layout is not current; retry after rendering"
            plan.pageCount > MAX_PREVIEW_PAGES -> "Preview/print currently supports at most200 captured pages"
            else -> null
        }
        if (refusal != null) JOptionPane.showMessageDialog(window, refusal)
        return plan.takeIf { refusal == null }
    }
}

/** Captured print preview: page pictures are rendered off the EDT from one immutable snapshot. */
private class PrintPreview(
    private val window: JFrame,
    private val s: EditorSurface,
    private val plan: DocumentPagePlan,
) {
    private val dialog = JDialog(window, "Captured print preview - raster print, semantic PDF unqualified", false)
    private val image = JLabel("Capturing page pictures…")
    private val note =
        JLabel("Captured revision${plan.revision};${plan.pageCount} pages; ${plan.document.pageSettings.paper}")
    private val index = JSpinner(SpinnerNumberModel(1, 1, plan.pageCount, 1))
    private val first = JTextField("1", SHORT_FIELD_COLUMNS)
    private val last = JTextField(plan.pageCount.toString(), SHORT_FIELD_COLUMNS)
    private val fit = JCheckBox("Fit to printer printable area (scales, never repaginates)")
    private val print = JButton("Print…").apply { isEnabled = false }
    private val close = JButton("Cancel / Close")
    private var snapshot: PrintSnapshot? = null
    private var closed = false
    private var job: PrinterJob? = null
    private var workerBusy = false
    private lateinit var builder: PrintSnapshot.Builder
    private lateinit var timer: Timer

    fun open() {
        val controls = JPanel(WrappingControls()).apply {
            add(JLabel("Preview page"))
            add(index)
            add(JLabel("Print range"))
            add(first)
            add(last)
            add(fit)
            add(print)
            add(close)
        }
        dialog.contentPane = JPanel(BorderLayout()).apply {
            add(note, BorderLayout.NORTH)
            add(JScrollPane(image), BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        dialog.setSize(PREVIEW_WIDTH_PX, PREVIEW_HEIGHT_PX)
        dialog.setLocationRelativeTo(window)
        if (window.x < OFFSCREEN_OWNER_X_PX)dialog.setLocation(OFFSCREEN_COORDINATE_PX, OFFSCREEN_COORDINATE_PX)
        builder = try {
            PrintSnapshot.Builder(plan, ImageResources::cached)
        } catch (e: IllegalArgumentException) {
            JOptionPane.showMessageDialog(window, e.message, "Output not captured", JOptionPane.WARNING_MESSAGE)
            return
        }
        timer = Timer(CAPTURE_TICK_MS, null)
        timer.addActionListener { captureNext() }
        index.addChangeListener { render() }
        print.addActionListener { printSelectedRange() }
        close.addActionListener { cancel() }
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                cancel()
            }
            override fun windowClosed(e: WindowEvent) {
                if (!closed)cancel()
            }
        })
        dialog.isVisible = true
        timer.start()
    }

    private fun render() {
        val captured = snapshot ?: return
        if (workerBusy || closed)return
        workerBusy = true
        index.isEnabled = false
        print.isEnabled = false
        val page = (index.value as Int) - 1
        object : SwingWorker<java.awt.image.BufferedImage, Unit>() {
            override fun doInBackground() = captured.render(page, PREVIEW_DPI)

            // Reason: preview boundary; any render failure becomes a note and the dialog stays usable.
            @Suppress("TooGenericExceptionCaught")
            override fun done() {
                workerBusy = false
                try {
                    val bitmap = get()
                    if (!closed) {
                        image.icon = ImageIcon(bitmap)
                        image.text = ""
                    } else {
                        bitmap.flush()
                    }
                } catch (e: Exception) {
                    if (!closed)note.text = "Preview failed: ${e.cause?.message ?: e.message}"
                }
                if (closed) {
                    captured.close()
                } else {
                    index.isEnabled = true
                    print.isEnabled = true
                }
            }
        }.execute()
    }

    // Reason: capture boundary; any page-capture failure stops capture and reports that no output was produced.
    @Suppress("TooGenericExceptionCaught")
    private fun captureNext() {
        if (closed) {
            timer.stop()
            return
        }
        try {
            builder.next()
            image.text = "Captured${builder.completed}/${plan.pageCount}"
            if (builder.completed == plan.pageCount) {
                timer.stop()
                snapshot = builder.finish()
                note.text += "; ${plan.relaxations.size} pagination notices"
                render()
            }
        } catch (e: Exception) {
            timer.stop()
            builder.close()
            note.text = "Capture failed: ${e.message}"
            image.text = "No output produced"
        }
    }

    private fun printSelectedRange() {
        val captured = snapshot ?: return
        val range = requestedRange()
        if (range == null) {
            note.text = "Enter range1-${plan.pageCount}"
        } else {
            startPrint(captured, range)
        }
    }

    private fun requestedRange(): IntRange? {
        val a = first.text.toIntOrNull()
        val b = last.text.toIntOrNull()
        return if (a == null || b == null) {
            null
        } else {
            (a..b).takeIf { a in 1..plan.pageCount && b in a..plan.pageCount }
        }
    }

    // Reason: printing boundary; any print-system failure is reported and the captured document is unchanged.
    @Suppress("TooGenericExceptionCaught")
    private fun startPrint(captured: PrintSnapshot, range: IntRange) {
        try {
            val printer = PrinterJob.getPrinterJob()
            job = printer
            printer.jobName = "Astra captured document"
            if (!printer.printDialog()) {
                note.text = "Print cancelled; captured document unchanged"
            } else {
                val format = printer.validatePage(captured.paperFormat())
                if (geometryAccepted(captured.warnings(format))) submitPrint(printer, captured, range, format)
            }
        } catch (e: Exception) {
            note.text = "Print unavailable: ${e.message}"
        }
    }

    private fun geometryAccepted(warnings: List<String>) = warnings.isEmpty() ||
        JOptionPane.showConfirmDialog(
            dialog,
            warnings.joinToString("\n") +
                "\nContinue with ${if (fit.isSelected)"uniform fit" else "actual size"}?",
            "Printer geometry",
            JOptionPane.YES_NO_OPTION,
        ) ==
        JOptionPane.YES_OPTION

    private fun submitPrint(printer: PrinterJob, captured: PrintSnapshot, range: IntRange, format: PageFormat) {
        printer.setPageable(
            captured.pageable(range.first, range.last, format, fit.isSelected) { closed || printer.isCancelled },
        )
        workerBusy = true
        print.isEnabled = false
        index.isEnabled = false
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                printer.print()
            }

            // Reason: printing boundary; any background print failure is reported as a note.
            @Suppress("TooGenericExceptionCaught")
            override fun done() {
                workerBusy = false
                try {
                    get()
                    if (!closed)note.text = "Print system accepted job; physical result unqualified"
                } catch (e: Exception) {
                    if (!closed)note.text = "Print failed/cancelled: ${e.cause?.message ?: e.message}"
                }
                if (closed) {
                    captured.close()
                } else {
                    print.isEnabled = true
                    index.isEnabled = true
                }
            }
        }.execute()
    }

    private fun cancel() {
        if (closed)return
        closed = true
        timer.stop()
        job?.cancel()
        builder.close()
        if (!workerBusy)snapshot?.close()
        dialog.dispose()
        s.requestFocusInWindow()
    }
}
