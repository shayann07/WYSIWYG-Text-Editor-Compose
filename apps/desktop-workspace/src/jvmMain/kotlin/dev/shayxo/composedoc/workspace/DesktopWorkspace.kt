@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.toString
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import dev.shayxo.composedoc.data.DocumentFiles
import dev.shayxo.composedoc.data.FragmentCodec
import dev.shayxo.composedoc.data.LocalRecoveryStore
import dev.shayxo.composedoc.data.UnfinishedStore
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.domain.RecoveryApplyResult
import dev.shayxo.composedoc.layout.StoredFonts
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.ui.DocumentCanvas
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorPlatformServices
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.EditorViewModel
import dev.shayxo.composedoc.ui.ImeTrace
import dev.shayxo.composedoc.ui.RecoveryStatus
import dev.shayxo.composedoc.ui.RetainedCompositionPreimage
import dev.shayxo.composedoc.ui.ViewState
import dev.shayxo.composedoc.ui.quarantineDiagnostic
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

private const val EDITOR_PREFERRED_WIDTH_PX = 1020
private const val EDITOR_PREFERRED_HEIGHT_PX = 640
private const val EDITOR_MIN_WIDTH_PX = 940
private const val EDITOR_MIN_HEIGHT_PX = 400
private const val SURFACE_LAYER = 100
private const val CHROME_BACKGROUND_RGB = 0xfafcff
private const val TITLE_RGB = 0x20304a
private const val MUTED_TEXT_RGB = 0x64748b
private const val FAILURE_TEXT_RGB = 0xb3261e
private const val HEADER_VERTICAL_PAD_PX = 6
private const val HEADER_SIDE_PAD_PX = 12
private const val FOOTER_VERTICAL_PAD_PX = 3
private const val FOOTER_SIDE_PAD_PX = 16
private const val FOOTER_LINE_COUNT = 3
private const val TITLE_FONT_SIZE = 16
private const val SMALL_FONT_SIZE = 12
private const val BUTTON_FONT_SIZE = 13
private const val ROW_HGAP_PX = 4
private const val ROW_VGAP_PX = 2
private const val BAR_HGAP_PX = 8
private const val BAR_VGAP_PX = 4
private const val DRAFT_PICKER_WIDTH_PX = 180
private const val DRAFT_PICKER_HEIGHT_PX = 28
private const val DRAFT_PREVIEW_ROWS = 15
private const val DRAFT_PREVIEW_COLUMNS = 48
private const val QUARANTINE_PREVIEW_ROWS = 16
private const val QUARANTINE_PREVIEW_COLUMNS = 56
private const val DRAFT_LABEL_PREVIEW_CHARS = 20

/** Retained drafts plus quarantined callbacks at which explicit recovery is refused. */
private const val UNRESOLVED_RECOVERY_CAPACITY = 16
private const val ZOOM_HALF = .5f
private const val ZOOM_THREE_QUARTERS = .75f
private const val ZOOM_FULL = 1f
private const val DEFAULT_ZOOM_INDEX = 2
private const val OFFSCREEN_COORDINATE_PX = -16000
private const val UI_FONT_FAMILY = "Segoe UI"

fun windowFiles(window: JFrame): WindowFiles? = window.rootPane?.getClientProperty("editor.files") as? WindowFiles
fun windowLayeredPane(window: JFrame): JLayeredPane? =
    window.rootPane?.getClientProperty("editor.layers") as? JLayeredPane

// Reason: Swing stores the typed surface getter in an untyped property; changing this lookup changes its contract.
@Suppress("UNCHECKED_CAST")
fun windowCurrentSurface(window: JFrame): EditorSurface? =
    (window.rootPane?.getClientProperty("editor.surfaceGetter") as? (() -> EditorSurface))?.invoke()

@JvmOverloads
// Reason: the desktop harness consumes this public entry point; grouping parameters would change callers.
@Suppress("LongParameterList")
fun showEditor(
    diagnostics: File? = null,
    initial: Document = Document(emptyList()),
    initialPath: java.nio.file.Path? = null,
    trace: ImeTrace? = null,
    traceLanguage: String? = null,
    offscreenTest: Boolean = false,
    windowLocation: Point? = null,
    fontProfile: String = "system",
    paged: Boolean = false,
    recoveryDirectory: java.nio.file.Path? = null,
): JFrame {
    check(SwingUtilities.isEventDispatchThread())
    return EditorWindow(
        EditorLaunch(
            diagnostics,
            initial,
            initialPath,
            trace,
            traceLanguage,
            offscreenTest,
            windowLocation,
            fontProfile,
            paged,
            recoveryDirectory,
        ),
    ).show()
}

