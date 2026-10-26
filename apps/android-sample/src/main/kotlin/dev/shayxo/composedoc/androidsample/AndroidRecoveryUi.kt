package dev.shayxo.composedoc.androidsample

import android.app.AlertDialog
import dev.shayxo.composedoc.data.AndroidRecoveryEntry
import java.text.DateFormat
import java.util.Date

/** Metadata-only listing. A row's exact owner/generation is captured before any confirmation or async IO. */
internal fun showRecovery(activity: MainActivity, owners: AndroidOwners) = settled(owners) {
    owners.work({ owners.recoveryStore.entries() }) { entries ->
        if (activity.isDestroyed || activity.isFinishing) return@work
        owners.recoverableCount = entries.size
        val choices = entries.map { entry ->
            "${entry.name} · ${DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.MEDIUM,
            ).format(Date(entry.timestamp))}" +
                " · #${entry.sequence}" +
                (if (entry.hasProposal) " · separate unfinished proposal" else "") +
                entry.warning?.let { " · $it" }.orEmpty()
        }.toMutableList().apply { add("Save safety copies (failed and completed attempts)") }
        AlertDialog.Builder(activity).setTitle("Local recovery · explicit restore into a new unsaved tab")
            .setItems(choices.toTypedArray()) { _, index ->
                if (index <
                    entries.size
                ) {
                    recoveryEntry(activity, owners, entries[index])
                } else {
                    showSaveSafety(activity, owners)
                }
            }.setNegativeButton("Later", null).show()
    }
}

private fun recoveryEntry(activity: MainActivity, owners: AndroidOwners, entry: AndroidRecoveryEntry) {
    val options = mutableListOf("Restore committed copy", "Export recovery envelope", "Discard this generation")
    if (entry.hasProposal) options.add("Open separate unfinished proposal")
    AlertDialog.Builder(activity).setTitle("${entry.name} · captured generation${entry.sequence}")
        .setItems(options.toTypedArray()) { _, index ->
            when (index) {
                0 -> restoreEntry(owners, entry, proposal = false)
                1 -> owners.launchPicker(activity, AndroidPickerKind.EXPORT_RECOVERY, entry.owner, entry.sequence)
                2 -> AlertDialog.Builder(activity).setTitle("Discard captured recovery generation?")
                    .setMessage("Only ${entry.name} through generation${entry.sequence}. A newer checkpoint survives.")
                    .setPositiveButton("Discard") { _, _ ->
                        owners.checkpoints.discard(entry.owner, entry.sequence) { result ->
                            val applied = result.getOrElse {
                                owners.note(owners.current, "Discard failed; recovery retained: ${it.message}")
                                return@discard
                            }
                            owners.status = if (applied) {
                                "Generation discarded; newer arrivals retained"
                            } else {
                                "Recovery changed; nothing discarded"
                            }
                            showRecovery(activity, owners)
                        }
                    }.setNegativeButton("Cancel", null).show()
                else -> restoreEntry(owners, entry, proposal = true)
            }
        }.setNegativeButton("Cancel", null).show()
}

private fun restoreEntry(owners: AndroidOwners, entry: AndroidRecoveryEntry, proposal: Boolean) {
    val original = owners.tab
    val generation = ++original.operation
    val basis = original.model.snapshot().committed
    owners.work({ owners.recoveryStore.read(entry.owner, entry.sequence) }, original.model) { record ->
        if (original.operation != generation) return@work
        val foreground = owners.current === original.model && original.model.snapshot().committed.same(basis)
        val document = if (proposal) checkNotNull(record.proposal) else record.document
        owners.add(
            document,
            if (proposal) "Recovered proposal (unsaved)" else "Recovered ${entry.name}",
            activate = foreground,
        )
        owners.note(original.model, "Separate unsaved copy opened; original/recovery retained; no callback replay")
    }
}
