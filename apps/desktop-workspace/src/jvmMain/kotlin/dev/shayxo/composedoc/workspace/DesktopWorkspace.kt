@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.workspace

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.text.*
import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import java.io.File
import javax.accessibility.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.*

fun windowFiles(window: JFrame): WindowFiles? = window.rootPane?.getClientProperty("editor.files") as? WindowFiles
fun windowLayeredPane(window: JFrame): JLayeredPane? = window.rootPane?.getClientProperty("editor.layers") as? JLayeredPane

@Suppress("UNCHECKED_CAST")
fun windowCurrentSurface(window: JFrame): EditorSurface? = (window.rootPane?.getClientProperty("editor.surfaceGetter") as? (() -> EditorSurface))?.invoke()

@JvmOverloads
fun showEditor(diagnostics: File? = null, initial: Document = Document(emptyList()), initialPath: java.nio.file.Path? = null, trace: ImeTrace? = null, traceLanguage: String? = null, offscreenTest: Boolean = false, windowLocation: Point? = null, fontProfile: String = "system", paged: Boolean = false, recoveryDirectory: java.nio.file.Path? = null): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    val engine = EditorEngine(initial)
    val state = ViewState(engine.snapshot())
    var surface = EditorSurface(engine, state, diagnostics, trace, services = EditorPlatformServices(DocumentFiles, FragmentCodec))
    var refreshRecoveryUi: () -> Unit = {}
    var refreshFindUi: () -> Unit = {}
    var refreshParagraphUi: () -> Unit = {}
    var refreshWritingUi: () -> Unit = {}
    var refreshStructureUi: () -> Unit = {}
    surface.onHostReplaced = {
        surface = it
        refreshRecoveryUi()
        refreshFindUi()
        refreshParagraphUi()
        refreshWritingUi()
        refreshStructureUi()
    }
    state.changeTypography(TypographyProfile.load(fontProfile))
    state.setPagedMode(paged)
    val window = JFrame("Astra Editor — Desktop Prototype")
    lateinit var files: WindowFiles
    var localRecovery: LocalRecoveryController? = null
    var unfinishedRecovery: UnfinishedController? = null
    var workspaceUi: WorkspaceUi? = null
    var approvedClose = false
    val compose = ComposePanel(renderSettings = RenderSettings.SwingGraphics()).apply {
        isFocusable = false
        setContent { DocumentCanvas(state) }
    }
    val layers = object : JLayeredPane() {
        override fun doLayout() {
            if (state.hostWidth != width || state.hostHeight != height) {
                state.hostWidth = width
                state.hostHeight = height
                state.geometry = null
                state.ensureCaret = true
            }
            compose.setBounds(0, 0, width, height)
            surface.setBounds(0, 0, width, height)
        }
        override fun getPreferredSize() = Dimension(1020, 640)
    }
    // Kotlin otherwise selects add(Component, index), not add(Component, layer).
    layers.add(compose)
    layers.setLayer(compose, 0)
    layers.add(surface)
    layers.setLayer(surface, 100)
    val bg = Color(0xfafcff)
    val header = JPanel(BorderLayout()).apply {
        background = bg
        border = EmptyBorder(6, 12, 6, 12)
    }
    val title = JLabel("Untitled").apply {
        font = Font("Segoe UI", Font.BOLD, 16)
        foreground = Color(0x20304a)
    }
    val subtitle = JLabel("Astra editor  /  Desktop prototype").apply {
        font = Font("Segoe UI", Font.PLAIN, 12)
        foreground = Color(0x64748b)
    }
    // Window title already identifies the file; avoid two permanent blank header rows.
    title.isVisible = false
    subtitle.isVisible = false
    val qualificationControls = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    val fontPicker = JComboBox(TypographyProfile.labels.values.toTypedArray()).apply {
        selectedItem = TypographyProfile.labels.getValue(fontProfile)
        toolTipText = "Experimental typography candidates; selection does not change document text or formatting"
    }
    val fontStatus = JLabel("Candidate fonts; typography review pending")
    var revertingFontSelection = false
    fontPicker.addActionListener {
        if (revertingFontSelection) return@addActionListener
        val id = TypographyProfile.labels.entries.single { it.value == fontPicker.selectedItem }.key
        runCatching { TypographyProfile.load(id) }.onSuccess { profile ->
            surface.changeTypography(profile)
            fontStatus.text = if (id == "system") "Unpinned system fonts" else "Pinned candidate; review pending"
            trace?.record("typography-profile", "key=${profile.key}; sizeSp=${profile.sizeSp}", engine.snapshot())
            surface.repaint()
        }.onFailure { failure ->
            revertingFontSelection = true
            fontPicker.selectedItem = state.typography.label
            revertingFontSelection = false
            fontStatus.text = "Font unavailable; previous profile retained"
            trace?.record("typography-profile-rejected", failure.message ?: "Font unavailable", engine.snapshot())
        }
        surface.requestFocusInWindow()
    }
    fitCombo(fontPicker)
    val typefaceRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
        add(JLabel("Preview"))
        add(fontPicker)
        add(fontStatus)
    }
    qualificationControls.add(typefaceRow)
    val pageToggle = JCheckBox("Pages (experiment)", paged).apply {
        isOpaque = false
        name = "page-mode"
        toolTipText = "Switch continuous/paged presentation; content is unchanged"
    }
    pageToggle.addActionListener {
        state.setPagedMode(pageToggle.isSelected)
        surface.requestFocusInWindow()
    }
    qualificationControls.add(
        JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            isOpaque = false
            add(pageToggle)
            add(JLabel("Enter: paragraph · Shift+Enter: line break · Ctrl+S: save · Wheel: scroll").apply { font = Font("Segoe UI", Font.PLAIN, 12) })
            if (recoveryDirectory != null) {
                add(
                    JButton("Recovery Copies…").apply {
                        isFocusable = false
                        addActionListener { showLocalRecovery(recoveryDirectory, state.typography.id, state.paged) }
                    },
                )
            }
        },
    )
    header.add(qualificationControls, BorderLayout.SOUTH)
    if (trace != null) {
        subtitle.text = "IME test: test text is logged locally"
        val languages = linkedMapOf("English" to "en-US", "Japanese" to "ja-JP", "Urdu" to "ur-PK", "Arabic" to "ar-SA")
        val picker = JComboBox(languages.keys.toTypedArray())
        val result = JLabel("Choose an installed input method; then type test text.")
        picker.addActionListener {
            val tag = languages.getValue(picker.selectedItem as String)
            val accepted = surface.selectInputLocale(java.util.Locale.forLanguageTag(tag), compositionEnabled = if (tag == "ja-JP") true else null)
            val ctx = surface.inputContext
            val compositionEnabled = if (accepted && tag == "ja-JP") runCatching { ctx?.isCompositionEnabled }.getOrNull() else null
            result.text = "Input: $tag — ${if (accepted)"selected" else "unavailable"}"
            trace.record("select-input", "requested=$tag; accepted=$accepted; actual=${ctx?.locale}; compositionEnabled=$compositionEnabled", engine.snapshot())
            surface.requestFocusInWindow()
        }
        if (traceLanguage != null) SwingUtilities.invokeLater { picker.selectedItem = languages.entries.first { it.value == traceLanguage }.key }
        qualificationControls.add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                isOpaque = false
                add(picker)
                add(result)
            },
        )
        trace.record("session-start", "Live input test mode; no synthetic injection. OS=${System.getProperty("os.name")}; Java=${System.getProperty("java.version")}", engine.snapshot())
    }
    val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
    fun button(label: String, tooltip: String, action: () -> Unit) = JButton(label).apply {
        isFocusable = false
        font = Font("Segoe UI", Font.PLAIN, 13)
        toolTipText = tooltip
        addActionListener {
            action()
            surface.requestFocusInWindow()
        }
        buttons.add(this)
    }
    val open = button("Open", "Open a document in a new window (Ctrl+O)") { files.open() }
    val save = button("Save", "Save document (Ctrl+S); Save As: Ctrl+Shift+S") { files.save() }
    val saveAs = button("Save As…", "Save to another .astra file (Ctrl+Shift+S)") { files.save(asNew = true) }
    val undo = button("Undo", "Ctrl+Z") { engine.perform(EditAction.Undo) }
    val redo = button("Redo", "Ctrl+Shift+Z or Ctrl+Y") { engine.perform(EditAction.Redo) }
    val bold = button("Bold", "Toggle bold (Ctrl+B)") { if (surface.admissionAllowed) engine.toggleFormat(FormatKey.BOLD) }
    val italic = button("Italic", "Toggle italic (Ctrl+I)") { if (surface.admissionAllowed) engine.toggleFormat(FormatKey.ITALIC) }
    button("Copy", "Copy rich + plain selected committed text (Ctrl+C)") { surface.copySelection(false) }
    button("Cut", "Publish clipboard then remove captured selection (Ctrl+X)") { surface.copySelection(true) }
    val pasteBtn = button("Paste", "Paste rich when available (Ctrl+V); plain: Ctrl+Shift+V") { surface.paste() }
    button("Paste Plain", "Ignore rich fragment (Ctrl+Shift+V)") { surface.paste(true) }
    val recoveryBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        isOpaque = false
        name = "recovery-controls"
        isVisible = false
    }
    recoveryBar.add(JLabel("Recovery:"))
    val draftPicker = JComboBox<String>().apply {
        name = "recovery-picker"
        preferredSize = Dimension(180, 28)
        toolTipText = "Choose a retained recovery draft"
    }
    recoveryBar.add(draftPicker)
    var shownDrafts: List<RetainedCompositionPreimage> = emptyList()
    var updatingDraftPicker = false
    draftPicker.addActionListener { if (!updatingDraftPicker) shownDrafts.getOrNull(draftPicker.selectedIndex)?.let { surface.selectRetainedDraft(it) } }
    fun recoveryButton(label: String, tooltip: String, action: () -> Unit) = button(label, tooltip, action).also {
        buttons.remove(it)
        recoveryBar.add(it)
    }
    val inspectDraft = recoveryButton("Inspect", "Preview the selected retained document text") {
        val text = surface.retainedDraftDocument()?.text ?: surface.inspectRetainedDraft() ?: return@recoveryButton
        val area = javax.swing.JTextArea(text, 15, 48).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            caretPosition = 0
        }
        javax.swing.JOptionPane.showMessageDialog(window, javax.swing.JScrollPane(area), "Retained draft text — export preserves formatting", javax.swing.JOptionPane.INFORMATION_MESSAGE)
    }
    val exportDraft = recoveryButton("Export Draft", "Save the full retained document separately; keep this recovery record") {
        surface.retainedDraftDocument()?.let { files.exportDraftDocument(it) }
    }
    val aBtn = recoveryButton("Apply Draft", "Apply the selected draft only to its matching original basis") {
        val selected = surface.retainedPreimage
        val durable = unfinishedRecovery
        if (durable == null || selected == null) {
            surface.applyRecoveredDraft()
        } else {
            durable.resolveLive(selected.recordKey, apply = {
                val draft = selected.recoveryDraft
                if (draft != null && surface.engine.applyRecoveredDraft(draft) == RecoveryApplyResult.Applied)surface.engine.snapshot().committed else null
            }, remove = {
                surface.recoverySession.resolveRetainedDraft(selected)
                surface.statusChanged(engine.snapshot())
            })
        }
    }
    val dBtn = recoveryButton("Discard Draft", "Explicitly discard only the selected retained draft") {
        val selected = surface.retainedPreimage
        if (unfinishedRecovery == null || selected == null) {
            surface.discardRecoveredDraft()
        } else {
            unfinishedRecovery!!.resolveLive(selected.recordKey, remove = {
                surface.recoverySession.resolveRetainedDraft(selected)
                surface.statusChanged(engine.snapshot())
            })
        }
    }
    val rBtn = recoveryButton("Recover", "Recover editor input after failure") { surface.recoverExplicitly(false) }
    qualificationControls.add(recoveryBar)
    val quarantineBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        isOpaque = false
        name = "quarantine-controls"
    }
    val quarantineLabel = JLabel()
    quarantineBar.add(quarantineLabel)
    fun quarantineButton(label: String, action: () -> Unit) = button(label, label, action).also {
        buttons.remove(it)
        quarantineBar.add(it)
    }
    quarantineButton("Inspect Input") {
        val captured = surface.quarantinedCallbacks.toList()
        val area = JTextArea(quarantineDiagnostic(captured), 16, 56).apply {
            isEditable = false
            lineWrap = true
            caretPosition = 0
        }
        JOptionPane.showMessageDialog(window, JScrollPane(area), "Unverified input — never applied to the document", JOptionPane.INFORMATION_MESSAGE)
    }
    val exportInput = quarantineButton("Export Input") { files.exportQuarantinedInput(surface.quarantinedCallbacks.toList()) }
    val discardInput = quarantineButton("Discard Input…") {
        val captured = surface.quarantinedCallbacks.toList()
        if (files.confirmQuarantineDiscard(captured.size)) {
            val durable = unfinishedRecovery
            if (durable == null) {
                surface.discardQuarantinedCallbacks(captured)
            } else {
                captured.forEach { entry -> durable.resolveLive(entry, remove = { surface.discardQuarantinedCallbacks(listOf(entry)) }) }
            }
        }
    }
    qualificationControls.add(quarantineBar)
    refreshRecoveryUi = {
        val records = surface.recoverySession.snapshotRetainedDrafts()
        val changed = records.size != shownDrafts.size || records.zip(shownDrafts).any { (a, b) -> a !== b }
        updatingDraftPicker = true
        if (changed) {
            shownDrafts = records
            draftPicker.removeAllItems()
            records.forEachIndexed { index, record ->
                val preview = record.draftText.replace('\n', ' ').take(20).ifEmpty { "(empty draft)" }
                draftPicker.addItem("${index + 1}/${records.size} · $preview")
            }
        }
        draftPicker.selectedIndex = records.indexOfFirst { it === surface.retainedPreimage }
        updatingDraftPicker = false
        val wasVisible = recoveryBar.isVisible
        recoveryBar.isVisible = records.isNotEmpty() || !surface.admissionAllowed
        listOf(draftPicker, inspectDraft, exportDraft, aBtn, dBtn).forEach { it.isVisible = records.isNotEmpty() }
        draftPicker.isEnabled = records.isNotEmpty() && !files.busy
        inspectDraft.isEnabled = records.isNotEmpty()
        exportDraft.isEnabled = surface.retainedDraftDocument() != null && !files.busy
        aBtn.isEnabled = surface.retainedPreimage?.recoveryDraft != null && !files.busy && unfinishedRecovery?.decisionBusy() != true
        dBtn.isEnabled = records.isNotEmpty() && !files.busy && unfinishedRecovery?.decisionBusy() != true
        rBtn.isVisible = !surface.admissionAllowed
        rBtn.isEnabled = records.size + surface.quarantinedCallbacks.size < 16 && !files.busy
        rBtn.toolTipText = "Recover paused input; at capacity export/discard unresolved work first"
        val quarantineVisible = surface.quarantinedCallbacks.isNotEmpty()
        val quarantineChanged = quarantineBar.isVisible != quarantineVisible
        quarantineBar.isVisible = quarantineVisible
        quarantineLabel.text = "Unverified input: ${surface.quarantinedCallbacks.size} callback(s)"
        exportInput.isEnabled = !files.busy
        discardInput.isEnabled = !files.busy && unfinishedRecovery?.decisionBusy() != true
        if (changed || quarantineChanged || wasVisible != recoveryBar.isVisible) {
            header.revalidate()
            header.repaint()
        }
    }
    val fileRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
        add(JLabel("File / edit"))
    }
    buttons.components.toList().forEach { fileRow.add(it) }
    qualificationControls.add(fileRow, 0)
    val footer = JPanel(BorderLayout()).apply {
        background = bg
        border = EmptyBorder(3, 16, 3, 16)
    }
    val position = JLabel()
    val notice = JLabel()
    listOf(position, notice).forEach {
        it.font = Font("Segoe UI", Font.PLAIN, 12)
        it.foreground = Color(0x64748b)
    }
    val unfinishedStatus = JLabel().apply {
        name = "unfinished-status"
        font = Font("Segoe UI", Font.PLAIN, 12)
    }
    val footerLines = JPanel(GridLayout(3, 1)).apply {
        isOpaque = false
        add(position)
        add(notice)
        add(unfinishedStatus)
    }
    footer.add(footerLines, BorderLayout.CENTER)
    val localRecoveryStatus = JLabel().apply {
        font = Font("Segoe UI", Font.PLAIN, 12)
        foreground = Color(0x64748b)
    }
    footer.add(localRecoveryStatus, BorderLayout.EAST)
    files = WindowFiles(window, engine, initialPath, onDiscardComposition = {
        val host = surface
        host.abortAndRetireComposition(replaceHost = false)
        // The close choice explicitly discarded this active preedit. Remove only the
        // capsule created for that abort; other/reentrant recovery work stays pending.
        host.lastRecoveryReport?.retainedPreimage?.let { host.recoverySession.resolveRetainedDraft(it) }
    }) { surface.statusChanged(engine.snapshot()) }
    files.hasRetainedRecovery = { surface.recoverySession.snapshotRetainedDrafts().isNotEmpty() || surface.quarantinedCallbacks.isNotEmpty() }
    files.pendingRecoveryDescription = { "${surface.recoverySession.snapshotRetainedDrafts().size} retained draft(s) and ${surface.quarantinedCallbacks.size} unverified input callback(s)" }
    files.openInNewWindow = { document, source ->
        showEditor(initial = document, initialPath = source, fontProfile = state.typography.id, paged = state.paged, offscreenTest = offscreenTest, recoveryDirectory = recoveryDirectory)
        Unit
    }
    files.discardRetainedRecovery = { surface.discardRecoverySession() }
    files.captureRecoveryDiscard = {
        val session = surface.recoverySession
        val drafts = session.snapshotRetainedDrafts()
        val callbacks = session.quarantinedCallbacks.toList()
        val discard: () -> Unit = {
            drafts.forEach { session.resolveRetainedDraft(it) }
            session.quarantinedCallbacks.removeIf { current -> callbacks.any { it === current } }
            surface.writeDiagnostics()
            surface.statusChanged(engine.snapshot())
        }
        discard
    }
    surface.requestSave = { files.save(it) }
    surface.requestOpen = { files.open() }
    surface.statusChanged = { f ->
        val counts = writingCounts(f)
        position.text = "${counts.words} word-like runs · ${counts.units} UTF-16 units · ${counts.selected} selected units"
        position.toolTipText = "Committed counts; whitespace separates runs containing letters/digits/symbols. Joiners/marks stay in runs; not linguistic tokenization. Selected count is visible UTF-16 range."
        localRecovery?.let { recovery ->
            recovery.observe(f.committed, files.path)
            unfinishedRecovery?.observe(engine, surface.recoverySession, files.path, surface.latestRecoveryProposal)
            localRecoveryStatus.text = recovery.status()
            localRecoveryStatus.toolTipText = recovery.detail() + " · " + (unfinishedRecovery?.status() ?: "")
        }
        unfinishedStatus.text = (surface.recoveryCaptureFailure ?: unfinishedRecovery?.status() ?: "") + (workspaceUi?.warning?.let { " · $it" } ?: "")
        unfinishedStatus.toolTipText = unfinishedStatus.text
        unfinishedStatus.foreground = if (unfinishedStatus.text.contains("Failed"))Color(0xb3261e) else Color(0x64748b)
        refreshRecoveryUi()
        refreshFindUi()
        refreshParagraphUi()
        refreshWritingUi()
        refreshStructureUi()
        val degradedNote = if (!surface.admissionAllowed && surface.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED) " · Input paused (degraded recovery) — Save available" else ""
        notice.text = files.status + (if (f.notice == "Ready")"" else " · ${f.notice}") + degradedNote + (if (surface.clipboardMessage.isEmpty()) "" else " · ${surface.clipboardMessage}") + (if (StoredFonts.unavailable.isEmpty()) "" else " · Missing stored font: System fallback")
        notice.toolTipText = notice.text
        title.text = files.name
        title.toolTipText = files.path?.toString()
        window.title = "${files.name} — Astra Editor"
        open.isEnabled = !files.busy
        save.isEnabled = !files.busy
        undo.isEnabled = f.undoCount > 0 || f.marked != null
        redo.isEnabled = f.redoCount > 0 && f.marked == null
        pasteBtn.isEnabled = !files.busy && surface.admissionAllowed && !surface.clipboardPasteBusy
        saveAs.isEnabled = !files.busy
        bold.isEnabled = !files.busy && surface.admissionAllowed
        italic.isEnabled = !files.busy && surface.admissionAllowed
        fun label(name: String, key: FormatKey) = when (engine.formatState(key)) {
            true -> "$name on"
            false -> name
            null -> "$name mixed"
        }
        bold.text = label("Bold", FormatKey.BOLD)
        italic.text = label("Italic", FormatKey.ITALIC)
    }
    val findUi = FindReplaceUi(window, { surface }) {
        header.revalidate()
        header.repaint()
    }
    qualificationControls.add(findUi.panel)
    refreshFindUi = { findUi.refresh() }
    window.rootPane.putClientProperty("editor.find", findUi)
    val paragraphUi = ParagraphControls({ surface }, { files.busy })
    val paragraphRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
        add(JLabel("Paragraph"))
        add(paragraphUi.panel)
    }
    qualificationControls.add(paragraphRow)
    val writingUi = WritingControls({ surface }, { files.busy })
    // Tabs keep every group reachable at the existing 940×400 minimum.
    val tabs = WritingTabs().apply { name = "writing-tabs" }
    val textGroup = writingUi.text.apply {
        add(italic, 0)
        add(bold, 0)
    }
    val paraGroup = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(paragraphRow)
        add(writingUi.spacing)
    }
    val viewGroup = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(typefaceRow)
    }
    val pageRow = pageToggle.parent
    viewGroup.add(pageRow)
    tabs.addTab("Text", textGroup)
    tabs.addTab("Paragraph", paraGroup)
    tabs.addTab("View", viewGroup)
    val structureUi = StructureControls(window, { surface }, { files.busy })
    tabs.addTab("Structure", structureUi.panel)
    val outputUi = PageOutputUi(window, { surface }, { files.busy })
    tabs.addTab("Pages / Output", outputUi.panel)
    window.rootPane.putClientProperty("editor.pageOutput", outputUi)
    refreshStructureUi = { structureUi.refresh() }
    window.rootPane.putClientProperty("editor.structure", structureUi)
    qualificationControls.remove(paragraphRow)
    qualificationControls.add(tabs, 1)
    window.rootPane.putClientProperty("editor.writingTabs", tabs)
    tabs.addChangeListener { refreshWritingLayout(window, tabs.selectedComponent as? JComponent) }

    refreshWritingUi = { writingUi.refresh() }
    window.rootPane.putClientProperty("editor.writingControls", writingUi)
    val zoomPicker = JComboBox(arrayOf("50%", "75%", "100%")).apply {
        name = "document-zoom"
        selectedIndex = 2
        toolTipText = "Presentation zoom; document metrics, stored formatting and Undo are unchanged"
    }
    zoomPicker.addActionListener {
        state.changeZoom(listOf(.5f, .75f, 1f)[zoomPicker.selectedIndex])
        surface.requestFocusInWindow()
    }
    fitCombo(zoomPicker)
    window.rootPane.putClientProperty("editor.zoomPicker", zoomPicker)
    typefaceRow.add(JLabel("Zoom"))
    typefaceRow.add(zoomPicker)
    refreshParagraphUi = { paragraphUi.refresh() }
    window.rootPane.putClientProperty("editor.paragraphControls", paragraphUi)
    if (recoveryDirectory != null) {
        localRecovery = LocalRecoveryController(LocalRecoveryStore(recoveryDirectory), refresh = { surface.statusChanged(engine.snapshot()) })
        unfinishedRecovery = UnfinishedController(
            UnfinishedStore(LocalRecoveryStore(recoveryDirectory.resolve("unfinished-records"))),
            localRecovery!!.session,
            refresh = { surface.statusChanged(engine.snapshot()) },
            failed = { surface.pauseForRecoveryCapture(it) },
        )
        files.captureDurableDiscardBeforeClose = {
            unfinishedRecovery!!.observe(engine, surface.recoverySession, files.path)
            unfinishedRecovery!!.captureDiscardBeforeClose()
        }
        workspaceUi = WorkspaceUi(window, recoveryDirectory, { surface }, files, localRecovery, unfinishedRecovery)
        tabs.addTab("Workspace", workspaceUi!!.panel)
        files.onApprovedClose = {
            approvedClose = true
            workspaceUi?.close(true)
            unfinishedRecovery?.observe(engine, surface.recoverySession, files.path, surface.latestRecoveryProposal)
            unfinishedRecovery?.close()
            localRecovery?.finish(discard = true)
        }
        window.rootPane.putClientProperty("editor.workspace", workspaceUi)
        window.rootPane.putClientProperty("editor.unfinishedRecovery", unfinishedRecovery)
    }
    surface.statusChanged(engine.snapshot())
    val surfaceGetter: () -> EditorSurface = { surface }
    window.rootPane.putClientProperty("editor.files", files)
    window.rootPane.putClientProperty("editor.layers", layers)
    window.rootPane.putClientProperty("editor.surfaceGetter", surfaceGetter)
    window.rootPane.putClientProperty("editor.localRecovery", localRecovery)
    return window.apply {
        minimumSize = Dimension(940, 400)
        contentPane = JPanel(BorderLayout()).apply {
            add(WritingHeaderScroll(header, window).apply { name = "writing-header-scroll" }, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    add(layers, BorderLayout.CENTER)
                    add(structureUi.outlinePane, BorderLayout.EAST)
                },
                BorderLayout.CENTER,
            )
            add(footer, BorderLayout.SOUTH)
        }
        pack()
        when {
            windowLocation != null -> {
                isAutoRequestFocus = false
                location = windowLocation
            }
            offscreenTest -> {
                isAutoRequestFocus = false
                setLocation(-16000, -16000)
            }
            else -> setLocationRelativeTo(null)
        }
        isVisible = true
        workspaceUi?.observe()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                surface.viewModel.detach()
                if (!approvedClose) {
                    workspaceUi?.close(false)
                    unfinishedRecovery?.close()
                }
                localRecovery?.finish(discard = false)
                if (!surface.disposed) surface.removeNotify()
                compose.dispose()
            }
        })
        SwingUtilities.invokeLater {
            if (!offscreenTest && windowLocation == null) surface.requestFocusInWindow()
            surface.writeDiagnostics()
        }
    }
}