/** The [showEditor] arguments, carried as one value into the window builder. */
private data class EditorLaunch(
    val diagnostics: File?,
    val initial: Document,
    val initialPath: java.nio.file.Path?,
    val trace: ImeTrace?,
    val traceLanguage: String?,
    val offscreenTest: Boolean,
    val windowLocation: Point?,
    val fontProfile: String,
    val paged: Boolean,
    val recoveryDirectory: java.nio.file.Path?,
)

private fun uiFont(style: Int, size: Int) = Font(UI_FONT_FAMILY, style, size)

/**
 * Builds one editor window. Members are declared, and `init` steps run, in the exact order the
 * window has always been assembled: listener registration and component order are observable.
 */
private class EditorWindow(private val launch: EditorLaunch) {
    private val viewModel = EditorViewModel(launch.initial)
    private val state = ViewState(viewModel.snapshot())
    private var surface =
        EditorSurface(
            viewModel,
            state,
            launch.diagnostics,
            launch.trace,
            services = EditorPlatformServices(DocumentFiles, FragmentCodec),
        )
    private var refreshRecoveryUi: () -> Unit = {}
    private var refreshFindUi: () -> Unit = {}
    private var refreshParagraphUi: () -> Unit = {}
    private var refreshWritingUi: () -> Unit = {}
    private var refreshStructureUi: () -> Unit = {}

    init {
        surface.onHostReplaced = {
            surface = it
            refreshRecoveryUi()
            refreshFindUi()
            refreshParagraphUi()
            refreshWritingUi()
            refreshStructureUi()
        }
        state.changeTypography(TypographyProfile.load(launch.fontProfile))
        state.setPagedMode(launch.paged)
    }

    private val window = JFrame("Astra Editor — Desktop Prototype")
    private lateinit var files: WindowFiles
    private var localRecovery: LocalRecoveryController? = null
    private var unfinishedRecovery: UnfinishedController? = null
    private var workspaceUi: WorkspaceUi? = null
    private var approvedClose = false
    private val compose = ComposePanel(renderSettings = RenderSettings.SwingGraphics()).apply {
        isFocusable = false
        setContent { DocumentCanvas(state) }
    }
    private val layers = object : JLayeredPane() {
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
        override fun getPreferredSize() = Dimension(EDITOR_PREFERRED_WIDTH_PX, EDITOR_PREFERRED_HEIGHT_PX)
    }

    init {
        // Kotlin otherwise selects add(Component, index), not add(Component, layer).
        layers.add(compose)
        layers.setLayer(compose, 0)
        layers.add(surface)
        layers.setLayer(surface, SURFACE_LAYER)
    }

    private val header = JPanel(BorderLayout()).apply {
        background = Color(CHROME_BACKGROUND_RGB)
        border = EmptyBorder(HEADER_VERTICAL_PAD_PX, HEADER_SIDE_PAD_PX, HEADER_VERTICAL_PAD_PX, HEADER_SIDE_PAD_PX)
    }
    private val titleLabel = JLabel("Untitled").apply {
        font = uiFont(Font.BOLD, TITLE_FONT_SIZE)
        foreground = Color(TITLE_RGB)
    }
    private val subtitle = JLabel("Astra editor  /  Desktop prototype").apply {
        font = uiFont(Font.PLAIN, SMALL_FONT_SIZE)
        foreground = Color(MUTED_TEXT_RGB)
    }

    init {
        // Window title already identifies the file; avoid two permanent blank header rows.
        titleLabel.isVisible = false
        subtitle.isVisible = false
    }

    private val qualificationControls = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val fontPicker = JComboBox(TypographyProfile.labels.values.toTypedArray()).apply {
        selectedItem = TypographyProfile.labels.getValue(launch.fontProfile)
        toolTipText = "Experimental typography candidates; selection does not change document text or formatting"
    }
    private val fontStatus = JLabel("Candidate fonts; typography review pending")
    private var revertingFontSelection = false

    init {
        fontPicker.addActionListener { onFontSelected() }
        fitCombo(fontPicker)
    }

    private val typefaceRow = JPanel(FlowLayout(FlowLayout.LEFT, ROW_HGAP_PX, ROW_VGAP_PX)).apply {
        isOpaque = false
        add(JLabel("Preview"))
        add(fontPicker)
        add(fontStatus)
    }

    init {
        qualificationControls.add(typefaceRow)
    }

    private val pageToggle = JCheckBox("Pages (experiment)", launch.paged).apply {
        isOpaque = false
        name = "page-mode"
        toolTipText = "Switch continuous/paged presentation; content is unchanged"
    }

