package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FileFingerprint
import dev.shayxo.composedoc.data.LocalCopy
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.SafeDocumentIO
import dev.shayxo.composedoc.data.TemplateEntry
import dev.shayxo.composedoc.data.TemplateStore
import dev.shayxo.composedoc.data.UnfinishedStore
import dev.shayxo.composedoc.data.WorkspaceBytes
import dev.shayxo.composedoc.data.WorkspaceEntry
import dev.shayxo.composedoc.data.WorkspaceStore
import dev.shayxo.composedoc.data.workspacePositionsValid
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.PageSettings
import dev.shayxo.composedoc.model.PaperSize
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.findSurface
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingWorker
import javax.swing.Timer

private const val OBSERVE_INTERVAL_MS = 1800
private const val MAX_ENTRY_NAME_UNITS = 512
private const val MAX_VIEWPORT_PX = 16384
private const val TEMPLATE_VISIBLE_ROWS = 7
private const val TEMPLATE_FONT_SIZE = 16
private const val RECENT_FONT_SIZE = 15
private const val DIALOG_GAP_PX = 8
private const val DIALOG_EDGE_PX = 12
private const val TEMPLATE_NAME_COLUMNS = 28
private const val MAX_TEMPLATE_NAME_UNITS = 128
private const val ERROR_COLOR_RGB = 0xb3261e
private const val MARGIN_FIELD_COLUMNS = 6
private const val MIN_MARGIN_MM = 5
private const val MAX_MARGIN_MM = 40
private const val SHORT_ID_LENGTH = 8
private const val CELL_VERTICAL_PADDING_PX = 7
private const val CELL_HORIZONTAL_PADDING_PX = 8
private const val NOTE_ROWS = 3
private const val NOTE_COLUMNS = 50
private const val RECENT_WIDTH_PX = 840
private const val RECENT_HEIGHT_PX = 480
private const val ZOOM_HALF = .5f
private const val ZOOM_THREE_QUARTERS = .75f
private const val ZOOM_FULL = 1f
private val ZOOM_LEVELS = listOf(ZOOM_HALF, ZOOM_THREE_QUARTERS, ZOOM_FULL)

