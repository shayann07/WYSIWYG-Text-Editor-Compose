package dev.shayxo.composedoc.androidsample

import android.app.Activity
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.platform.LocalDensity
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
import dev.shayxo.composedoc.domain.EditorFrame
import dev.shayxo.composedoc.layout.AndroidTypography
import dev.shayxo.composedoc.ui.AndroidEditorView
import dev.shayxo.composedoc.ui.composeDocEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val COMPACT_HEIGHT_DP = 500
private const val SHELL_BACKGROUND_ARGB = 0xffeef1f5
private const val COMPACT_FONT_SCALE = 1.3f
private const val STATUS_FONT_SP = 12
private const val PROPOSAL_LABEL_UNITS = 50

open class MainActivity :
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
    internal var typography: AndroidTypography? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        saved.performAttach()
        saved.performRestore(state)
        life.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owners =
            (lastNonConfigurationInstance as? AndroidOwners)
                ?: AndroidOwners(documentDirectory(), intent.getBooleanExtra("blank", false))
        val root = ComposeView(this)
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        setContentView(root)
        owners.attach(applicationContext)
        root.setContent { writingScreen() }
    }
    private fun documentDirectory(): File {
        val test = intent.getStringExtra("qualification-root")
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debug && test != null && test.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) {
            File(filesDir, "qualification/$test/documents")
        } else {
            File(filesDir, "documents")
        }
    }

    @Composable
    private fun writingScreen() {
        var fonts by remember { mutableStateOf<AndroidTypography?>(null) }
        var fontError by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { AndroidTypography.load(applicationContext) } }
                .onSuccess {
                    fonts = it
                    typography = it
                }
                .onFailure { fontError = "Pinned fonts unavailable: ${it.message}" }
        }
        val current by owners.current.uiState.collectAsState()
        val configuration = LocalConfiguration.current
        val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val compact =
            keyboard ||
                configuration.screenHeightDp < COMPACT_HEIGHT_DP ||
                configuration.fontScale > COMPACT_FONT_SCALE
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
        writingControls(this, owners, inputView, compact)
    }

    @Composable
    private fun statusRows(frame: EditorFrame, fonts: AndroidTypography?, error: String, compact: Boolean) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            BasicText(
                "${frame.notice} · ${owners.tab.notice.ifEmpty { owners.status }} · ${owners.tab.recovery}",
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
            writingAction("Unfinished ${owners.unfinished.size} · check Recovery status") {
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
                    it.fragmentCodec = dev.shayxo.composedoc.data.FragmentCodec
                    it.publishRichClipboard = { bytes -> checkNotNull(owners.clipboardFiles).publish(bytes) }
                    val owner = it.model
                    it.onNativeEvent = { event ->
                        val userMessage = listOf("Clipboard", "Rich ", "Plain ", "Paste ", "Cut ")
                            .any(event::startsWith)
                        if (userMessage) owners.note(owner, event) else owners.nativeEvent(event)
                    }
                    it.post { it.requestFocus() }
                },
                onUnfinishedInput = { owners.retainDraft(it) },
            )
        } else {
            Spacer(modifier)
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        owners.pickerResult(this, requestCode, resultCode, data)
    }
    override fun onBackPressed() {
        if (!owners.tab.dirty &&
            owners.unfinished.isEmpty() &&
            owners.current.compositionCaptureToken() == null
        ) {
            super.onBackPressed()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("Keep this writing safe")
            .setMessage("Save explicitly, or finish a local recovery checkpoint before leaving. Cancel keeps editing.")
            .setPositiveButton("Keep recovery & leave") { _, _ ->
                owners.retainUnfinished()
                owners.checkpoints.keep(owners.tabs.toList()) { outcome ->
                    if (outcome.durable) finish() else owners.note(owners.current, outcome.reason)
                }
            }.setNeutralButton("Save") { _, _ ->
                if (!owners.saveBound()) owners.launchPicker(this, AndroidPickerKind.SAVE_AS)
            }.setNegativeButton("Cancel", null).show()
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

/** Independent process consumer of the same packaged surface, not a second engine in one view. */
class PeerActivity : MainActivity()
