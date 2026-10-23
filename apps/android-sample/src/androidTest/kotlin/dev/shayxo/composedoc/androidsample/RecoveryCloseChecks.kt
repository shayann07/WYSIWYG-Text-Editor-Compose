package dev.shayxo.composedoc.androidsample

import android.view.accessibility.AccessibilityNodeInfo
import dev.shayxo.composedoc.model.Document
import dev.shayxo.composedoc.ui.EditorIntent
import java.io.File
import java.io.RandomAccessFile

internal fun AdapterInstrumentation.recoveryCloseChecks() {
    group("discard then unchanged keep-and-close requires a new durable generation") {
        val tab = main {
            activity.owners.add(dev.shayxo.composedoc.model.Document(emptyList()), activate = true).also {
                it.model.dispatch(EditorIntent.Type("اُردو discarded draft"))
                activity.owners.checkpoints.flush()
            }
        }
        waitUntil { main { tab.recovery.startsWith("Recovery #${tab.sequence} completed") } }
        val oldGeneration = main { tab.sequence }
        check(activity.owners.recoveryStore.discard(tab.id, oldGeneration))
        main {
            activity.owners.checkpoints.discarded(tab.id, oldGeneration)
        }
        assertCloseDisclosure()
        main { activity.owners.closeDocument(tab) }
        waitUntil { main { tab !in activity.owners.tabs } }
        val entries = activity.owners.recoveryStore.entries().filter { it.owner == tab.id }
        check(entries.isNotEmpty()) { "Close reported success after discard with no replacement checkpoint" }
        val restored = activity.owners.recoveryStore.read(tab.id, entries.single().sequence)
        check(entries.single().sequence > oldGeneration)
        check(restored.document.text == "اُردو discarded draft")
    }
    group("two mounted owners closing during one publication both receive their outcome") {
        val tabs = main {
            val owners = activity.owners
            val first = owners.add(dev.shayxo.composedoc.model.Document(emptyList()), activate = true)
            first.model.dispatch(EditorIntent.Type("first pending close"))
            val second = owners.add(dev.shayxo.composedoc.model.Document(emptyList()), activate = true)
            second.model.dispatch(EditorIntent.Type("second pending close"))
            owners.closeDocument(first)
            owners.closeDocument(second)
            first to second
        }
        waitUntil { main { tabs.first !in activity.owners.tabs && tabs.second !in activity.owners.tabs } }
        check(activity.owners.recoveryStore.entries().any { it.owner == tabs.first.id })
        check(activity.owners.recoveryStore.entries().any { it.owner == tabs.second.id })
    }
    repeatAndEdit()
    discardInFlight()
    failedAndQuota()
    unfinishedAndTeardown()
}

private fun AdapterInstrumentation.assertCloseDisclosure() {
    main { requestCloseTab(activity, activity.owners, activity.inputView) }
    waitUntil { closeDialogText("Cancel") != null }
    try {
        val expected = "Keep recovery & close creates and verifies a private checkpoint before closing. " +
            "Cancel keeps editing."
        check(closeDialogText(expected) != null) {
            "Close must describe pending verification, not claim a discarded checkpoint already protects writing"
        }
    } finally {
        checkNotNull(closeDialogText("Cancel")).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        waitForIdleSync()
    }
}

private fun AdapterInstrumentation.closeDialogText(text: String): AccessibilityNodeInfo? {
    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = when {
        node == null -> null
        node.text?.toString()?.equals(text, ignoreCase = true) == true -> node
        else -> (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it)) }
    }
    return find(uiAutomation.rootInActiveWindow)
}

private fun AdapterInstrumentation.draft(text: String) = main {
    activity.owners.add(Document(emptyList()), activate = true).also { it.model.dispatch(EditorIntent.Type(text)) }
}

private fun AdapterInstrumentation.repeatAndEdit() {
    group("repeated closes receive exactly one durable or cancelled outcome each") {
        val tab = draft("repeated close")
        val results = mutableListOf<RecoveryCloseOutcome>()
        main {
            activity.owners.closeDocument(tab) { results += it }
            activity.owners.closeDocument(tab) { results += it }
        }
        waitUntil { main { results.size == 2 } }
        main {
            check(results.count { it.durable } == 1)
            check(results.count { it.state == RecoveryCloseOutcome.State.CANCELLED } == 1)
            check(tab !in activity.owners.tabs)
        }
    }
    group("editing while close is pending cancels that close and preserves newer writing") {
        val tab = draft("before close")
        var result: RecoveryCloseOutcome? = null
        main {
            activity.owners.closeDocument(tab) { result = it }
            tab.model.dispatch(EditorIntent.Type(" newer"))
        }
        waitUntil { main { result != null } }
        main {
            check(result?.state == RecoveryCloseOutcome.State.CANCELLED)
            check(tab in activity.owners.tabs && tab.model.snapshot().committed.text == "before close newer")
            activity.owners.closeDocument(tab)
        }
        waitUntil { main { tab !in activity.owners.tabs } }
        check(activity.owners.recoveryStore.read(tab.id, tab.sequence).document.text == "before close newer")
    }
}

