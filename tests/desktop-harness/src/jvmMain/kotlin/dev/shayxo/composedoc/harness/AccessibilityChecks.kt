package dev.shayxo.composedoc.harness

import dev.shayxo.composedoc.domain.EditAction
import dev.shayxo.composedoc.layout.TypographyProfile
import dev.shayxo.composedoc.model.Selection
import dev.shayxo.composedoc.model.TextStyleFlags
import dev.shayxo.composedoc.model.UnitKind
import dev.shayxo.composedoc.model.evidenceJson
import dev.shayxo.composedoc.ui.EditorSurface
import dev.shayxo.composedoc.ui.findSurface
import dev.shayxo.composedoc.workspace.showEditor
import java.awt.event.InputMethodEvent
import java.awt.font.TextHitInfo
import java.io.File
import java.text.AttributedString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

fun runAccessibilityHostChecks(surface: EditorSurface) {
    val editable = checkNotNull(surface.accessibleContext.accessibleEditableText)
    val e = surface.viewModel.verificationEngine
    val textEvents = ArrayList<java.beans.PropertyChangeEvent>()
    val textListener = java.beans.PropertyChangeListener { event ->
        if (event.propertyName == javax.accessibility.AccessibleContext.ACCESSIBLE_TEXT_PROPERTY) textEvents += event
    }
    surface.accessibleContext.addPropertyChangeListener(textListener)
    editable.setTextContents("اردو English")
    surface.accessibleContext.removePropertyChangeListener(textListener)
    val inserted = textEvents.single().newValue as javax.accessibility.AccessibleTextSequence
    check(inserted.startIndex == 0 && inserted.endIndex == 12 && inserted.text == "اردو English")
    editable.selectText(12, 12)
    val before = e.snapshot()
    editable.replaceText(0, 4, "کتاب")
    check(editable.getTextRange(0, editable.charCount) == "کتاب English")
    e.perform(EditAction.Undo)
    check(e.snapshot().committed == before.committed && e.snapshot().selection == before.selection)
    e.perform(EditAction.Redo)
    check(e.snapshot().visible.text == "کتاب English")
    editable.selectText(0, 4)
    val stylesBefore = e.snapshot()
    val attributes = SimpleAttributeSet().apply {
        StyleConstants.setBold(this, true)
        StyleConstants.setItalic(this, true)
    }
    var publications = 0
    val attributeEvents = ArrayList<java.beans.PropertyChangeEvent>()
    val styleListener = java.beans.PropertyChangeListener { event ->
        if (event.propertyName ==
            javax.accessibility.AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED
        ) {
            attributeEvents += event
        }
    }
    surface.accessibleContext.addPropertyChangeListener(styleListener)
    val oldObserver = e.onChange
    e.onChange = {
        oldObserver(it)
        publications++
    }
    try {
        editable.setAttributes(0, 4, attributes)
    } finally {
        e.onChange = oldObserver
        surface.accessibleContext.removePropertyChangeListener(styleListener)
    }
    check(publications == 1 && e.snapshot().undoCount == stylesBefore.undoCount + 1)
    val attributeSequence = attributeEvents.single().newValue as javax.accessibility.AccessibleAttributeSequence
    check(
        attributeSequence.startIndex == 0 &&
            attributeSequence.endIndex == 4 &&
            StyleConstants.isBold(attributeSequence.attributes) &&
            StyleConstants.isItalic(attributeSequence.attributes),
    )
    check(e.snapshot().visible.units.take(4).all { it.style == TextStyleFlags(true, true) })
    check(
        StyleConstants.isBold(editable.getCharacterAttribute(0)) &&
            StyleConstants.isItalic(editable.getCharacterAttribute(0)),
    )
    e.perform(EditAction.Undo)
    check(e.snapshot().committed == stylesBefore.committed && e.snapshot().selection == stylesBefore.selection)
    e.perform(EditAction.Redo)
    val styled = e.snapshot()
    check(runCatching { editable.replaceText(0, 9999, "bad") }.isFailure)
    check(e.snapshot() == styled)
    val unsupported = SimpleAttributeSet().apply {
        StyleConstants.setFontSize(this, 99)
        StyleConstants.setBold(this, false)
    }
    check(runCatching { editable.setAttributes(0, 4, unsupported) }.isFailure)
    check(e.snapshot() == styled) { "Unsupported attribute must not partially change supported fields" }
    editable.selectText(editable.charCount, editable.charCount)
    val targetedBefore = e.snapshot()
    editable.insertTextAtIndex(2, "X")
    check(e.snapshot().committed.units[2].style == TextStyleFlags(true, true)) {
        "AT insertion inherited unrelated current-caret style"
    }
    e.perform(EditAction.Undo)
    check(e.snapshot().committed == targetedBefore.committed && e.snapshot().selection == targetedBefore.selection)
    val beforeUnrelatedCaret = e.snapshot()
    check(runCatching { editable.setAttributes(2, 2, attributes) }.isFailure)
    check(e.snapshot() == beforeUnrelatedCaret)

    editable.setTextContents("A😀Z")
    val surrogate = e.snapshot()
    editable.delete(1, 2)
    check(
        e.snapshot().visible.units.map {
            it.char.code
        } == listOf(65, 0xDE00, 90),
    ) { "AT protocol range was grapheme-snapped or normalized" }
    e.perform(EditAction.Undo)
    check(e.snapshot().committed == surrogate.committed)
    editable.setTextContents("ک")
    editable.insertTextAtIndex(1, "\u0650")
    check(e.snapshot().visible.text == "ک\u0650")
    editable.delete(1, 2)
    check(e.snapshot().visible.text == "ک")

    e.selectAll()
    e.replace("ک\nتاب", "StructureFixture", paragraphBreaks = true)
    val structural = e.snapshot().committed
    editable.replaceText(0, 1, "ق")
    check(e.snapshot().committed.units.drop(1) == structural.units.drop(1))
    check(e.snapshot().committed.units[1].kind == UnitKind.PARAGRAPH_BREAK)
    editable.setTextContents("الف\nب")
    check(e.snapshot().visible.units[3].kind == UnitKind.TEXT) { "Opaque AT LF must not infer Enter" }

    fun input(text: String, count: Int, caret: Int?) = surface.dispatchEvent(
        InputMethodEvent(
            surface,
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            AttributedString(text).iterator,
            count,
            caret?.let(TextHitInfo::leading),
            null,
        ),
    )
    editable.setTextContents("AB")
    editable.selectText(0, 0)
    input("に", 0, 1)
    editable.replaceText(1, 2, "X") // Capture retained A, not the caret after settlement.
    editable.selectText(2, 3)
    check(e.snapshot().waiting)
    input("日", 1, null)
    check(e.snapshot().committed.text == "日XB")
    e.perform(EditAction.Undo)
    check(e.snapshot().committed.text == "日AB")
    e.perform(EditAction.Undo)
    check(e.snapshot().committed.text == "AB")

    editable.selectText(0, 0)
    input("に", 0, 1)
    editable.delete(0, 1) // Provisional ID will not survive a changed final candidate.
    input("日", 1, null)
    check(e.snapshot().committed.text == "日AB" && e.snapshot().notice.contains("target changed", ignoreCase = true))

    editable.setTextContents("A")
    editable.setAttributes(
        0,
        1,
        SimpleAttributeSet().apply {
            StyleConstants.setBold(this, false)
            StyleConstants.setItalic(this, false)
        },
    )
    editable.selectText(1, 1)
    val beforePendingAttributes = e.snapshot()
    input("に", 0, 1)
    editable.setAttributes(0, 1, attributes)
    e.perform(EditAction.Undo)
    input("日", 1, null)
    check(e.snapshot().committed.text == "A日" && e.snapshot().marked == null)
    check(e.snapshot().committed.units.first().style == TextStyleFlags())
    check(e.snapshot().undoCount == beforePendingAttributes.undoCount + 1) {
        "Queued Undo must remove the real multi-attribute transaction, preserving the composition group"
    }
    e.perform(EditAction.Undo)
    check(e.snapshot().committed.text == "A")
    while (e.snapshot().undoCount > 0) e.perform(EditAction.Undo)
    check(e.snapshot().visible.text.isEmpty())
    println(
        "PASS editable accessibility: exact UTF-16, target capture, attributes, structure and composition history; OS AT NOT RUN",
    )
}

