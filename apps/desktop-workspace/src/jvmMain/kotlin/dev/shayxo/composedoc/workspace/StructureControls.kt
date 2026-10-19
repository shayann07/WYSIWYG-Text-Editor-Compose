package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.ImageDecoder
import dev.shayxo.composedoc.domain.ClipboardCapture
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.domain.StructureCommand
import dev.shayxo.composedoc.model.ImageBlock
import dev.shayxo.composedoc.model.LinkValue
import dev.shayxo.composedoc.model.ListKind
import dev.shayxo.composedoc.model.MarkerTypography
import dev.shayxo.composedoc.model.OutlineEntry
import dev.shayxo.composedoc.model.StoredFont
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.contiguousLinkAt
import dev.shayxo.composedoc.model.paragraphAt
import dev.shayxo.composedoc.model.paragraphs
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.ImageResources
import java.awt.Component
import java.awt.Container
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

private const val MAX_HEADING_LEVEL = 6
private const val OUTLINE_WIDTH_PX = 320
private const val OUTLINE_HEIGHT_PX = 250
private const val OUTLINE_MIN_WIDTH_PX = 260
private const val OUTLINE_MIN_HEIGHT_PX = 80
private const val RESTART_FIELD_COLUMNS = 8
private const val MAX_LIST_START = 9999
private const val MARKER_SIZE_COLUMNS = 6
private const val MIN_MARKER_SIZE = 8
private const val MAX_MARKER_SIZE = 96
private const val TEXT_FIELD_COLUMNS = 34
private const val WIDTH_FIELD_COLUMNS = 8
private const val MAX_ALT_TEXT_UNITS = 2048
private const val DEFAULT_IMAGE_WIDTH_DP = 240
private const val MIN_IMAGE_WIDTH_DP = 10
private const val MAX_IMAGE_WIDTH_DP = 600
private const val MAX_LINK_LABEL_UNITS = 4096
private const val UNICODE_OBJECT_REPLACEMENT = '\uFFFC'

/** Inputs of one open Link dialog, compared against the live target on Apply/Remove. */
private class LinkForm(
    val surface: EditorSurface,
    val capture: ClipboardCapture,
    val inserting: Boolean,
    val uri: JTextField,
    val label: JTextField,
)