private fun AdapterInstrumentation.discardInFlight() {
    group("discard fences an in-flight close and its delayed publication cannot resurrect evidence") {
        val tab = draft("in flight")
        val results = mutableListOf<RecoveryCloseOutcome>()
        var discarded = false
        val sequence = main {
            activity.owners.closeDocument(tab) { results += it }
            tab.sequence.also { generation ->
                activity.owners.checkpoints.discard(tab.id, generation) { discarded = it.getOrThrow() }
            }
        }
        waitUntil { main { discarded && results.size == 1 } }
        main { check(!results.single().durable && tab in activity.owners.tabs) }
        check(runCatching { activity.owners.recoveryStore.read(tab.id, sequence) }.isFailure)
        check(activity.owners.recoveryStore.entries().none { it.owner == tab.id })
        main { activity.owners.closeDocument(tab) }
        waitUntil { main { tab !in activity.owners.tabs } }
        check(activity.owners.recoveryStore.read(tab.id, tab.sequence).document.text == "in flight")
    }
}

private fun AdapterInstrumentation.failedAndQuota() {
    group("one owner write failure receives a reason without holding another close and retry succeeds") {
        val bad = draft("failed owner")
        val good = draft("independent owner")
        val directory = File(activity.owners.directory.parentFile, "rich-recovery/${bad.id}")
        check(directory.isDirectory || directory.mkdirs())
        val fault = File(directory, "order.bin")
        check(fault.mkdir())
        val results = mutableListOf<RecoveryCloseOutcome>()
        main {
            activity.owners.closeDocument(bad) { results += it }
            activity.owners.closeDocument(good) { results += it }
        }
        waitUntil { main { results.size == 2 } }
        main {
            check(bad in activity.owners.tabs && good !in activity.owners.tabs)
            check(results.single { !it.durable }.reason.isNotBlank())
        }
        check(fault.delete())
        main { activity.owners.closeDocument(bad) }
        waitUntil { main { bad !in activity.owners.tabs } }
        check(activity.owners.recoveryStore.read(bad.id, bad.sequence).document.text == "failed owner")
    }
    group("quota exhaustion refuses close with actionable outcome then allows a fresh retry") {
        val tab = draft("quota protected")
        val quota = File(activity.owners.directory.parentFile, "rich-recovery/test-owned-quota.bin")
        RandomAccessFile(quota, "rw").use { it.setLength(TEST_QUOTA_BYTES) }
        var result: RecoveryCloseOutcome? = null
        main { activity.owners.closeDocument(tab) { result = it } }
        waitUntil { main { result != null } }
        main {
            check(result?.state == RecoveryCloseOutcome.State.FAILED)
            check(result?.reason?.contains("storage full") == true || result?.reason?.contains("quota") == true)
            check(tab in activity.owners.tabs)
        }
        check(quota.delete())
        main { activity.owners.closeDocument(tab) }
        waitUntil { main { tab !in activity.owners.tabs } }
        check(activity.owners.recoveryStore.read(tab.id, tab.sequence).document.text == "quota protected")
    }
}

private fun AdapterInstrumentation.unfinishedAndTeardown() {
    group("keep-and-close durably retains accepted content and separate unfinished proposal") {
        val tab = draft("accepted ")
        waitUntil { main { activity.inputView?.model === tab.model } }
        val expected = main {
            // This is a synthetic protocol trace: fence real Gboard callbacks before injecting its proposal.
            activity.inputView!!.retireNativeSession()
            tab.model.dispatch(EditorIntent.NativeText("仮候補", 0, null))
            tab.model.snapshot().visible.text.also { activity.owners.closeDocument(tab) }
        }
        waitUntil { main { tab !in activity.owners.tabs } }
        val record = activity.owners.recoveryStore.read(tab.id, tab.sequence)
        check(record.document.text == "accepted " && record.proposal?.text == expected)
        check(record.evidence.isNotBlank())
    }
    group("teardown delivers one cancellation for every outstanding close and never reports empty-queue durability") {
        val tab = draft("teardown retained")
        val results = mutableListOf<RecoveryCloseOutcome>()
        main {
            activity.owners.checkpoints.keep(listOf(tab)) { results += it }
            activity.owners.checkpoints.keep(listOf(tab)) { results += it }
            activity.owners.checkpoints.close()
            check(results.size == 2 && results.all { it.state == RecoveryCloseOutcome.State.CANCELLED })
        }
        waitForIdleSync()
        main { check(results.size == 2 && tab in activity.owners.tabs) }
    }
}

private const val TEST_QUOTA_BYTES = 64L * 1024 * 1024