// Reason: workspace UI boundary; any background or callback failure is shown and the user's work is kept.
@Suppress("TooGenericExceptionCaught")
fun <T> workspaceWork(parent: Component, action: () -> T, success: (T) -> Unit) {
    object : SwingWorker<T, Unit>() {
        override fun doInBackground() = action()
        override fun done() {
            try {
                success(get())
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    parent,
                    e.cause?.message ?: e.message,
                    "Workspace operation failed — work kept",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }.execute()
}

class WorkspaceUi(
    val window: JFrame,
    val root: Path,
    val surface: () -> EditorSurface,
    val files: WindowFiles,
    val committed: LocalRecoveryController?,
    val unfinished: UnfinishedController?,
) {
    val store = WorkspaceStore(root.resolve("workspace"))
    val templates = TemplateStore(root.resolve("templates"))
    val id = UUID.randomUUID().toString()
    private val openedAt = System.currentTimeMillis()
    val panel = JPanel(WrappingControls()).apply {
        isOpaque = false
        name = "workspace-controls"
    }
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "workspace-index").apply { isDaemon = true }
    }
    private data class Capture(val entry: WorkspaceEntry, val document: Document)
    private var next: Capture? = null
    private var hashedDocument: Document? = null
    private var contentHash = ""
    private var writing = false
    private var closed = false
    private val timer = Timer(OBSERVE_INTERVAL_MS) { observe() }.apply { start() }
    var warning: String? = null
        private set
    init {
        fun button(label: String, name: String, action: () -> Unit) {
            panel.add(
                JButton(label).apply {
                    this.name = name
                    addActionListener { action() }
                },
            )
        }
        button("New / Template…", "workspace-new") { chooseTemplate() }
        button("Recent / Workspace…", "workspace-recent") { showRecent() }
        button("Save as Template…", "workspace-save-template") { saveTemplate() }
        button("Page settings…", "workspace-pages") { pageDialog() }
        button("Unfinished recovery…", "workspace-unfinished") {
            showUnfinishedRecovery(
                root.resolve("unfinished-records"),
                surface().view.typography.id,
                surface().view.paged,
            )
        }
        button("File status / conflict…", "workspace-conflict") {
            if (files.conflict != null) {
                files.showConflict()
            } else {
                JOptionPane.showMessageDialog(
                    window,
                    "${files.path ?: "Untitled — no named file"}\n${files.status}\n" +
                        "Last opened/saved bytes: ${files.fingerprint ?: "No disk basis"}\n" +
                        "Save revalidates the current destination; automatic recovery never overwrites it.",
                    "Document file status",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }
        window.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke("control N"), "new-template")
        window.rootPane.actionMap.put(
            "new-template",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    chooseTemplate()
                }
            },
        )
        files.preserveBeforeReload = { doc, done ->
            val target = UUID.randomUUID().toString()
            workspaceWork(window, {
                val rs = LocalRecoveryStore(root)
                rs.acquire(target).use { lease ->
                    rs.publish(lease, LocalCopy(target, 1, System.currentTimeMillis(), files.path?.toString(), doc))
                }
                Unit
            }) { done() }
        }
    }

    // Reason: the background metadata writer is a resilience boundary; any failure becomes a visible warning.
    @Suppress("TooGenericExceptionCaught")
    fun observe() {
        if (closed || committed == null || unfinished == null)return
        val s = surface()
        val f = s.viewModel.snapshot()
        val e = WorkspaceEntry(
            id,
            files.path?.toAbsolutePath()?.normalize()?.toString(),
            files.name.take(MAX_ENTRY_NAME_UNITS),
            files.fingerprint,
            openedAt,
            committed.session,
            unfinished.owner,
            if (f.marked == null && f.visible.same(f.committed))f.selection else Selection(0, 0),
            s.view.scroll.coerceAtLeast(0f),
            s.view.paged,
            s.view.zoom,
            s.view.typography.id,
            true,
            viewportWidth = s.width.coerceIn(0, MAX_VIEWPORT_PX),
            viewportHeight = s.height.coerceIn(0, MAX_VIEWPORT_PX),
        )
        synchronized(this) {
            next = Capture(e, f.committed)
            if (writing)return
            writing = true
        }
        executor.execute {
            try {
                while (true) {
                    val task = synchronized(this) { next.also { next = null } } ?: break
                    if (hashedDocument !== task.document) {
                        contentHash = WorkspaceBytes.hash(DocumentFiles.encode(task.document))
                        hashedDocument = task.document
                    }
                    store.update(task.entry.copy(contentHash = contentHash))
                }
            } catch (e: Exception) {
                warning = "Workspace metadata failed: ${e.message}"
            } finally {
                synchronized(this) { writing = false }
            }
        }
    }
    fun close(deliberate: Boolean) {
        if (closed)return
        observe()
        closed = true
        timer.stop()
        executor.execute {
            if (deliberate)store.entries().firstOrNull { it.id == id }?.let { store.update(it.copy(active = false)) }
        }
        executor.shutdown()
    }
    private fun newWindow(d: Document): JFrame = showEditor(
        initial = d,
        fontProfile = surface().view.typography.id,
        paged = surface().view.paged,
        recoveryDirectory = root,
    ).also { windowFiles(it)!!.markUnsavedCopy("New from template") }
    fun chooseTemplate() {
        workspaceWork(window, { templates.list() }) { entries ->
            val dialog = JDialog(window, "Create from template — independent unsaved copy", true)
            val model = DefaultListModel<TemplateEntry>()
            entries.forEach(model::addElement)
            val list = JList(model).apply {
                name = "template-list"
                selectedIndex = 0
                visibleRowCount = TEMPLATE_VISIBLE_ROWS
                font = Font("Segoe UI", Font.PLAIN, TEMPLATE_FONT_SIZE)
            }
            val status = JLabel("Original sample text; no expert linguistic review")
            val create = JButton("Create unsaved copy").apply {
                name = "template-create"
                addActionListener { createFromTemplate(this, list, dialog, status) }
            }
            val remove = JButton("Remove user template…").apply {
                addActionListener {
                    val e = list.selectedValue ?: return@addActionListener
                    if (e.builtIn) {
                        status.text = "Built-in templates cannot be removed"
                        return@addActionListener
                    }
                    if (JOptionPane.showConfirmDialog(
                            dialog,
                            "Remove this private template? Source documents and created copies remain.",
                            "Remove template",
                            JOptionPane.YES_NO_OPTION,
                        ) == JOptionPane.YES_OPTION
                    ) {
                        workspaceWork(dialog, { templates.remove(e) }) { model.removeElement(e) }
                    }
                }
            }
            dialog.contentPane = JPanel(BorderLayout(DIALOG_GAP_PX, DIALOG_GAP_PX)).apply {
                border = BorderFactory.createEmptyBorder(DIALOG_EDGE_PX, DIALOG_EDGE_PX, DIALOG_EDGE_PX, DIALOG_EDGE_PX)
                add(status, BorderLayout.NORTH)
                add(JScrollPane(list), BorderLayout.CENTER)
                add(
                    JPanel().apply {
                        add(create)
                        add(remove)
                        add(
                            JButton("Cancel").apply {
                                addActionListener {
                                    dialog.dispose()
                                    surface().requestFocusInWindow()
                                }
                            },
                        )
                    },
                    BorderLayout.SOUTH,
                )
            }
            dialog.pack()
            dialog.setLocationRelativeTo(window)
            dialog.isVisible = true
        }
    }

    private fun createFromTemplate(create: JButton, list: JList<TemplateEntry>, dialog: JDialog, status: JLabel) {
        val entry = list.selectedValue ?: return
        create.isEnabled = false
        object : SwingWorker<Document, Unit>() {
            override fun doInBackground() = templates.create(entry)

            // Reason: template UI boundary; any failure is shown and the template is kept.
            @Suppress("TooGenericExceptionCaught")
            override fun done() {
                try {
                    newWindow(get())
                    dialog.dispose()
                } catch (e: Exception) {
                    status.text = "Template kept; cannot create: ${e.cause?.message ?: e.message}"
                } finally {
                    create.isEnabled = true
                }
            }
        }.execute()
    }
    private fun saveTemplate() {
        val s = surface()
        val c = s.viewModel.clipboardCapture() ?: return
        val label = JTextField("My Urdu template", TEMPLATE_NAME_COLUMNS).apply { name = "template-name" }
        val dialog = JDialog(window, "Save as reusable template", true).apply { name = "save-template-dialog" }
        val error = JLabel(" ").apply { foreground = Color(ERROR_COLOR_RGB) }
        val save = JButton("Save template").apply { name = "save-template" }
        save.addActionListener {
            val message = when {
                label.text.isBlank() || label.text.length > MAX_TEMPLATE_NAME_UNITS -> "Enter a name of 1–128 units."
                s.viewModel.clipboardCapture() != c -> "Document changed; cancel and reopen Save as Template."
                else -> null
            }
            if (message != null) {
                error.text = message
                dialog.pack()
                return@addActionListener
            }
            val name = label.text
            save.isEnabled = false
            error.text = "Saving template…"
            object : SwingWorker<TemplateEntry, Unit>() {
                override fun doInBackground() = templates.save(name, c.basis.document)

                // Reason: template UI boundary; any failure is shown in the dialog and the document is kept.
                @Suppress("TooGenericExceptionCaught")
                override fun done() {
                    save.isEnabled = true
                    try {
                        get()
                        dialog.dispose()
                    } catch (e: Exception) {
                        error.text = "Not saved: ${e.cause?.message ?: e.message}"
                        dialog.pack()
                    }
                }
            }.execute()
        }
        dialog.contentPane = JPanel(BorderLayout(DIALOG_GAP_PX, DIALOG_GAP_PX)).apply {
            border = BorderFactory.createEmptyBorder(DIALOG_EDGE_PX, DIALOG_EDGE_PX, DIALOG_EDGE_PX, DIALOG_EDGE_PX)
            add(label, BorderLayout.CENTER)
            add(error, BorderLayout.NORTH)
            add(
                JPanel().apply {
                    add(save)
                    add(JButton("Cancel").apply { addActionListener { dialog.dispose() } })
                },
                BorderLayout.SOUTH,
            )
        }
        dialog.pack()
        dialog.setLocationRelativeTo(window)
        dialog.isVisible = true
        s.requestFocusInWindow()
    }
    private fun pageDialog() {
        val s = surface()
        if (!s.admissionAllowed || files.busy) {
            JOptionPane.showMessageDialog(
                window,
                "Finish file work or recover paused input before changing page settings.",
            )
            return
        }
        val c = s.viewModel.clipboardCapture() ?: run {
            JOptionPane.showMessageDialog(window, "Finish composing before opening page settings.")
            return
        }
        val old = c.basis.document.pageSettings
        val size = JComboBox(PaperSize.entries.toTypedArray()).apply {
            selectedItem = old.paper
            name = "page-paper"
        }
        val direction = JComboBox(arrayOf("Portrait", "Landscape")).apply {
            selectedIndex = if (old.landscape)1 else 0
            name = "page-orientation"
        }
        val margin = JTextField(old.marginMm.toString(), MARGIN_FIELD_COLUMNS).apply { name = "page-margin" }
        validatedDialog(
            window,
            "Document page settings — preview, not print qualification",
            "page-settings-dialog",
            listOf("Paper" to size, "Orientation" to direction, "Uniform margins (5–40 mm)" to margin),
            listOf(
                "Apply" to {
                    val n = margin.text.toIntOrNull()
                    when {
                        n == null || n !in MIN_MARGIN_MM..MAX_MARGIN_MM -> "Enter whole margins from 5 to 40 mm."
                        s.viewModel.clipboardCapture() != c -> "Document changed; reopen Page settings."
                        else -> {
                            val p = PageSettings(size.selectedItem as PaperSize, direction.selectedIndex == 1, n)
                            if (p == old ||
                                s.viewModel.dispatch(EditorIntent.ApplyStructure(c, StructureCommand.Pages(p)))
                            ) {
                                null
                            } else {
                                "Page change refused; finish composition and retry."
                            }
                        }
                    }
                },
            ),
        )
        s.requestFocusInWindow()
    }
    fun showRecent(onlyRestore: Boolean = false) {
        val recoveryHints = HashMap<String, String>()
        workspaceWork(window, { scanRecent(recoveryHints) }) { entries ->
            if (onlyRestore && entries.none { it.first.active && !workspaceProcessAlive(it.first) })return@workspaceWork
            RecentDialog(entries, recoveryHints).show()
        }
    }

    private fun scanRecent(recoveryHints: MutableMap<String, String>) =
        store.entries().filter { it.id != id }.map { e -> describeRecent(e, recoveryHints) }

    private fun describeRecent(
        e: WorkspaceEntry,
        recoveryHints: MutableMap<String, String>,
    ): Pair<WorkspaceEntry, FileFingerprint?> {
        val dir = root.resolve(e.recovery)
        val committedAvailable = !Files.exists(dir.resolve("discarded")) &&
            (Files.exists(dir.resolve("copy-0.bin")) || Files.exists(dir.resolve("copy-1.bin")))
        val unfinishedAvailable = runCatching {
            UnfinishedStore(LocalRecoveryStore(root.resolve("unfinished-records"))).entries(e.unfinished).size
        }.getOrDefault(-1)
        recoveryHints[e.id] = "Committed copy ${if (committedAvailable)"available" else "not found"}; " +
            "unfinished ${if (unfinishedAvailable < 0)"needs inspection" else unfinishedAvailable.toString()}"
        return e to (e.path?.let { p -> runCatching { FileFingerprint.capture(Path.of(p)) }.getOrNull() })
    }

    /** Recent documents dialog; every restore is an explicit choice that opens a fresh owner. */
    private inner class RecentDialog(
        entries: List<Pair<WorkspaceEntry, FileFingerprint?>>,
        private val recoveryHints: Map<String, String>,
    ) {
        private val dialog = JDialog(window, "Recent documents / restore last workspace", false)
        private val model = DefaultListModel<Pair<WorkspaceEntry, FileFingerprint?>>().apply {
            entries.forEach { addElement(it) }
        }
        private val list = JList(model).apply {
            name = "workspace-recent-list"
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = RecentEntryRenderer(recoveryHints)
        }
        private val note = JTextArea(
            (store.warning?.plus("\n") ?: "") +
                "Restore is offered, never automatic. Named files are validated. " +
                "Newer accepted and unfinished recovery are separate choices; no old native session resumes.",
            NOTE_ROWS,
            NOTE_COLUMNS,
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        private val bar = JPanel(WrappingControls())

        fun show() {
            bar.add(
                JButton("Restore matching workspace").apply {
                    name = "restore-last-workspace"
                    addActionListener { restoreMatching() }
                },
            )
            action("Open saved file", "recent-open") { e ->
                if (e.path == null) {
                    note.text = "Use Recovery copies for this unsaved owner."
                } else {
                    open(e, Path.of(e.path))
                }
            }
            action("Locate moved file…", "recent-locate") { e ->
                val picker = JFileChooser().apply { dialogTitle = "Locate ${e.name}" }
                if (picker.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                    open(e, picker.selectedFile.toPath(), true)
                }
            }
            action("Committed copies…", "recent-recovery") {
                showLocalRecovery(root, surface().view.typography.id, surface().view.paged)
            }
            action("Unfinished work…", "recent-unfinished") {
                showUnfinishedRecovery(
                    root.resolve("unfinished-records"),
                    surface().view.typography.id,
                    surface().view.paged,
                )
            }
            action("Remove recent", "recent-remove") { e ->
                workspaceWork(dialog, { store.remove(e.id) }) {
                    removeFromList(e)
                    note.text = "Reference removed. File and recovery records preserved."
                }
            }
            bar.add(JButton("Clear recents…").apply { addActionListener { clearRecents() } })
            bar.add(JButton("Later").apply { addActionListener { dialog.dispose() } })
            dialog.contentPane = JPanel(BorderLayout(DIALOG_GAP_PX, DIALOG_GAP_PX)).apply {
                border = BorderFactory.createEmptyBorder(DIALOG_EDGE_PX, DIALOG_EDGE_PX, DIALOG_EDGE_PX, DIALOG_EDGE_PX)
                add(note, BorderLayout.NORTH)
                add(JScrollPane(list), BorderLayout.CENTER)
                add(bar, BorderLayout.SOUTH)
            }
            dialog.setSize(RECENT_WIDTH_PX, RECENT_HEIGHT_PX)
            dialog.setLocationRelativeTo(window)
            dialog.isVisible = true
        }

        private fun action(label: String, name: String, run: (WorkspaceEntry) -> Unit) {
            bar.add(
                JButton(label).apply {
                    this.name = name
                    addActionListener { list.selectedValue?.first?.let(run) }
                },
            )
        }

        private fun removeFromList(e: WorkspaceEntry) {
            (0 until model.size).firstOrNull { model[it].first.id == e.id }?.let { model.remove(it) }
        }

        private fun clearRecents() {
            if (JOptionPane.showConfirmDialog(
                    dialog,
                    "Clear references only? No files or recovery records are deleted.",
                    "Clear recent list",
                    JOptionPane.YES_NO_OPTION,
                ) == JOptionPane.YES_OPTION
            ) {
                workspaceWork(dialog, { store.clear() }) { model.clear() }
            }
        }

        private fun restoreMatching() {
            val candidates = (0 until model.size).map { model[it] }.filter {
                it.first.active &&
                    !workspaceProcessAlive(it.first) &&
                    it.first.path != null &&
                    it.second == it.first.fingerprint &&
                    it.second?.exists == true
            }
            if (candidates.isEmpty()) {
                note.text = "No unchanged named files to restore. " +
                    "Choose Locate/Open or recovery for changed, missing and unsaved entries."
                return
            }
            for ((entry, _) in candidates)open(entry, Path.of(entry.path!!))
            note.text = "Restoring matching named files. " +
                "Changed/missing/unsaved entries remain for explicit recovery choices."
        }

        private fun open(e: WorkspaceEntry, path: Path, located: Boolean = false) {
            workspaceWork(dialog, {
                val d = DocumentFiles.read(path)
                Triple(d, SafeDocumentIO.basis(d, path), workspacePositionsValid(e, d))
            }) { (d, fp, positionsValid) ->
                val matches = fp == e.fingerprint
                if ((!matches || !positionsValid) && !confirmSavedContent(matches)) return@workspaceWork
                val w = showEditor(
                    initial = d,
                    initialPath = path,
                    fontProfile = e.font,
                    paged = e.paged,
                    recoveryDirectory = root,
                )
                applySavedView(w, e, matches && positionsValid)
                // Reference is consumed from the prior offered workspace only after a successful fresh owner.
                workspaceWork(dialog, {
                    store.update(e.copy(active = false, path = if (located)path.toString() else e.path))
                }) {
                    note.text = if (positionsValid) {
                        "Opened saved content; fresh Undo history. Recovery remains separate."
                    } else {
                        "Opened saved content at its beginning: last editing basis differs. " +
                            "Recover newer work separately."
                    }
                }
                removeFromList(e)
            }
        }

        private fun confirmSavedContent(matches: Boolean) = JOptionPane.showConfirmDialog(
            dialog,
            if (!matches) {
                "File content differs or has no saved basis. Open external saved content at its beginning? " +
                    "Recovery remains available separately."
            } else {
                "This is the last saved file, but the last editing basis differs. " +
                    "Open saved content at its beginning? Recover newer accepted/unfinished work separately."
            },
            "Choose saved content or newer recovery",
            JOptionPane.YES_NO_OPTION,
        ) == JOptionPane.YES_OPTION

        private fun applySavedView(w: JFrame, e: WorkspaceEntry, restorePosition: Boolean) {
            val s = findSurface(w)!!
            (w.rootPane.getClientProperty("editor.zoomPicker") as JComboBox<*>).selectedIndex =
                ZOOM_LEVELS.indexOf(e.zoom)
            if (restorePosition) {
                s.viewModel.dispatch(EditorIntent.Select(e.selection))
                if (e.viewportWidth == s.width && e.viewportHeight == s.height) {
                    s.view.scroll = e.scroll
                    s.view.ensureCaret = false
                } else {
                    s.view.ensureCaret = true
                }
            }
        }
    }
}

