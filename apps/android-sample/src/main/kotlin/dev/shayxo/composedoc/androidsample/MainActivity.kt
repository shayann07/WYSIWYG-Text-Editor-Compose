package dev.shayxo.composedoc.androidsample

import android.app.Activity
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.layout.AndroidTypography
import dev.shayxo.composedoc.model.FormatKey
import dev.shayxo.composedoc.model.ParagraphAlignment
import dev.shayxo.composedoc.model.ParagraphDirection
import dev.shayxo.composedoc.ui.AndroidEditorView
import dev.shayxo.composedoc.ui.EditorIntent
import dev.shayxo.composedoc.ui.composeDocEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val COMPACT_HEIGHT_DP = 500
private const val SHELL_BACKGROUND_ARGB = 0xffeef1f5
private const val ACTION_BACKGROUND_ARGB = 0xffdbe4f0
private const val STATUS_FONT_SP = 12
private const val PROPOSAL_LABEL_UNITS = 50

class MainActivity :
    Activity(),
    LifecycleOwner,
    SavedStateRegistryOwner {
    private val life = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = life
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    lateinit var owners: AndroidOwners
        private set
    var inputView: AndroidEditorView? = null
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        saved.performAttach()
        saved.performRestore(state)
        life.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owners =
            (lastNonConfigurationInstance as? AndroidOwners)
                ?: AndroidOwners(File(filesDir, "documents"), intent.getBooleanExtra("blank", false))
        val root = ComposeView(this)
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        setContentView(root)
        root.setContent { writingScreen() }
    }

    @Composable
    private fun writingScreen() {
        var fonts by remember { mutableStateOf<AndroidTypography?>(null) }
        var fontError by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { AndroidTypography.load(applicationContext) } }
                .onSuccess { fonts = it }.onFailure { fontError = "Pinned fonts unavailable: ${it.message}" }
        }
        val current by owners.current.uiState.collectAsState()
        val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP
        Column(Modifier.fillMaxSize().background(Color(SHELL_BACKGROUND_ARGB)).systemBarsPadding().imePadding()) {
            val label = "ComposeDoc · editor ${owners.active + 1}/${owners.models.size} · " +
                "${current.frame.committed.units.size} UTF-16 units"
            BasicText(label, Modifier.padding(4.dp), style = TextStyle(fontSize = STATUS_FONT_SP.sp))
            toolbarRows(compact)
            statusRows(current.frame, fonts, fontError, compact)
            unfinishedControl()
            editorBody(fonts, Modifier.fillMaxWidth().weight(1f))
        }
    }

    @Composable
    private fun toolbarRows(compact: Boolean) {
        if (compact) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                fileActions(owners, inputView)
                textActions(owners, inputView)
                paragraphActions(owners)
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { fileActions(owners, inputView) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { textActions(owners, inputView) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { paragraphActions(owners) }
        }
    }

    @Composable
    private fun statusRows(frame: EditorFrame, fonts: AndroidTypography?, error: String, compact: Boolean) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            BasicText(
                "${frame.notice} · ${owners.status}",
                Modifier.padding(horizontal = 6.dp),
                style = TextStyle(fontSize = STATUS_FONT_SP.sp),
            )
        }
        if (!compact || fonts?.unavailable?.isNotEmpty() == true) {
            BasicText(
                fonts?.status ?: error.ifEmpty { "Loading pinned fonts…" },
                Modifier.padding(horizontal = 6.dp),
                style = TextStyle(fontSize = STATUS_FONT_SP.sp),
            )
        }
    }

    @Composable
    private fun unfinishedControl() {
        if (owners.unfinished.isNotEmpty()) {
            action("Unfinished ${owners.unfinished.size} · memory only") {
                owners.retainUnfinished()
                inputView?.retireNativeSession()
                val labels = owners.unfinished.mapIndexed { index, draft ->
                    "${index + 1}: ${draft.document.text.take(PROPOSAL_LABEL_UNITS)}"
                }.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Retained proposals: open separate unsaved copy")
                    .setItems(labels) { _, index -> owners.openUnfinishedCopy(index) }
                    .setNegativeButton("Keep", null).show()
            }
        }
    }

    @Composable
    private fun editorBody(face: AndroidTypography?, modifier: Modifier) {
        if (face != null) {
            composeDocEditor(
                owners.current,
                face,
                modifier,
                onInputView = {
                    inputView = it
                    it.onNativeEvent = { event -> owners.nativeEvent(event) }
                    it.post { it.requestFocus() }
                },
                onUnfinishedInput = { owners.retainDraft(it) },
            )
        } else {
            Spacer(modifier)
        }
    }
    override fun onStart() {
        super.onStart()
        life.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }
    override fun onResume() {
        super.onResume()
        currentActivity = java.lang.ref.WeakReference(this)
        life.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    override fun onPause() {
        owners.retainUnfinished()
        inputView?.retireNativeSession()
        life.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }
    override fun onStop() {
        life.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }
    override fun onRetainNonConfigurationInstance(): Any = owners
    override fun onSaveInstanceState(outState: Bundle) {
        saved.performSave(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        life.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (!isChangingConfigurations)owners.dispose()
        inputView =
            null
        super.onDestroy()
    }
    companion object {
        var currentActivity: java.lang.ref.WeakReference<MainActivity>? = null
            private set
    }
}

@Composable
private fun fileActions(owners: AndroidOwners, input: AndroidEditorView?) {
    action("Keyboard") { input?.showKeyboard() }
    action("Next editor") {
        owners.retainUnfinished()
        input?.retireNativeSession()
        owners.active =
            (owners.active + 1) % owners.models.size
    }
    action("Save") { owners.save() }
    action("Open latest saved") { owners.reopen() }
}

@Composable
private fun textActions(owners: AndroidOwners, input: AndroidEditorView?) {
    action(
        if (owners.current.formatState(FormatKey.BOLD) ==
            true
        ) {
            "Bold ✓"
        } else {
            "Bold"
        },
    ) { owners.current.dispatch(EditorIntent.ToggleFormat(FormatKey.BOLD)) }
    action(
        if (owners.current.formatState(FormatKey.ITALIC) ==
            true
        ) {
            "Italic ✓"
        } else {
            "Italic"
        },
    ) { owners.current.dispatch(EditorIntent.ToggleFormat(FormatKey.ITALIC)) }
    action("Undo") { owners.current.dispatch(EditorIntent.Command(EditAction.Undo)) }
    action("Redo") { owners.current.dispatch(EditorIntent.Command(EditAction.Redo)) }
    action("Select all") { owners.current.dispatch(EditorIntent.SelectAll) }
    action("Cancel input") {
        owners.retainUnfinished()
        input?.cancelProvisionalInput()
    }
}

@Composable
private fun paragraphActions(owners: AndroidOwners) {
    ParagraphDirection.entries.forEach { direction ->
        action(direction.name) { owners.current.dispatch(EditorIntent.SetParagraphProperties(direction = direction)) }
    }
    ParagraphAlignment.entries.forEach { align ->
        action(align.name) { owners.current.dispatch(EditorIntent.SetParagraphProperties(alignment = align)) }
    }
}

@Composable
private fun action(label: String, run: () -> Unit) {
    BasicText(
        label,
        Modifier.padding(3.dp).background(Color(ACTION_BACKGROUND_ARGB)).clickable(onClick = run).padding(8.dp),
    )
}
