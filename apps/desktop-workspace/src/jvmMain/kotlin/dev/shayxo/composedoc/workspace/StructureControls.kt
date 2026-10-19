package dev.shayxo.composedoc.workspace

import dev.shayxo.composedoc.data.*
import dev.shayxo.composedoc.domain.*
import dev.shayxo.composedoc.layout.*
import dev.shayxo.composedoc.model.*
import dev.shayxo.composedoc.ui.*
import java.awt.*
import javax.swing.*

/** Desktop shell adapter; every mutation delegates to the semantic reducer. */
class StructureControls(private val window: JFrame, private val surface: () -> EditorSurface, private val busy: () -> Boolean) {
    val panel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        name = "structure-controls"
    }
    val role = JComboBox(arrayOf("Mixed", "Normal") + (1..6).map { "Heading $it" }).apply {
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
        preferredSize = Dimension(320, 250)
        minimumSize = Dimension(260, 80)
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
        fun row() = JPanel(WrappingControls()).apply {
            isOpaque = false
            panel.add(this)
        }
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
                addActionListener {
                    val c = surface().engine.clipboardCapture() ?: return@addActionListener
                    val value = JTextField("1", 8).apply { name = "list-restart-value" }
                    validatedDialog(
                        window,
                        "Restart numbering",
                        "list-restart-dialog",
                        listOf("Start (1–9999)" to value),
                        listOf(
                            "Apply" to {
                                val n = value.text.toIntOrNull()
                                when {
                                    n == null || n !in 1..9999 -> "Enter a whole number from 1 to 9999."
                                    surface().engine.clipboardCapture() != c -> "Target changed; cancel and reopen Restart."
                                    !surface().engine.applyStructure(c, StructureCommand.Restart(n)) -> "Command not applied. Select a numbered item or cancel."
                                    else -> null
                                }
                            },
                        ),
                    )
                    surface().requestFocusInWindow()
                }
            },
        )
        r.add(
            JButton("Marker style…").apply {
                name = "marker-style"
                isFocusable = false
                toolTipText = "Stored list-marker font/size; independent of body formatting"
                addActionListener {
                    val c = surface().engine.clipboardCapture() ?: return@addActionListener
                    val p = c.basis.document.paragraphAt(c.selection.focus).properties
                    val fonts = StoredFont.entries.filter { it != StoredFont.DEFAULT }
                    val family = JComboBox(fonts.toTypedArray()).apply {
                        selectedItem = p.markerTypography.font
                        name = "marker-font"
                    }
                    val size = JTextField(p.markerTypography.size.toString(), 6).apply { name = "marker-size" }
                    validatedDialog(
                        window,
                        "List marker typography",
                        "marker-style-dialog",
                        listOf("Font" to family, "Size (8–96)" to size),
                        listOf(
                            "Apply" to {
                                val n = size.text.toIntOrNull()
                                when {
                                    n == null || n !in 8..96 -> "Enter a whole size from 8 to 96."
                                    surface().engine.clipboardCapture() != c -> "Target changed; cancel and reopen Marker style."
                                    !surface().engine.applyStructure(c, StructureCommand.MarkerStyle(MarkerTypography(family.selectedItem as StoredFont, n))) -> "Select list items with a different marker style."
                                    else -> null
                                }
                            },
                        ),
                    )
                    surface().requestFocusInWindow()
                }
            },
        )
        role.toolTipText = "Semantic role; direct font/size overrides remain separate. Heading-end Enter creates Normal"
        list.toolTipText = "Create/switch/remove semantic list items; no marker characters inserted"
        role.addActionListener { if (!refreshing && role.selectedIndex > 0)command(StructureCommand.Role(role.selectedIndex - 1)) }
        list.addActionListener { if (!refreshing && list.selectedIndex > 0)command(StructureCommand.ListType(ListKind.entries[list.selectedIndex - 1])) }
        val objects = row()
        objects.add(link)
        objects.add(openLink)
        objects.add(insertImage)
        objects.add(imageProperties)
        objects.add(
            JButton("Replace image…").apply {
                name = "replace-image"
                addActionListener {
                    val s = surface()
                    val c = s.engine.clipboardCapture() ?: return@addActionListener
                    if (c.basis.document.units.subList(c.selection.start, c.selection.end).singleOrNull()?.image == null) {
                        JOptionPane.showMessageDialog(window, "Select one image first.")
                        return@addActionListener
                    }
                    if (working)return@addActionListener
                    val picker = JFileChooser().apply { dialogTitle = "Replace selected image — preserve width and alternative text" }
                    if (picker.showOpenDialog(window) != JFileChooser.APPROVE_OPTION)return@addActionListener
                    working = true
                    refresh()
                    pendingImport = ImageDecoder.importAsync(picker.selectedFile.toPath(), { (id, data) ->
                        working = false
                        if (!window.isDisplayable || s.disposed)return@importAsync
                        if (!s.admissionAllowed || !s.engine.applyStructure(c, StructureCommand.ReplaceImage(id, data)))JOptionPane.showMessageDialog(window, "Image target changed; replacement refused. Old image kept.")
                        refresh()
                        s.requestFocusInWindow()
                    }, { e ->
                        working = false
                        refresh()
                        JOptionPane.showMessageDialog(window, e.message, "Image unchanged", JOptionPane.ERROR_MESSAGE)
                    })
                }
            },
        )
        objects.add(
            JButton("Delete image").apply {
                name = "delete-image"
                addActionListener {
                    val s = surface()
                    val c = s.engine.clipboardCapture() ?: return@addActionListener
                    if (s.admissionAllowed && c.basis.document.units.subList(c.selection.start, c.selection.end).singleOrNull()?.image != null)s.engine.clipboardApply(c, null, plain = "", cut = true)
                    s.requestFocusInWindow()
                }
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
        outline.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val index = outline.locationToIndex(e.point)
                if (index >= 0 && outline.getCellBounds(index, index)?.contains(e.point) == true) {
                    outline.selectedIndex = index
                    outlineModel[index].let {
                        surface().engine.navigateOutline(it.anchor)
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
                        surface().engine.navigateOutline(it.anchor)
                        surface().requestFocusInWindow()
                    }
                }
            },
        )
        link.isFocusable = false
        link.toolTipText = "Add/edit/remove URI metadata. At a caret insert labeled text"
        link.addActionListener { linkDialog() }
        openLink.isFocusable = false
        openLink.toolTipText = "Explicitly activate http/https/mailto only; editing clicks never open links"
        openLink.addActionListener {
            val value = linkAtSelection() ?: return@addActionListener
            if (value.activatable())runCatching { Desktop.getDesktop().browse(java.net.URI(value.uri)) }.onFailure { JOptionPane.showMessageDialog(window, "Link could not be opened: ${it.message}") }
        }
        insertImage.isFocusable = false
        insertImage.toolTipText = "Embed local PNG/JPEG; bytes owned by document. Oversize display fits page proportionally"
        insertImage.addActionListener {
            val s = surface()
            val capture = s.engine.clipboardCapture() ?: return@addActionListener
            val chooser = JFileChooser().apply {
                dialogTitle = "Insert PNG or JPEG"
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PNG or JPEG (signature checked)", "png", "jpg", "jpeg")
            }
            if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION)return@addActionListener
            val path = chooser.selectedFile.toPath()
            working = true
            refresh()
            pendingImport = ImageDecoder.importAsync(path, { (id, data) ->
                working = false
                if (!window.isDisplayable || s.disposed)return@importAsync
                val alt = JTextField(path.fileName.toString(), 34).apply { name = "insert-image-alt" }
                validatedDialog(
                    window,
                    "Describe inserted image",
                    "insert-image-alt-dialog",
                    listOf("Alt text (empty: no description/decorative intent)" to alt),
                    listOf(
                        "Insert" to {
                            when {
                                alt.text.length > 2048 -> "Shorten alternative text to 2048 UTF-16 units."
                                !s.admissionAllowed || s.engine.clipboardCapture() != capture -> "Target changed; cancel and insert again."
                                !s.engine.applyStructure(capture, StructureCommand.InsertImage(ImageBlock(id, 240, alt.text), data)) -> "Insertion rejected; document kept."
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
        imageProperties.isFocusable = false
        imageProperties.toolTipText = "Select an image; edit width10–600 document dp and alternative text. Aspect ratio remains fixed"
        imageProperties.addActionListener {
            val s = surface()
            val c = s.engine.clipboardCapture() ?: return@addActionListener
            val image = c.basis.document.units.subList(c.selection.start, c.selection.end).singleOrNull()?.image ?: return@addActionListener
            val width = JTextField(image.width.toString(), 8).apply { name = "image-width" }
            val alt = JTextField(image.alt, 34).apply { name = "image-alt" }
            val resource = c.basis.document.resources.getValue(image.resource)
            val p = s.geometry()?.paragraphs?.firstOrNull { it.startOffset == c.selection.start && it.image != null }
            val fitted = p?.let { it.imageWidth / (s.geometry()!!.scale * s.view.zoom) }?.toInt()
            val widths = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(width)
                add(
                    JButton("Original size").apply {
                        name = "image-original-size"
                        addActionListener { width.text = resource.width.coerceIn(10, 600).toString() }
                    },
                )
                add(
                    JButton("Fit to content").apply {
                        name = "image-fit"
                        addActionListener { width.text = ((p?.contentWidth?.div(s.geometry()!!.scale * s.view.zoom)) ?: 600f).toInt().coerceIn(10, 600).toString() }
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
                            n !in 10..600 -> "Width must be from 10 to 600 document dp."
                            alt.text.length > 2048 -> "Alternative text exceeds 2048 UTF-16 units; shorten it."
                            s.engine.clipboardCapture() != c -> "Target changed; cancel and reopen Image properties."
                            n == image.width && alt.text == image.alt -> null
                            !s.engine.applyStructure(c, StructureCommand.ImageProperties(n, alt.text)) -> "Image update rejected; cancel and select one image."
                            else -> null
                        }
                    },
                ),
            )
            s.requestFocusInWindow()
        }
    }
    private fun command(c: StructureCommand) {
        if (surface().admissionAllowed && !busy() && !working)surface().engine.structure(c)
        surface().requestFocusInWindow()
    }
    private fun linkAtSelection(): LinkValue? {
        val f = surface().engine.snapshot()
        val d = f.visible
        if (f.selection.collapsed)return d.contiguousLinkAt(f.selection)?.first
        val values = d.units.subList(f.selection.start, f.selection.end).filter { it.kind == UnitKind.TEXT }.map { it.style.link }.distinct()
        return values.singleOrNull()
    }
    private fun linkDialog() {
        val s = surface()
        val c = s.engine.clipboardCapture() ?: return
        val d = c.basis.document
        val inside = d.contiguousLinkAt(c.selection)
        val current = linkAtSelection()
        val inserting = c.selection.collapsed && inside == null
        val range = inside?.second ?: c.selection
        val uri = JTextField(current?.uri ?: "https://", 34).apply { name = "link-uri" }
        val label = JTextField(if (inserting)"" else d.text.substring(range.start, range.end), 34).apply {
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
        fun apply(remove: Boolean): String? {
            if (s.engine.clipboardCapture() != c)return "Target changed; cancel and reopen Link."
            if (remove && inserting)return "No existing link here. Cancel or insert a labeled link."
            if (!remove && (!runCatching { LinkValue("check", uri.text).activatable() }.getOrDefault(false)))return "Use an absolute http, https or mailto URI."
            if (!remove && inserting && (label.text.isEmpty() || label.text.length > 4096 || label.text.any { it == '\n' || it == '\ufffc' }))return "Supply a single-line label of 1–4096 units."
            if (!s.engine.applyStructure(c, StructureCommand.Link(if (remove)null else uri.text, label.text)))return "No change applied; select linked text or cancel."
            return null
        }
        validatedDialog(window, title, "link-dialog", listOf((if (inserting)"Label" else "Target text (displayed graphemes)") to label, "URI (http/https/mailto)" to uri), listOf("Apply" to { apply(false) }, "Remove" to { apply(true) }))
        s.requestFocusInWindow()
    }
    fun refresh() {
        refreshing = true
        try {
            val s = surface()
            role.selectedIndex = s.engine.paragraphState { it.heading }?.plus(1) ?: 0
            list.selectedIndex = s.engine.paragraphState { it.list }?.ordinal?.plus(1) ?: 0
            val entries = s.engine.outline()
            val selected = outline.selectedValue?.anchor
            val hadSelection = outline.selectedIndex >= 0
            if ((0 until outlineModel.size()).map { outlineModel[it] } != entries) {
                outlineModel.clear()
                entries.forEach { outlineModel.addElement(it) }
                if (hadSelection)outline.selectedIndex = entries.indexOfFirst { it.anchor == selected }
            }
            val current = s.engine.snapshot().let { f -> f.committed.paragraphs().lastOrNull { it.start <= f.selection.focus && it.properties.heading > 0 } }
            currentSection = current?.anchor
            hasCurrentSection = current != null
            outline.repaint()
            fun enabled(c: Component) {
                if (c is Container)c.components.forEach(::enabled)
                if (c !is JLabel && c !is JPanel)c.isEnabled = s.admissionAllowed && !busy() && !working
            }
            panel.components.forEach(::enabled)
            openLink.isEnabled = linkAtSelection()?.activatable() == true
            val f = s.engine.snapshot()
            val capturedActions = s.admissionAllowed && !busy() && !working && f.marked == null && !f.waiting
            link.isEnabled = capturedActions
            insertImage.isEnabled = capturedActions
            (panel.components.filterIsInstance<Container>().flatMap { it.components.toList() }).firstOrNull { it.name == "restart-list" }?.isEnabled = capturedActions
            imageProperties.isEnabled = s.admissionAllowed && !busy() && f.marked == null && f.visible.units.subList(f.selection.start, f.selection.end).singleOrNull()?.image != null
            ImageResources.prepare(f.visible) { s.repaint() }
        } finally {
            refreshing = false
        }
    }
}