/** Desktop shell adapter; every mutation delegates to the semantic reducer. */
class StructureControls(
    private val window: JFrame,
    private val surface: () -> EditorSurface,
    private val busy: () -> Boolean,
) {
    val panel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        name = "structure-controls"
    }
    val role = JComboBox(arrayOf("Mixed", "Normal") + (1..MAX_HEADING_LEVEL).map { "Heading $it" }).apply {
        name = "paragraph-role"
        fitCombo(this)
    }
    val list = JComboBox(arrayOf("Mixed", "No list", "Bullets", "Numbers")).apply { name = "list-type" }
    val outlineModel = DefaultListModel<OutlineEntry>()
    var currentSection: Long? = null
    var hasCurrentSection = false
    val outline = JList(outlineModel).apply {
        name = "document-outline"
        accessibleContext.accessibleName = "Document headings outline; blue bar marks current section"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = OutlineLabelRenderer { hasCurrentSection && it == currentSection }
        fixedCellHeight = -1
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                revalidate()
                repaint()
            }
        })
    }
    val outlinePane = JScrollPane(outline).apply {
        preferredSize = Dimension(OUTLINE_WIDTH_PX, OUTLINE_HEIGHT_PX)
        minimumSize = Dimension(OUTLINE_MIN_WIDTH_PX, OUTLINE_MIN_HEIGHT_PX)
        isVisible = false
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private var refreshing = false
    private var working = false
    private var pendingImport: java.util.concurrent.Future<*>? = null
    val insertImage = JButton("Insert image…").apply { name = "insert-image" }
    val imageProperties = JButton("Image properties…").apply { name = "image-properties" }
    val link = JButton("Link…").apply { name = "edit-link" }
    val openLink = JButton("Open Link").apply { name = "open-link" }
    init {
        window.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) {
                pendingImport?.cancel(true)
            }
        })
        val r = row()
        r.add(JLabel("Role"))
        r.add(role)
        r.add(list)
        fun button(label: String, tip: String, cmd: StructureCommand) {
            r.add(
                JButton(label).apply {
                    isFocusable = false
                    toolTipText = tip
                    addActionListener { command(cmd) }
                },
            )
        }
        button("Indent", "Move whole selected items beneath previous sibling", StructureCommand.Indent)
        button("Outdent", "Lift one list level; root items become Normal", StructureCommand.Outdent)
        button("Item paragraph", "Add another paragraph in the same list item", StructureCommand.Enter(true))
        r.add(
            JButton("Restart…").apply {
                name = "restart-list"
                isFocusable = false
                addActionListener { restartDialog() }
            },
        )
        r.add(
            JButton("Marker style…").apply {
                name = "marker-style"
                isFocusable = false
                toolTipText = "Stored list-marker font/size; independent of body formatting"
                addActionListener { markerStyleDialog() }
            },
        )
        role.toolTipText = "Semantic role; direct font/size overrides remain separate. Heading-end Enter creates Normal"
        list.toolTipText = "Create/switch/remove semantic list items; no marker characters inserted"
        role.addActionListener {
            if (!refreshing && role.selectedIndex > 0)command(StructureCommand.Role(role.selectedIndex - 1))
        }
        list.addActionListener {
            if (!refreshing && list.selectedIndex > 0) {
                command(StructureCommand.ListType(ListKind.entries[list.selectedIndex - 1]))
            }
        }
        val objects = row()
        objects.add(link)
        objects.add(openLink)
        objects.add(insertImage)
        objects.add(imageProperties)
        objects.add(
            JButton("Replace image…").apply {
                name = "replace-image"
                addActionListener { replaceImage() }
            },
        )
        objects.add(
            JButton("Delete image").apply {
                name = "delete-image"
                addActionListener { deleteImage() }
            },
        )
        objects.add(
            JCheckBox("Outline").apply {
                name = "outline-toggle"
                isOpaque = false
                addActionListener {
                    outlinePane.isVisible = isSelected
                    window.validate()
                    surface().view.ensureCaret = true
                }
            },
        )
        installOutlineNavigation()
        link.isFocusable = false
        link.toolTipText = "Add/edit/remove URI metadata. At a caret insert labeled text"
        link.addActionListener { linkDialog() }
        openLink.isFocusable = false
        openLink.toolTipText = "Explicitly activate http/https/mailto only; editing clicks never open links"
        openLink.addActionListener {
            val value = linkAtSelection() ?: return@addActionListener
            if (value.activatable()) {
                runCatching { Desktop.getDesktop().browse(java.net.URI(value.uri)) }.onFailure {
                    JOptionPane.showMessageDialog(window, "Link could not be opened: ${it.message}")
                }
            }
        }
        insertImage.isFocusable = false
        insertImage.toolTipText =
            "Embed local PNG/JPEG; bytes owned by document. Oversize display fits page proportionally"
        insertImage.addActionListener { insertImageDialog() }
        imageProperties.isFocusable = false
        imageProperties.toolTipText =
            "Select an image; edit width10–600 document dp and alternative text. Aspect ratio remains fixed"
        imageProperties.addActionListener { imagePropertiesDialog() }
    }

    private fun row() = JPanel(WrappingControls()).apply {
        isOpaque = false
        panel.add(this)
    }

    private fun restartDialog() {
        val c = surface().viewModel.clipboardCapture() ?: return
        val value = JTextField("1", RESTART_FIELD_COLUMNS).apply { name = "list-restart-value" }
        validatedDialog(
            window,
            "Restart numbering",
            "list-restart-dialog",
            listOf("Start (1–9999)" to value),
            listOf(
                "Apply" to {
                    val n = value.text.toIntOrNull()
                    when {
                        n == null || n !in 1..MAX_LIST_START -> "Enter a whole number from 1 to 9999."
                        surface().viewModel.clipboardCapture() != c -> "Target changed; cancel and reopen Restart."
                        !surface().viewModel.dispatch(EditorIntent.ApplyStructure(c, StructureCommand.Restart(n))) ->
                            "Command not applied. Select a numbered item or cancel."
                        else -> null
                    }
                },
            ),
        )
        surface().requestFocusInWindow()
    }

    private fun markerStyleDialog() {
        val c = surface().viewModel.clipboardCapture() ?: return
        val p = c.basis.document.paragraphAt(c.selection.focus).properties
        val fonts = StoredFont.entries.filter { it != StoredFont.DEFAULT }
        val family = JComboBox(fonts.toTypedArray()).apply {
            selectedItem = p.markerTypography.font
            name = "marker-font"
        }
        val size = JTextField(p.markerTypography.size.toString(), MARKER_SIZE_COLUMNS).apply { name = "marker-size" }
        validatedDialog(
            window,
            "List marker typography",
            "marker-style-dialog",
            listOf("Font" to family, "Size (8–96)" to size),
            listOf(
                "Apply" to {
                    val n = size.text.toIntOrNull()
                    when {
                        n == null || n !in MIN_MARKER_SIZE..MAX_MARKER_SIZE -> "Enter a whole size from 8 to 96."
                        surface().viewModel.clipboardCapture() != c ->
                            "Target changed; cancel and reopen Marker style."
                        !surface().viewModel.dispatch(
                            EditorIntent.ApplyStructure(
                                c,
                                StructureCommand.MarkerStyle(MarkerTypography(family.selectedItem as StoredFont, n)),
                            ),
                        ) -> "Select list items with a different marker style."
                        else -> null
                    }
                },
            ),
        )
        surface().requestFocusInWindow()
    }

    private fun replaceImage() {
        val s = surface()
        val c = s.viewModel.clipboardCapture() ?: return
        if (c.basis.document.units.subList(c.selection.start, c.selection.end).singleOrNull()?.image == null) {
            JOptionPane.showMessageDialog(window, "Select one image first.")
        } else if (!working) {
            val picker = JFileChooser().apply {
                dialogTitle = "Replace selected image — preserve width and alternative text"
            }
            if (picker.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) importReplacement(s, c, picker)
        }
    }

    private fun importReplacement(s: EditorSurface, c: ClipboardCapture, picker: JFileChooser) {
        working = true
        refresh()
        pendingImport = ImageDecoder.importAsync(picker.selectedFile.toPath(), { (id, data) ->
            working = false
            if (!window.isDisplayable || s.disposed)return@importAsync
            if (!s.admissionAllowed ||
                !s.viewModel.dispatch(EditorIntent.ApplyStructure(c, StructureCommand.ReplaceImage(id, data)))
            ) {
                JOptionPane.showMessageDialog(window, "Image target changed; replacement refused. Old image kept.")
            }
            refresh()
            s.requestFocusInWindow()
        }, { e ->
            working = false
            refresh()
            JOptionPane.showMessageDialog(window, e.message, "Image unchanged", JOptionPane.ERROR_MESSAGE)
        })
    }

    private fun deleteImage() {
        val s = surface()
        val c = s.viewModel.clipboardCapture() ?: return
        if (s.admissionAllowed &&
            c.basis.document.units.subList(c.selection.start, c.selection.end).singleOrNull()?.image != null
        ) {
            s.viewModel.dispatch(EditorIntent.ApplyClipboard(c, null, plain = "", cut = true))
        }
        s.requestFocusInWindow()
    }

    private fun installOutlineNavigation() {
        outline.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val index = outline.locationToIndex(e.point)
                if (index >= 0 && outline.getCellBounds(index, index)?.contains(e.point) == true) {
                    outline.selectedIndex = index
                    outlineModel[index].let {
                        surface().viewModel.dispatch(EditorIntent.NavigateOutline(it.anchor))
                        surface().requestFocusInWindow()
                    }
                }
            }
        })
        outline.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "navigate")
        outline.actionMap.put(
            "navigate",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    outline.selectedValue?.let {
                        surface().viewModel.dispatch(EditorIntent.NavigateOutline(it.anchor))
                        surface().requestFocusInWindow()
                    }
                }
            },
        )
    }

    private fun insertImageDialog() {
        val s = surface()
        val capture = s.viewModel.clipboardCapture() ?: return
        val chooser = JFileChooser().apply {
            dialogTitle = "Insert PNG or JPEG"
            fileFilter =
                javax.swing.filechooser.FileNameExtensionFilter("PNG or JPEG (signature checked)", "png", "jpg", "jpeg")
        }
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION)return
        val path = chooser.selectedFile.toPath()
        working = true
        refresh()
        pendingImport = ImageDecoder.importAsync(path, { (id, data) ->
            working = false
            if (!window.isDisplayable || s.disposed)return@importAsync
            val alt = JTextField(path.fileName.toString(), TEXT_FIELD_COLUMNS).apply { name = "insert-image-alt" }
            validatedDialog(
                window,
                "Describe inserted image",
                "insert-image-alt-dialog",
                listOf("Alt text (empty: no description/decorative intent)" to alt),
                listOf(
                    "Insert" to {
                        when {
                            alt.text.length > MAX_ALT_TEXT_UNITS -> "Shorten alternative text to 2048 UTF-16 units."
                            !s.admissionAllowed || s.viewModel.clipboardCapture() != capture ->
                                "Target changed; cancel and insert again."
                            !s.viewModel.dispatch(
                                EditorIntent.ApplyStructure(
                                    capture,
                                    StructureCommand.InsertImage(
                                        ImageBlock(id, DEFAULT_IMAGE_WIDTH_DP, alt.text),
                                        data,
                                    ),
                                ),
                            ) -> "Insertion rejected; document kept."
                            else -> null
                        }
                    },
                ),
            )
            refresh()
            s.requestFocusInWindow()
        }, { e ->
            working = false
            refresh()
            JOptionPane.showMessageDialog(window, e.message, "Image not inserted", JOptionPane.ERROR_MESSAGE)
        })
    }

    private fun imagePropertiesDialog() {
        val s = surface()
        val c = s.viewModel.clipboardCapture() ?: return
        val image = c.basis.document.units.subList(c.selection.start, c.selection.end).singleOrNull()?.image ?: return
        val width = JTextField(image.width.toString(), WIDTH_FIELD_COLUMNS).apply { name = "image-width" }
        val alt = JTextField(image.alt, TEXT_FIELD_COLUMNS).apply { name = "image-alt" }
        val resource = c.basis.document.resources.getValue(image.resource)
        val p = s.geometry()?.paragraphs?.firstOrNull { it.startOffset == c.selection.start && it.image != null }
        val fitted = p?.let { it.imageWidth / (s.geometry()!!.scale * s.view.zoom) }?.toInt()
        val widths = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(width)
            add(
                JButton("Original size").apply {
                    name = "image-original-size"
                    addActionListener {
                        width.text = resource.width.coerceIn(MIN_IMAGE_WIDTH_DP, MAX_IMAGE_WIDTH_DP).toString()
                    }
                },
            )
            add(
                JButton("Fit to content").apply {
                    name = "image-fit"
                    addActionListener {
                        val content = p?.contentWidth?.div(s.geometry()!!.scale * s.view.zoom)
                        width.text = (content ?: MAX_IMAGE_WIDTH_DP.toFloat()).toInt()
                            .coerceIn(MIN_IMAGE_WIDTH_DP, MAX_IMAGE_WIDTH_DP).toString()
                    }
                },
            )
        }
        validatedDialog(
            window,
            "Image properties — requested ${image.width} dp; fitted ${fitted ?: "pending"} dp",
            "image-properties-dialog",
            listOf("Width dp (10–600; aspect preserved)" to widths, "Alternative text (max 2048 units)" to alt),
            listOf(
                "OK" to {
                    val n = width.text.toIntOrNull()
                    when {
                        n == null -> "Width must be a whole number from 10 to 600."
                        n !in MIN_IMAGE_WIDTH_DP..MAX_IMAGE_WIDTH_DP -> "Width must be from 10 to 600 document dp."
                        alt.text.length > MAX_ALT_TEXT_UNITS ->
                            "Alternative text exceeds 2048 UTF-16 units; shorten it."
                        s.viewModel.clipboardCapture() != c -> "Target changed; cancel and reopen Image properties."
                        n == image.width && alt.text == image.alt -> null
                        !s.viewModel.dispatch(
                            EditorIntent.ApplyStructure(c, StructureCommand.ImageProperties(n, alt.text)),
                        ) -> "Image update rejected; cancel and select one image."
                        else -> null
                    }
                },
            ),
        )
        s.requestFocusInWindow()
    }

    private fun command(c: StructureCommand) {
        if (surface().admissionAllowed && !busy() && !working)surface().viewModel.dispatch(EditorIntent.Structure(c))
        surface().requestFocusInWindow()
    }
    private fun linkAtSelection(): LinkValue? {
        val f = surface().viewModel.snapshot()
        val d = f.visible
        if (f.selection.collapsed)return d.contiguousLinkAt(f.selection)?.first
        val values = d.units.subList(f.selection.start, f.selection.end)
            .filter { it.kind == UnitKind.TEXT }
            .map { it.style.link }
            .distinct()
        return values.singleOrNull()
    }
    private fun linkDialog() {
        val s = surface()
        val c = s.viewModel.clipboardCapture() ?: return
        val d = c.basis.document
        val inside = d.contiguousLinkAt(c.selection)
        val current = linkAtSelection()
        val inserting = c.selection.collapsed && inside == null
        val range = inside?.second ?: c.selection
        val uri = JTextField(current?.uri ?: "https://", TEXT_FIELD_COLUMNS).apply { name = "link-uri" }
        val label = JTextField(
            if (inserting)"" else d.text.substring(range.start, range.end),
            TEXT_FIELD_COLUMNS,
        ).apply {
            name = "link-label"
            isEditable = inserting
        }
        val title = if (inserting) {
            "Insert hyperlink"
        } else if (current == null) {
            "Hyperlink — mixed selection"
        } else {
            "Edit hyperlink"
        }
        val form = LinkForm(s, c, inserting, uri, label)
        validatedDialog(
            window,
            title,
            "link-dialog",
            listOf(
                (if (inserting)"Label" else "Target text (displayed graphemes)") to label,
                "URI (http/https/mailto)" to uri,
            ),
            listOf("Apply" to { applyLink(form, false) }, "Remove" to { applyLink(form, true) }),
        )
        s.requestFocusInWindow()
    }

    private fun applyLink(form: LinkForm, remove: Boolean): String? = when {
        form.surface.viewModel.clipboardCapture() != form.capture -> "Target changed; cancel and reopen Link."
        remove && form.inserting -> "No existing link here. Cancel or insert a labeled link."
        !remove && (!runCatching { LinkValue("check", form.uri.text).activatable() }.getOrDefault(false)) ->
            "Use an absolute http, https or mailto URI."
        !remove && form.inserting && invalidLinkLabel(form.label.text) ->
            "Supply a single-line label of 1–4096 units."
        !form.surface.viewModel.dispatch(
            EditorIntent.ApplyStructure(
                form.capture,
                StructureCommand.Link(if (remove)null else form.uri.text, form.label.text),
            ),
        ) -> "No change applied; select linked text or cancel."
        else -> null
    }

    private fun invalidLinkLabel(text: String) = text.isEmpty() ||
        text.length > MAX_LINK_LABEL_UNITS ||
        text.any { it == '\n' || it == UNICODE_OBJECT_REPLACEMENT }

    fun refresh() {
        refreshing = true
        try {
            val s = surface()
            role.selectedIndex = s.viewModel.paragraphState { it.heading }?.plus(1) ?: 0
            list.selectedIndex = s.viewModel.paragraphState { it.list }?.ordinal?.plus(1) ?: 0
            refreshOutline(s)
            panel.components.forEach { enableTree(it, s) }
            openLink.isEnabled = linkAtSelection()?.activatable() == true
            refreshCapturedActions(s, s.viewModel.snapshot())
        } finally {
            refreshing = false
        }
    }

    private fun refreshOutline(s: EditorSurface) {
        val entries = s.viewModel.outline()
        val selected = outline.selectedValue?.anchor
        val hadSelection = outline.selectedIndex >= 0
        if ((0 until outlineModel.size()).map { outlineModel[it] } != entries) {
            outlineModel.clear()
            entries.forEach { outlineModel.addElement(it) }
            if (hadSelection)outline.selectedIndex = entries.indexOfFirst { it.anchor == selected }
        }
        val current = s.viewModel.snapshot().let { f ->
            f.committed.paragraphs().lastOrNull { it.start <= f.selection.focus && it.properties.heading > 0 }
        }
        currentSection = current?.anchor
        hasCurrentSection = current != null
        outline.repaint()
    }

    private fun enableTree(c: Component, s: EditorSurface) {
        if (c is Container)c.components.forEach { enableTree(it, s) }
        if (c !is JLabel && c !is JPanel)c.isEnabled = s.admissionAllowed && !busy() && !working
    }

    private fun refreshCapturedActions(s: EditorSurface, f: EditorFrame) {
        val capturedActions = s.admissionAllowed && !busy() && !working && f.marked == null && !f.waiting
        link.isEnabled = capturedActions
        insertImage.isEnabled = capturedActions
        (panel.components.filterIsInstance<Container>().flatMap { it.components.toList() })
            .firstOrNull { it.name == "restart-list" }
            ?.isEnabled = capturedActions
        imageProperties.isEnabled = s.admissionAllowed &&
            !busy() &&
            f.marked == null &&
            f.visible.units.subList(f.selection.start, f.selection.end).singleOrNull()?.image != null
        ImageResources.prepare(f.visible) { s.repaint() }
    }
}