    init {
        pageToggle.addActionListener {
            state.setPagedMode(pageToggle.isSelected)
            surface.requestFocusInWindow()
        }
        qualificationControls.add(buildPageRow())
        header.add(qualificationControls, BorderLayout.SOUTH)
        launch.trace?.let {
            ImeTestControls(it, viewModel) { surface }.install(subtitle, qualificationControls, launch.traceLanguage)
        }
    }

    private val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, BAR_HGAP_PX, 0)).apply { isOpaque = false }
    private val open = button("Open", "Open a document in a new window (Ctrl+O)") { files.open() }
    private val save = button("Save", "Save document (Ctrl+S); Save As: Ctrl+Shift+S") { files.save() }
    private val saveAs = button("Save As…", "Save to another .astra file (Ctrl+Shift+S)") { files.save(asNew = true) }
    private val undo = button("Undo", "Ctrl+Z") { viewModel.dispatch(EditorIntent.Command(EditAction.Undo)) }
    private val redo =
        button("Redo", "Ctrl+Shift+Z or Ctrl+Y") { viewModel.dispatch(EditorIntent.Command(EditAction.Redo)) }
    private val bold =
        button("Bold", "Toggle bold (Ctrl+B)") {
            if (surface.admissionAllowed) viewModel.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD))
        }
    private val italic =
        button("Italic", "Toggle italic (Ctrl+I)") {
            if (surface.admissionAllowed) viewModel.dispatch(EditorIntent.ToggleFormat(FormatKey.ITALIC))
        }

    init {
        button("Copy", "Copy rich + plain selected committed text (Ctrl+C)") { surface.copySelection(false) }
        button("Cut", "Publish clipboard then remove captured selection (Ctrl+X)") { surface.copySelection(true) }
    }

    private val pasteBtn =
        button("Paste", "Paste rich when available (Ctrl+V); plain: Ctrl+Shift+V") { surface.paste() }

    init {
        button("Paste Plain", "Ignore rich fragment (Ctrl+Shift+V)") { surface.paste(true) }
    }

    private val recoveryRows =
        RecoveryRows(window, viewModel, { surface }, { files }, { unfinishedRecovery }) { label, tooltip, action ->
            detachedButton(label, tooltip, action)
        }

    init {
        qualificationControls.add(recoveryRows.bar)
    }

    private val quarantineRow =
        QuarantineRow(window, { surface }, { files }, { unfinishedRecovery }) { label, tooltip, action ->
            detachedButton(label, tooltip, action)
        }

    init {
        qualificationControls.add(quarantineRow.bar)
        refreshRecoveryUi = { refreshRecoveryControls() }
    }

    private val fileRow = JPanel(FlowLayout(FlowLayout.LEFT, ROW_HGAP_PX, ROW_VGAP_PX)).apply {
        isOpaque = false
        add(JLabel("File / edit"))
    }

    init {
        buttons.components.toList().forEach { fileRow.add(it) }
        qualificationControls.add(fileRow, 0)
    }

    private val footer = JPanel(BorderLayout()).apply {
        background = Color(CHROME_BACKGROUND_RGB)
        border = EmptyBorder(FOOTER_VERTICAL_PAD_PX, FOOTER_SIDE_PAD_PX, FOOTER_VERTICAL_PAD_PX, FOOTER_SIDE_PAD_PX)
    }
    private val position = JLabel()
    private val notice = JLabel()

    init {
        listOf(position, notice).forEach {
            it.font = uiFont(Font.PLAIN, SMALL_FONT_SIZE)
            it.foreground = Color(MUTED_TEXT_RGB)
        }
    }

    private val unfinishedStatus = JLabel().apply {
        name = "unfinished-status"
        font = uiFont(Font.PLAIN, SMALL_FONT_SIZE)
    }
    private val footerLines = JPanel(GridLayout(FOOTER_LINE_COUNT, 1)).apply {
        isOpaque = false
        add(position)
        add(notice)
        add(unfinishedStatus)
    }

    init {
        footer.add(footerLines, BorderLayout.CENTER)
    }

    private val localRecoveryStatus = JLabel().apply {
        font = uiFont(Font.PLAIN, SMALL_FONT_SIZE)
        foreground = Color(MUTED_TEXT_RGB)
    }

    init {
        footer.add(localRecoveryStatus, BorderLayout.EAST)
        createFiles()
        surface.requestSave = { files.save(it) }
        surface.requestOpen = { files.open() }
        surface.statusChanged = { f -> onStatusChanged(f) }
    }

    private val findUi = FindReplaceUi(window, { surface }) {
        header.revalidate()
        header.repaint()
    }

    init {
        qualificationControls.add(findUi.panel)
        refreshFindUi = { findUi.refresh() }
        window.rootPane.putClientProperty("editor.find", findUi)
    }

    private val paragraphUi = ParagraphControls({ surface }, { files.busy })
    private val paragraphRow = JPanel(FlowLayout(FlowLayout.LEFT, ROW_HGAP_PX, ROW_VGAP_PX)).apply {
        isOpaque = false
        add(JLabel("Paragraph"))
        add(paragraphUi.panel)
    }

    init {
        qualificationControls.add(paragraphRow)
    }

    private val writingUi = WritingControls({ surface }, { files.busy })

    // Tabs keep every group reachable at the existing 940×400 minimum.
    private val tabs = WritingTabs().apply { name = "writing-tabs" }
    private val textGroup = writingUi.text.apply {
        add(italic, 0)
        add(bold, 0)
    }
    private val paraGroup = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(paragraphRow)
        add(writingUi.spacing)
    }
    private val viewGroup = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(typefaceRow)
    }

    init {
        val pageRow = pageToggle.parent
        viewGroup.add(pageRow)
        tabs.addTab("Text", textGroup)
        tabs.addTab("Paragraph", paraGroup)
        tabs.addTab("View", viewGroup)
    }

    private val structureUi = StructureControls(window, { surface }, { files.busy })

    init {
        tabs.addTab("Structure", structureUi.panel)
    }

    private val outputUi = PageOutputUi(window, { surface }, { files.busy })

    init {
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
    }

    private val zoomPicker = JComboBox(arrayOf("50%", "75%", "100%")).apply {
        name = "document-zoom"
        selectedIndex = DEFAULT_ZOOM_INDEX
        toolTipText = "Presentation zoom; document metrics, stored formatting and Undo are unchanged"
    }

    init {
        zoomPicker.addActionListener {
            state.changeZoom(listOf(ZOOM_HALF, ZOOM_THREE_QUARTERS, ZOOM_FULL)[zoomPicker.selectedIndex])
            surface.requestFocusInWindow()
        }
        fitCombo(zoomPicker)
        window.rootPane.putClientProperty("editor.zoomPicker", zoomPicker)
        typefaceRow.add(JLabel("Zoom"))
        typefaceRow.add(zoomPicker)
        refreshParagraphUi = { paragraphUi.refresh() }
        window.rootPane.putClientProperty("editor.paragraphControls", paragraphUi)
        launch.recoveryDirectory?.let { installRecovery(it) }
        surface.statusChanged(viewModel.snapshot())
        val surfaceGetter: () -> EditorSurface = { surface }
        window.rootPane.putClientProperty("editor.files", files)
        window.rootPane.putClientProperty("editor.layers", layers)
        window.rootPane.putClientProperty("editor.surfaceGetter", surfaceGetter)
        window.rootPane.putClientProperty("editor.localRecovery", localRecovery)
    }

    private fun onFontSelected() {
        if (revertingFontSelection) return
        val id = TypographyProfile.labels.entries.single { it.value == fontPicker.selectedItem }.key
        runCatching { TypographyProfile.load(id) }.onSuccess { profile ->
            surface.changeTypography(profile)
            fontStatus.text = if (id == "system") "Unpinned system fonts" else "Pinned candidate; review pending"
            launch.trace?.record(
                "typography-profile",
                "key=${profile.key}; sizeSp=${profile.sizeSp}",
                viewModel.snapshot(),
            )
            surface.repaint()
        }.onFailure { failure ->
            revertingFontSelection = true
            fontPicker.selectedItem = state.typography.label
            revertingFontSelection = false
            fontStatus.text = "Font unavailable; previous profile retained"
            launch.trace?.record(
                "typography-profile-rejected",
                failure.message ?: "Font unavailable",
                viewModel.snapshot(),
            )
        }
        surface.requestFocusInWindow()
    }

    private fun buildPageRow() = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        isOpaque = false
        add(pageToggle)
        add(
            JLabel("Enter: paragraph · Shift+Enter: line break · Ctrl+S: save · Wheel: scroll").apply {
                font =
                    uiFont(Font.PLAIN, SMALL_FONT_SIZE)
            },
        )
        val recoveryDirectory = launch.recoveryDirectory
        if (recoveryDirectory != null) {
            add(
                JButton("Recovery Copies…").apply {
                    isFocusable = false
                    addActionListener { showLocalRecovery(recoveryDirectory, state.typography.id, state.paged) }
                },
            )
        }
    }

    private fun button(label: String, tooltip: String, action: () -> Unit) = JButton(label).apply {
        isFocusable = false
        font = uiFont(Font.PLAIN, BUTTON_FONT_SIZE)
        toolTipText = tooltip
        addActionListener {
            action()
            surface.requestFocusInWindow()
        }
        buttons.add(this)
    }

    /** A styled action button built like the toolbar buttons, then taken out of the toolbar for another row. */
    private fun detachedButton(label: String, tooltip: String, action: () -> Unit) =
        button(label, tooltip, action).also { buttons.remove(it) }

    private fun refreshRecoveryControls() {
        val records = surface.recoverySession.snapshotRetainedDrafts()
        val changed = recoveryRows.refreshPicker(records)
        val wasVisible = recoveryRows.bar.isVisible
        recoveryRows.bar.isVisible = records.isNotEmpty() || !surface.admissionAllowed
        recoveryRows.refreshButtons(records)
        val quarantineChanged = quarantineRow.refresh()
        if (changed || quarantineChanged || wasVisible != recoveryRows.bar.isVisible) {
            header.revalidate()
            header.repaint()
        }
    }

    private fun createFiles() {
        files = WindowFiles(window, viewModel, launch.initialPath, onDiscardComposition = {
            val host = surface
            host.abortAndRetireComposition(replaceHost = false)
            // The close choice explicitly discarded this active preedit. Remove only the
            // capsule created for that abort; other/reentrant recovery work stays pending.
            host.lastRecoveryReport?.retainedPreimage?.let { host.recoverySession.resolveRetainedDraft(it) }
        }) { surface.statusChanged(viewModel.snapshot()) }
        files.hasRetainedRecovery =
            {
                surface.recoverySession.snapshotRetainedDrafts().isNotEmpty() ||
                    surface.quarantinedCallbacks.isNotEmpty()
            }
        files.pendingRecoveryDescription =
            {
                "${surface.recoverySession.snapshotRetainedDrafts().size} retained draft(s) and " +
                    "${surface.quarantinedCallbacks.size} unverified input callback(s)"
            }
        files.openInNewWindow = { document, source ->
            showEditor(
                initial = document,
                initialPath = source,
                fontProfile = state.typography.id,
                paged = state.paged,
                offscreenTest = launch.offscreenTest,
                recoveryDirectory = launch.recoveryDirectory,
            )
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
                surface.statusChanged(viewModel.snapshot())
            }
            discard
        }
    }

    private fun onStatusChanged(f: EditorFrame) {
        val counts = writingCounts(f)
        position.text =
            "Body: ${counts.words} word-like runs · ${counts.units} UTF-16 units · ${counts.selected} selected units"
        position.toolTipText =
            "Committed body counts; headers, footers and page-number fields are not counted. " +
            "Whitespace separates runs containing letters/digits/symbols. " +
            "Joiners/marks stay in runs; not linguistic tokenization. Selected count is visible UTF-16 range."
        localRecovery?.let { recovery ->
            recovery.observe(f.committed, files.path)
            unfinishedRecovery?.observe(viewModel, surface.recoverySession, files.path, surface.latestRecoveryProposal)
            localRecoveryStatus.text = recovery.status()
            localRecoveryStatus.toolTipText = recovery.detail() + " · " + (unfinishedRecovery?.status() ?: "")
        }
        unfinishedStatus.text =
            (surface.recoveryCaptureFailure ?: unfinishedRecovery?.status() ?: "") +
            (workspaceUi?.warning?.let { " · $it" } ?: "")
        unfinishedStatus.toolTipText = unfinishedStatus.text
        unfinishedStatus.foreground =
            if (unfinishedStatus.text.contains("Failed"))Color(FAILURE_TEXT_RGB) else Color(MUTED_TEXT_RGB)
        refreshRecoveryUi()
        refreshFindUi()
        refreshParagraphUi()
        refreshWritingUi()
        refreshStructureUi()
        notice.text = noticeText(f)
        notice.toolTipText = notice.text
        titleLabel.text = files.name
        titleLabel.toolTipText = files.path?.toString()
        window.title = "${files.name} — Astra Editor"
        refreshEditButtons(f)
    }

    private fun noticeText(f: EditorFrame): String {
        val degradedNote = if (!surface.admissionAllowed &&
            surface.recoveryStatus == RecoveryStatus.DEGRADED_RECOVERY_FAILED
        ) {
            " · Input paused (degraded recovery) — Save available"
        } else {
            ""
        }
        return files.status + (if (f.notice == "Ready")"" else " · ${f.notice}") + degradedNote +
            (if (surface.clipboardMessage.isEmpty()) "" else " · ${surface.clipboardMessage}") +
            (if (StoredFonts.unavailable.isEmpty()) "" else " · Missing stored font: System fallback")
    }

    private fun refreshEditButtons(f: EditorFrame) {
        open.isEnabled = !files.busy
        save.isEnabled = !files.busy
        undo.isEnabled = f.undoCount > 0 || f.marked != null
        redo.isEnabled = f.redoCount > 0 && f.marked == null
        pasteBtn.isEnabled = !files.busy && surface.admissionAllowed && !surface.clipboardPasteBusy
        saveAs.isEnabled = !files.busy
        bold.isEnabled = !files.busy && surface.admissionAllowed
        italic.isEnabled = !files.busy && surface.admissionAllowed
        bold.text = formatLabel("Bold", FormatKey.BOLD)
        italic.text = formatLabel("Italic", FormatKey.ITALIC)
    }

    private fun formatLabel(name: String, key: FormatKey) = when (viewModel.formatState(key)) {
        true -> "$name on"
        false -> name
        null -> "$name mixed"
    }

    private fun installRecovery(recoveryDirectory: java.nio.file.Path) {
        localRecovery =
            LocalRecoveryController(LocalRecoveryStore(recoveryDirectory), refresh = {
                surface.statusChanged(viewModel.snapshot())
            })
        unfinishedRecovery = UnfinishedController(
            UnfinishedStore(LocalRecoveryStore(recoveryDirectory.resolve("unfinished-records"))),
            localRecovery!!.session,
            refresh = { surface.statusChanged(viewModel.snapshot()) },
            failed = { surface.pauseForRecoveryCapture(it) },
        )
        files.captureDurableDiscardBeforeClose = {
            unfinishedRecovery!!.observe(viewModel, surface.recoverySession, files.path)
            unfinishedRecovery!!.captureDiscardBeforeClose()
        }
        workspaceUi = WorkspaceUi(window, recoveryDirectory, { surface }, files, localRecovery, unfinishedRecovery)
        tabs.addTab("Workspace", workspaceUi!!.panel)
        files.onApprovedClose = {
            approvedClose = true
            workspaceUi?.close(true)
            unfinishedRecovery?.observe(viewModel, surface.recoverySession, files.path, surface.latestRecoveryProposal)
            unfinishedRecovery?.close()
            localRecovery?.finish(discard = true)
        }
        window.rootPane.putClientProperty("editor.workspace", workspaceUi)
        window.rootPane.putClientProperty("editor.unfinishedRecovery", unfinishedRecovery)
    }

    fun show(): JFrame = window.apply {
        minimumSize = Dimension(EDITOR_MIN_WIDTH_PX, EDITOR_MIN_HEIGHT_PX)
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
        placeWindow()
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
            if (!launch.offscreenTest && launch.windowLocation == null) surface.requestFocusInWindow()
            surface.writeDiagnostics()
        }
    }

    private fun placeWindow() {
        val windowLocation = launch.windowLocation
        when {
            windowLocation != null -> {
                window.isAutoRequestFocus = false
                window.location = windowLocation
            }
            launch.offscreenTest -> {
                window.isAutoRequestFocus = false
                window.setLocation(OFFSCREEN_COORDINATE_PX, OFFSCREEN_COORDINATE_PX)
            }
            else -> window.setLocationRelativeTo(null)
        }
    }
}

