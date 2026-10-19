package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import java.awt.*
import java.awt.event.*
import java.awt.print.PrinterJob
import javax.swing.*

/** Desktop consumer. Commands own content; preview/print use a captured immutable plan. */
class PageOutputUi(private val window: JFrame, private val surface: () -> EditorSurface, private val busy: () -> Boolean) {
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
            surface().engine.structure(StructureCommand.PageBreak)
            surface().requestFocusInWindow()
        }
        button("Paragraph pagination…", "pagination-properties") { pagination() }
        button("Header / Footer…", "running-content") { running() }
        button("Print preview / Print…", "print-preview") { preview() }
        button("Layout notices…", "page-layout-notices") {
            val s = surface()
            val plan = s.geometry()?.documentPagePlan
            JOptionPane.showMessageDialog(window, s.view.layoutError ?: plan?.relaxations?.joinToString("\n")?.ifEmpty { "No pagination constraint was relaxed. Fixed paper size; fit-width and zoom are presentation only." } ?: "Layout is not ready")
        }
        button("PDF status…", "pdf-output-status") { JOptionPane.showMessageDialog(window, "Semantic PDF export is not qualified. The pinned paragraph binding does not expose the actual positioned glyph/cluster stream; independent shaping failed the mixed-script witness. Print preview uses actual captured page pictures, not searchable PDF. Your document is unchanged.", "PDF bridge blocked", JOptionPane.INFORMATION_MESSAGE) }
        panel.add(JLabel("Fixed paper layout · Fit width × Zoom · PDF gate open"))
        window.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control ENTER"), "explicit-page-break")
        window.rootPane.actionMap.put(
            "explicit-page-break",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    if (surface().admissionAllowed && !busy())surface().engine.structure(StructureCommand.PageBreak)
                }
            },
        )
    }
    private fun pagination() {
        val s = surface()
        val capture = s.engine.clipboardCapture() ?: return
        val values = capture.basis.document.targetParagraphs(capture.selection).map { it.properties.pagination }.distinct()
        val old = values.singleOrNull() ?: PaginationRules()
        val before = JCheckBox("Page break before", old.breakBefore)
        val next = JCheckBox("Keep with next", old.keepWithNext)
        val lines = JCheckBox("Keep lines together", old.keepLines)
        val widows = JTextField(old.widows.toString(), 3)
        val orphans = JTextField(old.orphans.toString(), 3)
        validatedDialog(
            window,
            if (values.size > 1)"Mixed pagination - Apply sets all targets" else "Paragraph pagination",
            "pagination-dialog",
            listOf("" to before, "" to next, "" to lines, "Widows (last-page lines1-5)" to widows, "Orphans (first-page lines1-5)" to orphans),
            listOf(
                "Apply" to {
                    val w = widows.text.toIntOrNull()
                    val o = orphans.text.toIntOrNull()
                    when {
                        w == null || o == null || w !in 1..5 || o !in 1..5 -> "Enter whole widow/orphan counts1-5"
                        s.engine.clipboardCapture() != capture -> "Document/selection changed; reopen controls"
                        else -> {
                            val rules = PaginationRules(before.isSelected, next.isSelected, lines.isSelected, w, o)
                            if (values.all { it == rules } || s.engine.applyStructure(capture, StructureCommand.Pagination(rules)))null else "Command refused; finish input and retry"
                        }
                    }
                },
            ),
        )
        s.requestFocusInWindow()
    }
    private fun running() {
        val s = surface()
        val capture = s.engine.clipboardCapture() ?: return
        val old = capture.basis.document.running
        data class Controls(val text: JTextArea, val font: JComboBox<String>, val size: JTextField, val bold: JCheckBox, val italic: JCheckBox, val direction: JComboBox<ParagraphDirection>, val alignment: JComboBox<ParagraphAlignment>, val field: JComboBox<RunningField>)
        val rows = ArrayList<Pair<String, JComponent>>()
        fun controls(label: String, f: RunningFlow?): Controls {
            val text = JTextArea(f?.text ?: "", 3, 36).apply {
                lineWrap = true
                wrapStyleWord = true
                name = "running-${label.lowercase()}-text"
            }
            val font = JComboBox(StoredFont.entries.map { it.name }.toTypedArray()).apply { selectedIndex = (f?.style?.font ?: StoredFont.NOTO_NASTALIQ).ordinal }
            val size = JTextField((f?.style?.size?.takeIf { it > 0 } ?: 18).toString(), 3)
            val bold = JCheckBox("Bold", f?.style?.bold == true)
            val italic = JCheckBox("Italic", f?.style?.italic == true)
            val direction = JComboBox(ParagraphDirection.entries.toTypedArray()).apply { selectedItem = f?.direction ?: ParagraphDirection.RTL }
            val alignment = JComboBox(ParagraphAlignment.entries.toTypedArray()).apply { selectedItem = f?.alignment ?: ParagraphAlignment.START }
            val field = JComboBox(RunningField.entries.toTypedArray()).apply { selectedItem = f?.field ?: RunningField.NONE }
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
            return Controls(text, font, size, bold, italic, direction, alignment, field)
        }
        val header = controls("Header", old.header)
        val footer = controls("Footer", old.footer)
        val first = JCheckBox("Hide running content on first page (reserve unchanged)", old.differentFirst)
        val digits = JComboBox(PageDigits.entries.toTypedArray()).apply { selectedItem = old.digits }
        rows += "" to first
        rows += "Page-number digits" to digits
        validatedDialog(
            window,
            "Document running content - separate flows",
            "running-content-dialog",
            rows,
            listOf(
                "Apply" to apply@{
                    if (s.engine.clipboardCapture() != capture)return@apply "Document changed; reopen running content"
                    fun value(c: Controls, previous: RunningFlow?): RunningFlow? {
                        val field = c.field.selectedItem as RunningField
                        if (c.text.text.isEmpty() && field == RunningField.NONE)return null
                        val size = c.size.text.toIntOrNull() ?: error("Enter whole running font size8-32")
                        return RunningFlow(previous?.id ?: java.util.UUID.randomUUID().toString(), c.text.text, (previous?.style ?: TextStyleFlags()).copy(bold = c.bold.isSelected, italic = c.italic.isSelected, font = StoredFont.entries[c.font.selectedIndex], size = size), c.direction.selectedItem as ParagraphDirection, c.alignment.selectedItem as ParagraphAlignment, field)
                    }
                    try {
                        val content = RunningContent(value(header, old.header), value(footer, old.footer), first.isSelected, digits.selectedItem as PageDigits)
                        val candidate = capture.basis.document.copy(running = content)
                        s.view.measure?.invoke(s.engine.snapshot().copy(visible = candidate, committed = candidate))
                        if (content.same(old) || s.engine.applyStructure(capture, StructureCommand.Running(content)))null else "Change refused; finish input and retry"
                    } catch (e: IllegalArgumentException) {
                        e.message ?: "Invalid running content"
                    } catch (e: IllegalStateException) {
                        e.message ?: "Invalid running content"
                    }
                },
            ),
        )
        s.requestFocusInWindow()
    }
    private fun preview() {
        val s = surface()
        val frame = s.engine.snapshot()
        if (frame.marked != null || frame.waiting) {
            JOptionPane.showMessageDialog(window, "Finish or cancel composition before output. No provisional text is exported.")
            return
        }
        val plan = s.geometry()?.documentPagePlan
        if (plan == null || plan.document !== frame.committed || plan.revision != frame.documentRevision) {
            JOptionPane.showMessageDialog(window, s.view.layoutError ?: "Layout is not current; retry after rendering")
            return
        }
        if (plan.pageCount > 200) {
            JOptionPane.showMessageDialog(window, "Preview/print currently supports at most200 captured pages")
            return
        }
        val dialog = JDialog(window, "Captured print preview - raster print, semantic PDF unqualified", false)
        val image = JLabel("Capturing page pictures…")
        val note = JLabel("Captured revision${plan.revision};${plan.pageCount} pages; ${plan.document.pageSettings.paper}")
        val index = JSpinner(SpinnerNumberModel(1, 1, plan.pageCount, 1))
        val first = JTextField("1", 3)
        val last = JTextField(plan.pageCount.toString(), 3)
        val fit = JCheckBox("Fit to printer printable area (scales, never repaginates)")
        val print = JButton("Print…").apply { isEnabled = false }
        val close = JButton("Cancel / Close")
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
        dialog.setSize(850, 700)
        dialog.setLocationRelativeTo(window)
        if (window.x < -10000)dialog.setLocation(-16000, -16000)
        var snapshot: PrintSnapshot? = null
        var closed = false
        var job: PrinterJob? = null
        var workerBusy = false
        val builder = try {
            PrintSnapshot.Builder(plan, ImageResources::cached)
        } catch (e: IllegalArgumentException) {
            JOptionPane.showMessageDialog(window, e.message, "Output not captured", JOptionPane.WARNING_MESSAGE)
            return
        }
        fun render() {
            val captured = snapshot ?: return
            if (workerBusy || closed)return
            workerBusy = true
            index.isEnabled = false
            print.isEnabled = false
            val page = (index.value as Int) - 1
            object : SwingWorker<java.awt.image.BufferedImage, Unit>() {
                override fun doInBackground() = captured.render(page, 96)
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
        val timer = Timer(10, null)
        timer.addActionListener {
            if (closed) {
                timer.stop()
                return@addActionListener
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
        index.addChangeListener { render() }
        print.addActionListener {
            val captured = snapshot ?: return@addActionListener
            val a = first.text.toIntOrNull()
            val b = last.text.toIntOrNull()
            if (a == null || b == null || a !in 1..plan.pageCount || b !in a..plan.pageCount) {
                note.text = "Enter range1-${plan.pageCount}"
                return@addActionListener
            }
            try {
                val printer = PrinterJob.getPrinterJob()
                job = printer
                printer.jobName = "Astra captured document"
                if (!printer.printDialog()) {
                    note.text = "Print cancelled; captured document unchanged"
                    return@addActionListener
                }
                val format = printer.validatePage(captured.paperFormat())
                val warnings = captured.warnings(format)
                if (warnings.isNotEmpty() && JOptionPane.showConfirmDialog(dialog, warnings.joinToString("\n") + "\nContinue with ${if (fit.isSelected)"uniform fit" else "actual size"}?", "Printer geometry", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)return@addActionListener
                printer.setPageable(captured.pageable(a, b, format, fit.isSelected) { closed || printer.isCancelled })
                workerBusy = true
                print.isEnabled = false
                index.isEnabled = false
                object : SwingWorker<Unit, Unit>() {
                    override fun doInBackground() {
                        printer.print()
                    }
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
            } catch (e: Exception) {
                note.text = "Print unavailable: ${e.message}"
            }
        }
        fun cancel() {
            if (closed)return
            closed = true
            timer.stop()
            job?.cancel()
            builder.close()
            if (!workerBusy)snapshot?.close()
            dialog.dispose()
            s.requestFocusInWindow()
        }
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
}
