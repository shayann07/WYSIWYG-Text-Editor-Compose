@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shayxo.composedoc.ui

import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.layout.TypographyProfile
import java.awt.Dimension
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

private const val DEFAULT_EDITOR_WIDTH = 700
private const val DEFAULT_EDITOR_HEIGHT = 600
private const val NATIVE_INPUT_LAYER = 100

/** Public desktop host assembly. The caller owns the ViewModel and supplies persistence/clipboard ports. */
class DesktopEditor(
    val model: EditorViewModel,
    typography: TypographyProfile,
    services: EditorPlatformServices = EditorPlatformServices.unavailable,
) : JLayeredPane(),
    AutoCloseable {
    init {
        check(SwingUtilities.isEventDispatchThread()) { "Create desktop owners and hosts on the Swing owner thread" }
    }
    val viewState = ViewState(model.snapshot()).apply { changeTypography(typography) }
    private val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics()).apply {
        isFocusable = false
        setContent { DocumentCanvas(viewState) }
    }
    private var input = EditorSurface(model, viewState, services = services)
    val inputSurface: EditorSurface get() = input
    var onFrame: (EditorFrame) -> Unit = {}
    private var closed = false
    init {
        add(panel)
        setLayer(panel, 0)
        add(input)
        setLayer(input, NATIVE_INPUT_LAYER)
        bindHost(input)
    }
    private fun bindHost(host: EditorSurface) {
        host.statusChanged = { onFrame(it) }
        host.onHostReplaced = { replacement ->
            input = replacement
            bindHost(replacement)
            revalidate()
            repaint()
        }
    }
    override fun doLayout() {
        if (viewState.hostWidth != width || viewState.hostHeight != height) {
            viewState.hostWidth = width
            viewState.hostHeight = height
            viewState.geometry = null
            viewState.ensureCaret = true
        }
        panel.setBounds(0, 0, width, height)
        input.setBounds(0, 0, width, height)
    }
    override fun getPreferredSize() = Dimension(DEFAULT_EDITOR_WIDTH, DEFAULT_EDITOR_HEIGHT)
    fun requestEditorFocus(): Boolean = input.requestFocusInWindow()
    val hasUnfinishedInput: Boolean get() = model.compositionCaptureToken() != null ||
        model.snapshot().waiting ||
        input.recoverySession.snapshotRetainedDrafts().isNotEmpty() ||
        input.quarantinedCallbacks.isNotEmpty() ||
        input.recoverySession.hasUnresolvedObligations

    /** Refuses unresolved input; callers must finish/export it rather than disposing its only owner. */
    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed)return
        check(!hasUnfinishedInput) { "Unfinished input is retained; finish or export it before closing" }
        input.abortAndRetireComposition(false)
        check(!hasUnfinishedInput) { "New input arrived during teardown; its records are retained" }
        removeAll()
        panel.dispose()
        closed = true
        onFrame = {}
    }
}