/** Live input-method test row shown only when an IME trace is recorded. */
private class ImeTestControls(
    private val trace: ImeTrace,
    private val viewModel: EditorViewModel,
    private val surface: () -> EditorSurface,
) {
    fun install(subtitle: JLabel, qualificationControls: JPanel, traceLanguage: String?) {
        subtitle.text = "IME test: test text is logged locally"
        val languages = linkedMapOf("English" to "en-US", "Japanese" to "ja-JP", "Urdu" to "ur-PK", "Arabic" to "ar-SA")
        val picker = JComboBox(languages.keys.toTypedArray())
        val result = JLabel("Choose an installed input method; then type test text.")
        picker.addActionListener {
            val tag = languages.getValue(picker.selectedItem as String)
            selectInputLanguage(tag, result)
        }
        if (traceLanguage !=
            null
        ) {
            SwingUtilities.invokeLater {
                picker.selectedItem =
                    languages.entries.first { it.value == traceLanguage }.key
            }
        }
        qualificationControls.add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                isOpaque = false
                add(picker)
                add(result)
            },
        )
        trace.record(
            "session-start",
            "Live input test mode; no synthetic injection. OS=${System.getProperty(
                "os.name",
            )}; Java=${System.getProperty("java.version")}",
            viewModel.snapshot(),
        )
    }

    private fun selectInputLanguage(tag: String, result: JLabel) {
        val accepted = surface().selectInputLocale(
            java.util.Locale.forLanguageTag(tag),
            compositionEnabled = if (tag ==
                "ja-JP"
            ) {
                true
            } else {
                null
            },
        )
        val ctx = surface().inputContext
        val compositionEnabled = if (accepted &&
            tag == "ja-JP"
        ) {
            runCatching { ctx?.isCompositionEnabled }.getOrNull()
        } else {
            null
        }
        result.text = "Input: $tag — ${if (accepted)"selected" else "unavailable"}"
        trace.record(
            "select-input",
            "requested=$tag; accepted=$accepted; actual=${ctx?.locale}; compositionEnabled=$compositionEnabled",
            viewModel.snapshot(),
        )
        surface().requestFocusInWindow()
    }
}

