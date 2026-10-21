package dev.shayxo.composedoc.androidsample

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.shayxo.composedoc.domain.FindBasis
import dev.shayxo.composedoc.domain.FindMatch
import dev.shayxo.composedoc.domain.findLiteral
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.ui.AndroidEditorView
import dev.shayxo.composedoc.ui.EditorIntent

internal fun showFind(activity: MainActivity, owners: AndroidOwners, input: AndroidEditorView?) = settled(owners) {
    MobileFind(activity, owners, input).show()
}

/** Query fields are chrome, never document history. Replacements use the captured domain transaction. */
private class MobileFind(
    private val activity: MainActivity,
    private val owners: AndroidOwners,
    private val input: AndroidEditorView?,
) {
    private val model = owners.current
    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val query = EditText(activity).apply {
        hint = "Find literal Unicode (case sensitive)"
        contentDescription = "Find exact Unicode"
        column.addView(this)
    }
    private val replacement = EditText(activity).apply {
        hint = "Replacement (empty deletes)"
        contentDescription = "Replacement text"
        column.addView(this)
    }
    private val count = TextView(activity).also { column.addView(it) }
    private val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        column.addView(this)
    }
    private val replacementRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        column.addView(this)
    }
    private var basis: FindBasis? = null
    private var matches = emptyList<FindMatch>()
    private var active = -1
    private val replaceButton = action("Replace", true) { replace(false) }
    private val allButton = action("Replace all", true) { replace(true) }
    private val content = android.widget.ScrollView(activity).apply { addView(column) }
    private val dialog = AlertDialog.Builder(activity).setTitle("Find and Replace · literal / case sensitive")
        .setView(content).setNegativeButton("Close", null).create()
    private val poll = object : Runnable {
        override fun run() {
            if (!dialog.isShowing) return
            refresh()
            query.postDelayed(this, REFRESH_MS)
        }
    }

    fun show() {
        action("Previous") { navigate(-1) }
        action("Next") { navigate(1) }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refresh(true)
                reveal()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        query.setOnKeyListener { _, key, event ->
            val enter = event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_ENTER
            if (enter) navigate(if (event.isShiftPressed) -1 else 1)
            enter
        }
        query.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        query.setOnEditorActionListener { _, action, _ ->
            val search = action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            if (search) navigate(1)
            search
        }
        dialog.setOnKeyListener { _, key, event ->
            val escape = key == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_DOWN
            if (escape) dialog.dismiss()
            escape
        }
        dialog.setOnShowListener {
            refresh()
            query.post(poll)
            query.requestFocus()
        }
        dialog.setOnDismissListener {
            query.removeCallbacks(poll)
            input?.showSearch(null, emptyList(), -1)
            input?.requestFocus()
        }
        dialog.show()
    }

    private fun refresh(reset: Boolean = false) {
        if (owners.current !== model) {
            basis = null
            matches = emptyList()
            active = -1
            count.text = "Document changed. Close and reopen Find for the active editor."
            replaceButton.isEnabled = false
            allButton.isEnabled = false
            input?.showSearch(null, emptyList(), -1)
            return
        }
        val frame = model.snapshot()
        val captured = model.captureFindBasis()
        val changed = basis?.revision != captured.revision || basis?.document != captured.document
        if (changed || reset) {
            val old = matches.getOrNull(active)?.start ?: frame.selection.start
            basis = captured
            val settled = model.compositionCaptureToken() == null && !frame.waiting
            matches = if (settled) findLiteral(captured.document, query.text.toString()) else emptyList()
            active = matches.indexOfFirst { it.start >= old }.let { if (it < 0 && matches.isNotEmpty()) 0 else it }
        }
        updatePresentation()
    }

    private fun updatePresentation() {
        count.text = "${if (active >= 0) active + 1 else 0}/${matches.size} · exact Unicode · paragraph-local" +
            if (matches.getOrNull(active)?.editable == false) " · interior-grapheme match: replace disabled" else ""
        input?.showSearch(basis, matches, active)
        replaceButton.isEnabled = query.text.isNotEmpty() && matches.getOrNull(active)?.editable == true
        allButton.isEnabled = query.text.isNotEmpty() && matches.isNotEmpty() && matches.all { it.editable }
    }

    private fun navigate(step: Int) {
        refresh()
        if (matches.isNotEmpty()) active = (active + matches.size + step) % matches.size
        refresh()
        reveal()
    }
    private fun reveal() = matches.getOrNull(active)?.let { input?.reveal(Selection(it.start, it.end)) }
    private fun action(label: String, replacement: Boolean = false, run: () -> Unit) = Button(activity).apply {
        text = label
        setOnClickListener { run() }
        val parent = if (replacement) replacementRow else row
        parent.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    private fun replace(all: Boolean) {
        if (owners.current !== model) {
            refresh()
            return
        }
        // Do not refresh before dispatch: a stale displayed basis must be rejected, never retargeted.
        val captured = basis ?: return
        val targets = if (all) matches else listOfNotNull(matches.getOrNull(active))
        val result = model.dispatch(EditorIntent.ReplaceFound(captured, targets, replacement.text.toString(), all))
        owners.note(model, "Find/Replace: $result")
        refresh(true)
        reveal()
    }
    companion object {
        private const val REFRESH_MS = 200L
    }
}
