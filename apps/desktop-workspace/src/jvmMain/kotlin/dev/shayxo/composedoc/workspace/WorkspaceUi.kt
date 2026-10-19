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
fun <T> workspaceWork(parent: Component, action: () -> T, success: (T) -> Unit) {
    object : SwingWorker<T, Unit>() {
        override fun doInBackground() = action()
        override fun done() {
            try {
                success(get())
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(parent, e.cause?.message ?: e.message, "Workspace operation failed — work kept", JOptionPane.ERROR_MESSAGE)
            }
        }
    }.execute()
}

class WorkspaceUi(val window: JFrame, val root: Path, val surface: () -> EditorSurface, val files: WindowFiles, val committed: LocalRecoveryController?, val unfinished: UnfinishedController?) {
    val store = WorkspaceStore(root.resolve("workspace"))
    val templates = TemplateStore(root.resolve("templates"))
    val id = UUID.randomUUID().toString()
    private val openedAt = System.currentTimeMillis()
    val panel = JPanel(WrappingControls()).apply {
        isOpaque = false
        name = "workspace-controls"
    }
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "workspace-index").apply { isDaemon = true } }
    private data class Capture(val entry: WorkspaceEntry, val document: Document)
    private var next: Capture? = null
    private var hashedDocument: Document? = null
    private var contentHash = ""
    private var writing = false
    private var closed = false
    private val timer = Timer(1800) { observe() }.apply { start() }
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
        button("Unfinished recovery…", "workspace-unfinished") { showUnfinishedRecovery(root.resolve("unfinished-records"), surface().view.typography.id, surface().view.paged) }
        button("File status / conflict…", "workspace-conflict") {
            if (files.conflict != null) {
                files.showConflict()
            } else {
                JOptionPane.showMessageDialog(window, "${files.path ?: "Untitled — no named file"}\n${files.status}\nLast opened/saved bytes: ${files.fingerprint ?: "No disk basis"}\nSave revalidates the current destination; automatic recovery never overwrites it.", "Document file status", JOptionPane.INFORMATION_MESSAGE)
            }
        }
        window.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control N"), "new-template")
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
                rs.acquire(target).use { lease -> rs.publish(lease, LocalCopy(target, 1, System.currentTimeMillis(), files.path?.toString(), doc)) }
                Unit
            }) { done() }
        }
    }
    fun observe() {
        if (closed || committed == null || unfinished == null)return
        val s = surface()
        val f = s.engine.snapshot()
        val e = WorkspaceEntry(
            id, files.path?.toAbsolutePath()?.normalize()?.toString(), files.name.take(512), files.fingerprint, openedAt, committed.session, unfinished.owner,
            if (f.marked == null && f.visible.same(f.committed))f.selection else Selection(0, 0), s.view.scroll.coerceAtLeast(0f), s.view.paged, s.view.zoom, s.view.typography.id, true, viewportWidth = s.width.coerceIn(0, 16384), viewportHeight = s.height.coerceIn(0, 16384),
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
        executor.execute { if (deliberate)store.entries().firstOrNull { it.id == id }?.let { store.update(it.copy(active = false)) } }
        executor.shutdown()
    }
    private fun newWindow(d: Document): JFrame = showEditor(initial = d, fontProfile = surface().view.typography.id, paged = surface().view.paged, recoveryDirectory = root).also { windowFiles(it)!!.markUnsavedCopy("New from template") }
    fun chooseTemplate() {
        workspaceWork(window, { templates.list() }) { entries ->
            val dialog = JDialog(window, "Create from template — independent unsaved copy", true)
            val model = DefaultListModel<TemplateEntry>()
            entries.forEach(model::addElement)
            val list = JList(model).apply {
                name = "template-list"
                selectedIndex = 0
                visibleRowCount = 7
                font = Font("Segoe UI", Font.PLAIN, 16)
            }
            val status = JLabel("Original sample text; no expert linguistic review")
            val create = JButton("Create unsaved copy").apply {
                name = "template-create"
                addActionListener {
                    val entry = list.selectedValue ?: return@addActionListener
                    isEnabled = false
                    object : SwingWorker<Document, Unit>() {
                        override fun doInBackground() = templates.create(entry)
                        override fun done() {
                            try {
                                newWindow(get())
                                dialog.dispose()
                            } catch (e: Exception) {
                                status.text = "Template kept; cannot create: ${e.cause?.message ?: e.message}"
                            } finally {
                                isEnabled = true
                            }
                        }
                    }.execute()
                }
            }
            val remove = JButton("Remove user template…").apply {
                addActionListener {
                    val e = list.selectedValue ?: return@addActionListener
                    if (e.builtIn) {
                        status.text = "Built-in templates cannot be removed"
                        return@addActionListener
                    }
                    if (JOptionPane.showConfirmDialog(dialog, "Remove this private template? Source documents and created copies remain.", "Remove template", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        workspaceWork(dialog, { templates.remove(e) }) { model.removeElement(e) }
                    }
                }
            }
            dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
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
    private fun saveTemplate() {
        val s = surface()
        val c = s.engine.clipboardCapture() ?: return
        val label = JTextField("My Urdu template", 28).apply { name = "template-name" }
        val dialog = JDialog(window, "Save as reusable template", true).apply { name = "save-template-dialog" }
        val error = JLabel(" ").apply { foreground = Color(0xb3261e) }
        val save = JButton("Save template").apply { name = "save-template" }
        save.addActionListener {
            val message = when {
                label.text.isBlank() || label.text.length > 128 -> "Enter a name of 1–128 units."
                s.engine.clipboardCapture() != c -> "Document changed; cancel and reopen Save as Template."
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
        dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
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
            JOptionPane.showMessageDialog(window, "Finish file work or recover paused input before changing page settings.")
            return
        }
        val c = s.engine.clipboardCapture() ?: run {
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
        val margin = JTextField(old.marginMm.toString(), 6).apply { name = "page-margin" }
        validatedDialog(
            window,
            "Document page settings — preview, not print qualification",
            "page-settings-dialog",
            listOf("Paper" to size, "Orientation" to direction, "Uniform margins (5–40 mm)" to margin),
            listOf(
                "Apply" to {
                    val n = margin.text.toIntOrNull()
                    when {
                        n == null || n !in 5..40 -> "Enter whole margins from 5 to 40 mm."
                        s.engine.clipboardCapture() != c -> "Document changed; reopen Page settings."
                        else -> {
                            val p = PageSettings(size.selectedItem as PaperSize, direction.selectedIndex == 1, n)
                            if (p == old || s.engine.applyStructure(c, StructureCommand.Pages(p)))null else "Page change refused; finish composition and retry."
                        }
                    }
                },
            ),
        )
        s.requestFocusInWindow()
    }
    fun showRecent(onlyRestore: Boolean = false) {
        val recoveryHints = HashMap<String, String>()
        workspaceWork(window, {
            store.entries().filter { it.id != id }.map { e ->
                val dir = root.resolve(e.recovery)
                val committedAvailable = !Files.exists(dir.resolve("discarded")) && (Files.exists(dir.resolve("copy-0.bin")) || Files.exists(dir.resolve("copy-1.bin")))
                val unfinishedAvailable = runCatching { UnfinishedStore(LocalRecoveryStore(root.resolve("unfinished-records"))).entries(e.unfinished).size }.getOrDefault(-1)
                recoveryHints[e.id] = "Committed copy ${if (committedAvailable)"available" else "not found"}; unfinished ${if (unfinishedAvailable < 0)"needs inspection" else unfinishedAvailable.toString()}"
                e to (e.path?.let { p -> runCatching { FileFingerprint.capture(Path.of(p)) }.getOrNull() })
            }
        }) { entries ->
            if (onlyRestore && entries.none { it.first.active && !workspaceProcessAlive(it.first) })return@workspaceWork
            val dialog = JDialog(window, "Recent documents / restore last workspace", false)
            val model = DefaultListModel<Pair<WorkspaceEntry, FileFingerprint?>>()
            entries.forEach(model::addElement)
            val list = JList(model).apply {
                name = "workspace-recent-list"
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                cellRenderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(l: JList<*>?, v: Any?, i: Int, selected: Boolean, focus: Boolean): Component {
                        super.getListCellRendererComponent(l, v, i, selected, focus)
                        val pair = v as Pair<*, *>
                        val e = pair.first as WorkspaceEntry
                        val actual = pair.second as FileFingerprint?
                        val date = java.time.Instant.ofEpochMilli(e.opened).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm:ss"))
                        text = "<html>${htmlEscape(e.name)}<br>${htmlEscape(e.path ?: "Untitled — recovery ${e.recovery.take(8)}")}<br>${if (e.path == null){
                            "Unsaved recovery"
                        } else if (actual?.exists != true){
                            "Missing / moved"
                        } else if (actual != e.fingerprint){
                            "Changed / replaced — saved positions unavailable"
                        } else {
                            "Saved basis matches"
                        }} · ${if (e.active)if (workspaceProcessAlive(e))"Running editor" else "Interrupted workspace" else "Closed"} · $date · ${e.id.take(8)}<br>${recoveryHints[e.id]}</html>"
                        font = Font("Segoe UI", Font.PLAIN, 15)
                        border = BorderFactory.createEmptyBorder(7, 8, 7, 8)
                        return this
                    }
                }
            }
            val note = JTextArea((store.warning?.plus("\n") ?: "") + "Restore is offered, never automatic. Named files are validated. Newer accepted and unfinished recovery are separate choices; no old native session resumes.", 3, 50).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            val bar = JPanel(WrappingControls())
            fun action(label: String, name: String, run: (WorkspaceEntry) -> Unit) {
                bar.add(
                    JButton(label).apply {
                        this.name = name
                        addActionListener { list.selectedValue?.first?.let(run) }
                    },
                )
            }
            fun open(e: WorkspaceEntry, path: Path, located: Boolean = false) {
                workspaceWork(dialog, {
                    val d = DocumentFiles.read(path)
                    Triple(d, SafeDocumentIO.basis(d, path), workspacePositionsValid(e, d))
                }) { (d, fp, positionsValid) ->
                    val p = path
                    val matches = fp == e.fingerprint
                    if ((!matches || !positionsValid) &&
                        JOptionPane.showConfirmDialog(
                            dialog,
                            if (!matches) {
                                "File content differs or has no saved basis. Open external saved content at its beginning? Recovery remains available separately."
                            } else {
                                "This is the last saved file, but the last editing basis differs. Open saved content at its beginning? Recover newer accepted/unfinished work separately."
                            },
                            "Choose saved content or newer recovery",
                            JOptionPane.YES_NO_OPTION,
                        ) != JOptionPane.YES_OPTION
                    ) {
                        return@workspaceWork
                    }
                    val w = showEditor(initial = d, initialPath = p, fontProfile = e.font, paged = e.paged, recoveryDirectory = root)
                    val s = findSurface(w)!!
                    (w.rootPane.getClientProperty("editor.zoomPicker") as JComboBox<*>).selectedIndex = listOf(.5f, .75f, 1f).indexOf(e.zoom)
                    if (matches && positionsValid) {
                        s.engine.move(e.selection)
                        if (e.viewportWidth == s.width && e.viewportHeight == s.height) {
                            s.view.scroll = e.scroll
                            s.view.ensureCaret = false
                        } else {
                            s.view.ensureCaret = true
                        }
                    }
                    // Reference is consumed from the prior offered workspace only after a successful fresh owner.
                    workspaceWork(dialog, { store.update(e.copy(active = false, path = if (located)p.toString() else e.path)) }) { note.text = if (positionsValid)"Opened saved content; fresh Undo history. Recovery remains separate." else "Opened saved content at its beginning: last editing basis differs. Recover newer work separately." }
                    (0 until model.size).firstOrNull { model[it].first.id == e.id }?.let { model.remove(it) }
                }
            }
            bar.add(
                JButton("Restore matching workspace").apply {
                    name = "restore-last-workspace"
                    addActionListener {
                        val candidates = (0 until model.size).map { model[it] }.filter { it.first.active && !workspaceProcessAlive(it.first) && it.first.path != null && it.second == it.first.fingerprint && it.second?.exists == true }
                        if (candidates.isEmpty()) {
                            note.text = "No unchanged named files to restore. Choose Locate/Open or recovery for changed, missing and unsaved entries."
                            return@addActionListener
                        }
                        for ((entry, _) in candidates)open(entry, Path.of(entry.path!!))
                        note.text = "Restoring matching named files. Changed/missing/unsaved entries remain for explicit recovery choices."
                    }
                },
            )
            action("Open saved file", "recent-open") { e -> if (e.path == null)note.text = "Use Recovery copies for this unsaved owner." else open(e, Path.of(e.path)) }
            action("Locate moved file…", "recent-locate") { e ->
                val picker = JFileChooser().apply { dialogTitle = "Locate ${e.name}" }
                if (picker.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION)open(e, picker.selectedFile.toPath(), true)
            }
            action("Committed copies…", "recent-recovery") { showLocalRecovery(root, surface().view.typography.id, surface().view.paged) }
            action("Unfinished work…", "recent-unfinished") { showUnfinishedRecovery(root.resolve("unfinished-records"), surface().view.typography.id, surface().view.paged) }
            action("Remove recent", "recent-remove") { e ->
                workspaceWork(dialog, { store.remove(e.id) }) {
                    (0 until model.size).firstOrNull { model[it].first.id == e.id }?.let { model.remove(it) }
                    note.text = "Reference removed. File and recovery records preserved."
                }
            }
            bar.add(JButton("Clear recents…").apply { addActionListener { if (JOptionPane.showConfirmDialog(dialog, "Clear references only? No files or recovery records are deleted.", "Clear recent list", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)workspaceWork(dialog, { store.clear() }) { model.clear() } } })
            bar.add(JButton("Later").apply { addActionListener { dialog.dispose() } })
            dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                add(note, BorderLayout.NORTH)
                add(JScrollPane(list), BorderLayout.CENTER)
                add(bar, BorderLayout.SOUTH)
            }
            dialog.setSize(840, 480)
            dialog.setLocationRelativeTo(window)
            dialog.isVisible = true
        }
    }
}
fun htmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun workspaceProcessAlive(e: WorkspaceEntry) = runCatching { ProcessHandle.of(e.processId).map { it.isAlive }.orElse(false) }.getOrDefault(true)