/** Retained-draft recovery row: draft picker plus explicit inspect/export/apply/discard/recover actions. */
private class RecoveryRows(
    private val window: JFrame,
    private val viewModel: EditorViewModel,
    private val surface: () -> EditorSurface,
    private val files: () -> WindowFiles,
    private val unfinished: () -> UnfinishedController?,
    button: (String, String, () -> Unit) -> JButton,
) {
    val bar = JPanel(FlowLayout(FlowLayout.LEFT, BAR_HGAP_PX, BAR_VGAP_PX)).apply {
        isOpaque = false
        name = "recovery-controls"
        isVisible = false
    }

    init {
        bar.add(JLabel("Recovery:"))
    }

    private val draftPicker = JComboBox<String>().apply {
        name = "recovery-picker"
        preferredSize = Dimension(DRAFT_PICKER_WIDTH_PX, DRAFT_PICKER_HEIGHT_PX)
        toolTipText = "Choose a retained recovery draft"
    }

    init {
        bar.add(draftPicker)
    }

    private var shownDrafts: List<RetainedCompositionPreimage> = emptyList()
    private var updatingDraftPicker = false

    init {
        draftPicker.addActionListener {
            if (!updatingDraftPicker) {
                shownDrafts.getOrNull(draftPicker.selectedIndex)?.let {
                    surface().selectRetainedDraft(it)
                }
            }
        }
    }

    private val inspectDraft =
        button("Inspect", "Preview the selected retained document text") { inspectRetainedDraft() }
            .also { bar.add(it) }
    private val exportDraft =
        button("Export Draft", "Save the full retained document separately; keep this recovery record") {
            surface().retainedDraftDocument()?.let { files().exportDraftDocument(it) }
        }.also { bar.add(it) }
    private val aBtn =
        button("Apply Draft", "Apply the selected draft only to its matching original basis") {
            applyRetainedDraft()
        }.also { bar.add(it) }
    private val dBtn = button("Discard Draft", "Explicitly discard only the selected retained draft") {
        discardRetainedDraft()
    }.also { bar.add(it) }
    private val rBtn =
        button("Recover", "Recover editor input after failure") { surface().recoverExplicitly(false) }
            .also { bar.add(it) }

    private fun inspectRetainedDraft() {
        val text = surface().retainedDraftDocument()?.text ?: surface().inspectRetainedDraft() ?: return
        val area = JTextArea(text, DRAFT_PREVIEW_ROWS, DRAFT_PREVIEW_COLUMNS).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            caretPosition = 0
        }
        JOptionPane.showMessageDialog(
            window,
            JScrollPane(area),
            "Retained draft text — export preserves formatting",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }

    private fun applyRetainedDraft() {
        val selected = surface().retainedPreimage
        val durable = unfinished()
        if (durable == null || selected == null) {
            surface().applyRecoveredDraft()
        } else {
            durable.resolveLive(selected.recordKey, apply = {
                val draft = selected.recoveryDraft
                if (draft != null &&
                    surface().viewModel.dispatch(EditorIntent.ApplyRecoveredDraft(draft)) == RecoveryApplyResult.Applied
                ) {
                    surface().viewModel.snapshot().committed
                } else {
                    null
                }
            }, remove = {
                surface().recoverySession.resolveRetainedDraft(selected)
                surface().statusChanged(viewModel.snapshot())
            })
        }
    }

    private fun discardRetainedDraft() {
        val selected = surface().retainedPreimage
        if (unfinished() == null || selected == null) {
            surface().discardRecoveredDraft()
        } else {
            unfinished()!!.resolveLive(selected.recordKey, remove = {
                surface().recoverySession.resolveRetainedDraft(selected)
                surface().statusChanged(viewModel.snapshot())
            })
        }
    }

    /** Rebuilds the picker only when the retained records changed; returns whether they did. */
    fun refreshPicker(records: List<RetainedCompositionPreimage>): Boolean {
        val changed = records.size != shownDrafts.size || records.zip(shownDrafts).any { (a, b) -> a !== b }
        updatingDraftPicker = true
        if (changed) {
            shownDrafts = records
            draftPicker.removeAllItems()
            records.forEachIndexed { index, record ->
                val preview =
                    record.draftText.replace('\n', ' ').take(DRAFT_LABEL_PREVIEW_CHARS).ifEmpty { "(empty draft)" }
                draftPicker.addItem("${index + 1}/${records.size} · $preview")
            }
        }
        draftPicker.selectedIndex = records.indexOfFirst { it === surface().retainedPreimage }
        updatingDraftPicker = false
        return changed
    }

    fun refreshButtons(records: List<RetainedCompositionPreimage>) {
        listOf(draftPicker, inspectDraft, exportDraft, aBtn, dBtn).forEach { it.isVisible = records.isNotEmpty() }
        draftPicker.isEnabled = records.isNotEmpty() && !files().busy
        inspectDraft.isEnabled = records.isNotEmpty()
        exportDraft.isEnabled = surface().retainedDraftDocument() != null && !files().busy
        aBtn.isEnabled =
            surface().retainedPreimage?.recoveryDraft != null &&
            !files().busy &&
            unfinished()?.decisionBusy() != true
        dBtn.isEnabled = records.isNotEmpty() && !files().busy && unfinished()?.decisionBusy() != true
        rBtn.isVisible = !surface().admissionAllowed
        rBtn.isEnabled =
            records.size + surface().quarantinedCallbacks.size < UNRESOLVED_RECOVERY_CAPACITY &&
            !files().busy
        rBtn.toolTipText = "Recover paused input; at capacity export/discard unresolved work first"
    }
}