/** Three-line description of one recent workspace entry. */
private class RecentEntryRenderer(private val recoveryHints: Map<String, String>) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        l: JList<*>?,
        v: Any?,
        i: Int,
        selected: Boolean,
        focus: Boolean,
    ): Component {
        super.getListCellRendererComponent(l, v, i, selected, focus)
        val pair = v as Pair<*, *>
        val e = pair.first as WorkspaceEntry
        val actual = pair.second as FileFingerprint?
        val date = java.time.Instant.ofEpochMilli(e.opened).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm:ss"))
        text = "<html>${htmlEscape(e.name)}<br>" +
            "${htmlEscape(e.path ?: "Untitled — recovery ${e.recovery.take(SHORT_ID_LENGTH)}")}<br>" +
            "${fileState(e, actual)} · ${ownerState(e)} · $date · ${e.id.take(SHORT_ID_LENGTH)}<br>" +
            "${recoveryHints[e.id]}</html>"
        font = Font("Segoe UI", Font.PLAIN, RECENT_FONT_SIZE)
        border = BorderFactory.createEmptyBorder(
            CELL_VERTICAL_PADDING_PX,
            CELL_HORIZONTAL_PADDING_PX,
            CELL_VERTICAL_PADDING_PX,
            CELL_HORIZONTAL_PADDING_PX,
        )
        return this
    }

    private fun fileState(e: WorkspaceEntry, actual: FileFingerprint?) = if (e.path == null) {
        "Unsaved recovery"
    } else if (actual?.exists != true) {
        "Missing / moved"
    } else if (actual != e.fingerprint) {
        "Changed / replaced — saved positions unavailable"
    } else {
        "Saved basis matches"
    }

    private fun ownerState(e: WorkspaceEntry) =
        if (e.active)if (workspaceProcessAlive(e))"Running editor" else "Interrupted workspace" else "Closed"
}
fun htmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun workspaceProcessAlive(e: WorkspaceEntry) =
    runCatching { ProcessHandle.of(e.processId).map { it.isAlive }.orElse(false) }.getOrDefault(true)