/** Run outside EDT so the actual accessibility owner-thread handoff can be tested. */
fun runAccessibilityThreadChecks(resultFile: File) {
    check(!SwingUtilities.isEventDispatchThread())
    lateinit var window: javax.swing.JFrame
    lateinit var surface: EditorSurface
    SwingUtilities.invokeAndWait {
        window = showEditor(offscreenTest = true)
        surface = checkNotNull(findSurface(window))
    }
    try {
        var windowTreeContexts = 0
        SwingUtilities.invokeAndWait {
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<javax.accessibility.AccessibleContext, Boolean>(),
            )
            val editors = ArrayList<javax.accessibility.AccessibleContext>()
            fun visit(accessible: javax.accessibility.Accessible?) {
                val context = accessible?.accessibleContext ?: return
                if (!seen.add(context)) return
                check(seen.size <= 500) { "Mounted accessibility tree exceeded bounded fixture" }
                if (context.accessibleName == "Document editor") editors += context
                for (index in 0 until context.accessibleChildrenCount) visit(context.getAccessibleChild(index))
            }
            visit(window)
            windowTreeContexts = seen.size
            check(editors.size == 1 && editors.single() === surface.accessibleContext) {
                "Mounted editor is not reachable through the standard Accessible window tree"
            }
        }
        val editable = checkNotNull(surface.accessibleContext.accessibleEditableText)
        editable.setTextContents("اردو English")
        editable.replaceText(0, 4, "کتاب")
        check(editable.getTextRange(0, editable.charCount) == "کتاب English")
        val initialAttributes = checkNotNull(editable.getCharacterAttribute(0)) // Bridge worker, not EDT.
        check(
            StyleConstants.getFontFamily(initialAttributes) == "SansSerif" &&
                StyleConstants.getFontSize(initialAttributes) == 18,
        )
        var typographyEvents = 0
        SwingUtilities.invokeAndWait {
            surface.viewModel.verificationEngine.selectAll()
            surface.viewModel.verificationEngine.replace("ک\nتاب", "TypographyFixture", paragraphBreaks = true)
            editable.setAttributes(0, 1, SimpleAttributeSet().apply { StyleConstants.setBold(this, true) })
            editable.setAttributes(2, 3, SimpleAttributeSet().apply { StyleConstants.setItalic(this, true) })
            surface.viewModel.verificationEngine.move(Selection(5, 5))
            surface.viewModel.verificationEngine.inputMethod("に", 0, 1)
            val beforeTypography = surface.viewModel.verificationEngine.snapshot()
            check(beforeTypography.marked != null)
            val covered = ArrayList<Int>()
            val listener = java.beans.PropertyChangeListener { event ->
                if (event.propertyName == javax.accessibility.AccessibleContext.ACCESSIBLE_TEXT_ATTRIBUTES_CHANGED) {
                    check(SwingUtilities.isEventDispatchThread())
                    val old = event.oldValue as javax.accessibility.AccessibleAttributeSequence
                    val next = event.newValue as javax.accessibility.AccessibleAttributeSequence
                    check(old.startIndex == next.startIndex && old.endIndex == next.endIndex)
                    check(
                        StyleConstants.getFontFamily(old.attributes) == "SansSerif" &&
                            StyleConstants.getFontSize(old.attributes) == 18,
                    )
                    check(
                        StyleConstants.getFontFamily(next.attributes) == "Noto Nastaliq Urdu" &&
                            StyleConstants.getFontSize(next.attributes) == 26,
                    )
                    for (index in next.startIndex until next.endIndex) {
                        val observed = checkNotNull(editable.getCharacterAttribute(index))
                        check(observed.isEqual(next.attributes)) {
                            "Font notification disagreed with the published attributes"
                        }
                        check(StyleConstants.isBold(old.attributes) == StyleConstants.isBold(observed))
                        check(StyleConstants.isItalic(old.attributes) == StyleConstants.isItalic(observed))
                        covered += index
                    }
                    check(surface.viewModel.verificationEngine.snapshot() == beforeTypography)
                    typographyEvents++
                }
            }
            surface.accessibleContext.addPropertyChangeListener(listener)
            try {
                val profile = TypographyProfile.load("noto-nastaliq")
                surface.changeTypography(profile)
                check(covered == beforeTypography.visible.units.indices.toList())
                check(typographyEvents >= 3) { "Distinct inline/paragraph runs were collapsed in a font notification" }
                val count = typographyEvents
                surface.changeTypography(profile)
                check(typographyEvents == count && surface.viewModel.verificationEngine.snapshot() == beforeTypography)
            } finally {
                surface.accessibleContext.removePropertyChangeListener(listener)
            }
            surface.viewModel.verificationEngine.abortComposition()
        }
        val requested = checkNotNull(editable.getCharacterAttribute(0))
        check(
            StyleConstants.getFontFamily(requested) == "Noto Nastaliq Urdu" &&
                StyleConstants.getFontSize(requested) == 26,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        SwingUtilities.invokeLater {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            surface.viewModel.verificationEngine.selectAll()
            surface.viewModel.verificationEngine.replace("Changed basis", "ConcurrentFixture")
        }
        check(entered.await(3, TimeUnit.SECONDS))
        val worker = Thread {
            try {
                editable.replaceText(0, 4, "must not apply")
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            var queued = false
            while (worker.isAlive && System.nanoTime() < deadline) {
                if (worker.state == Thread.State.WAITING &&
                    worker.stackTrace.any { it.methodName == "invokeAndWait" }
                ) {
                    queued = true
                    break
                }
                Thread.sleep(1) // Bounded scheduling wait for this specific live request, not a goal keepalive.
            }
            check(queued) { "Accessibility worker did not reach the owner-thread queue" }
        } finally {
            release.countDown()
        }
        worker.join(3000)
        check(!worker.isAlive)
        check(failure.get()?.message?.startsWith("AccessibilityBaseChanged") == true)
        check(editable.getTextRange(0, editable.charCount) == "Changed basis")
        SwingUtilities.invokeAndWait { window.dispose() }
        check(runCatching { editable.replaceText(0, 1, "late") }.isFailure)
        resultFile.parentFile.mkdirs()
        resultFile.writeText(
            evidenceJson(
                mapOf(
                    "status" to "PASS", "ownerThreadMarshalling" to "PASS",
                    "windowTreeExposure" to "PASS", "windowTreeContextsVisited" to windowTreeContexts,
                    "requestedTypographyWorkerRead" to "PASS", "typographyNotifications" to typographyEvents,
                    "typographyPreservesCompositionAndHistory" to "PASS",
                    "changedBasisQueuedRequest" to "REJECTED_PRESERVED", "retiredSurfaceAdmission" to "REJECTED",
                    "scope" to
                        "Java AccessibleEditableText API calls on a real mounted AWT surface; no OS screen reader or system clipboard qualification",
                ),
            ),
            Charsets.UTF_8,
        )
        println("PASS accessibility thread/admission checks; OS screen reader NOT RUN")
    } finally {
        SwingUtilities.invokeAndWait { window.dispose() }
    }
}