/** Unverified (quarantined) input row: inspect, export or explicitly discard captured callbacks. */
private class QuarantineRow(
    private val window: JFrame,
    private val surface: () -> EditorSurface,
    private val files: () -> WindowFiles,
    private val unfinished: () -> UnfinishedController?,
    button: (String, String, () -> Unit) -> JButton,
) {
    val bar = JPanel(FlowLayout(FlowLayout.LEFT, BAR_HGAP_PX, BAR_VGAP_PX)).apply {
        isOpaque = false
        name = "quarantine-controls"
    }
    private val label = JLabel()

    init {
        bar.add(label)
        button("Inspect Input", "Inspect Input") { inspectQuarantinedInput() }.also { bar.add(it) }
    }

    private val exportInput =
        button("Export Input", "Export Input") {
            files().exportQuarantinedInput(surface().quarantinedCallbacks.toList())
        }.also { bar.add(it) }
    private val discardInput =
        button("Discard Input…", "Discard Input…") { discardQuarantinedInput() }.also { bar.add(it) }

    private fun inspectQuarantinedInput() {
        val captured = surface().quarantinedCallbacks.toList()
        val diagnostic = quarantineDiagnostic(captured)
        val area = JTextArea(diagnostic, QUARANTINE_PREVIEW_ROWS, QUARANTINE_PREVIEW_COLUMNS).apply {
            isEditable = false
            lineWrap = true
            caretPosition = 0
        }
        JOptionPane.showMessageDialog(
            window,
            JScrollPane(area),
            "Unverified input — never applied to the document",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }

    private fun discardQuarantinedInput() {
        val captured = surface().quarantinedCallbacks.toList()
        if (files().confirmQuarantineDiscard(captured.size)) {
            val durable = unfinished()
            if (durable == null) {
                surface().discardQuarantinedCallbacks(captured)
            } else {
                captured.forEach { entry ->
                    durable.resolveLive(entry, remove = { surface().discardQuarantinedCallbacks(listOf(entry)) })
                }
            }
        }
    }

    /** Updates the row; returns whether its visibility changed. */
    fun refresh(): Boolean {
        val quarantineVisible = surface().quarantinedCallbacks.isNotEmpty()
        val quarantineChanged = bar.isVisible != quarantineVisible
        bar.isVisible = quarantineVisible
        label.text = "Unverified input: ${surface().quarantinedCallbacks.size} callback(s)"
        exportInput.isEnabled = !files().busy
        discardInput.isEnabled = !files().busy && unfinished()?.decisionBusy() != true
        return quarantineChanged
    }
}
