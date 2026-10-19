# Next delivery: recover unfinished writing after a crash

Work in `D:\Work\WYSIWYG-Text-Editor-Compose`. Complete this bounded implementation milestone autonomously; do not stop after planning or a headless demonstration.

Read AGENTS.md, docs/project/PROJECT-JOURNAL.md, docs/project/PRODUCT-ROADMAP.md, the latest handoff, docs/reports/v6-desktop-independent-handoff-audit-20261019.md and finalized V6 decision D08. Preserve frozen files, user documents and concurrent work. Inspect Git/task state before edits. The previous V6 desktop batch is accepted to its stated scope; reuse its tests and evidence rather than restarting the audit.

## Deliverable

Make unfinished writing recoverable after process termination: active provisional composition, retained composition drafts and quarantined input must have a separate durable recovery path. Keep the existing committed checkpoint path and named-document Save behavior intact. Give the user a usable recovery interface. This work implements an existing V6 requirement, not a new architecture or a production schema freeze.

### 1. Durable records with exact ownership

Inspect existing LocalRecovery, retained-draft/export/apply and quarantine machinery first. Reuse compatible codecs, checksums, background publication, resource handling and tests. Do not introduce another canonical document owner or homegrown storage framework.

Store versioned unfinished records with stable record/document/window ownership, capture generation, committed basis hash, exact raw UTF-16 and attributes, structural metadata including marker seeds, required resources, candidate/marked/selection evidence and native proposal provenance where present. Use immutable bounded deltas and pinned prerequisites as D08 requires; do not serialize live OS handles as resumable sessions. Publish prerequisites before references and validate all lengths, versions, checksums and ownership on load.

Keep capture, successful durable publication, accepted committed checkpoint and resolved/discarded states distinct. Coalesce only superseded captures of the same active candidate, not independent unresolved drafts or callbacks. Serialize owner-consistent capture before background IO; prevent older completions overwriting newer generations. State a measured, truthful checkpoint window. Preserve existing committed recovery even when unfinished capture fails.

On restart active composition is null. Offer unfinished work separately; never automatically commit provisional text, replay old callbacks, resume an OS composition or overwrite the named original. A durable record is recovery material, not authorization to replay an edit.

### 2. Usable recovery workflows

Extend the existing recovery UI to distinguish committed copies, unfinished drafts and quarantined input. Show source, timestamp, persistence status and readable previews without loading all records. Provide inspect, lossless export, open a recoverable attributed draft as a separate unsaved document, Later, and explicit selected-record discard. Preserve raw quarantine evidence even if it cannot safely become a document.

Allow existing Apply Draft only with its exact basis/ownership checks and one undoable transaction. A stale basis leaves the record intact and offers separate-document recovery/export. Do not clear a record merely because application or discard was requested. Handle crashes between application, document checkpoint and record-resolution publication without silently losing or automatically duplicating text. Delete only the selected captured generations; newly arriving records survive.

Normal commit/cancel and newer committed checkpoints must not leave misleading permanently active recovery candidates. Conversely, do not prune the last recoverable draft before its accepted replacement is durably protected. Define and test these lifecycle transitions explicitly.

### 3. Bounds and failure behavior

Implement D08 defaults: 4 MiB resident metadata/decoded-preview cache, 16 unresolved draft records and 64 MiB accounted retained disk payload per document. Count unique pinned bases/resources, including orphaned publication files awaiting safe cleanup. Account separately for active capture/in-flight work and bound that queue too. Use paged inspection.

Never silently evict unresolved records or hide them in an unaccounted archive. Refuse cancellable operations/backpressure before capacity; offer export and explicit discard. For noncancelable host input exceeding reserved capacity or failing storage, retain available evidence, expose RecoveryCaptureFailed and suspend automatic admission safely. Do not claim unconditional losslessness. Document the supported envelope, retry behavior and what can still be lost before durable publication. No power-loss guarantee from process-kill tests.

### 4. Tests that exercise the actual path

First reproduce loss of an unfinished draft after process termination in an isolated old-build fixture. Then test the new path using actual owner/recovery code, not a separate model that writes its own ideal records.

Cover active preedit; partial acceptance plus residual; explicit abort; normal acceptance; retargeted multiple islands; formatting and marker metadata; images/resources; stale quarantined callbacks; multiple windows; same filename with changed contents; non-BMP, lone surrogates, Urdu marks and mixed scripts. Preserve committed-prefix and attributed history behavior.

Exercise slow and out-of-order writes, retry, malformed/truncated records, missing prerequisites, process termination before/after publication, resource pinning/cleanup, quota boundaries, low-space/write-denial simulation, Apply/Discard failures and callbacks arriving during discard. All faults use owned temporary directories/processes; never fill the user's drive or damage real recovery files.

At a finite, documented set of persistence boundaries kill owned JVMs and restore in fresh JVMs. Compare exact content, attributes, identity where required, structural properties and resources. Verify no automatic callback replay or active composition restoration. Retain counterexamples and raw failure receipts. Relevant existing kernel, V6, clipboard, history, file, committed-recovery and quarantine regressions must pass on the same fresh build.

### 5. Actual desktop delivery

Use supported computer control for a short same-build walkthrough: recovery chooser, previews, separate unsaved draft, stale Apply, export, Later and selected discard; then Save As and fresh reopen. Inspect short and normal windows. Synthetic composition is acceptable for controlled failure injection, but label it synthetic; normal UI clicking does not qualify native IME behavior. Do not repeat Japanese installation/activation work during this batch.

If computer control is unavailable, complete independent work and report the exact native checks NOT RUN. Never replace visual checks with a global green claim.

## Finish

Deliver the launch command, a five-minute recovery walkthrough, actual fixes, exact build identity, tests and process-interruption receipts, screenshots and remaining loss windows. Update docs/guides/RECOVERY.md, docs/guides/WRITING.md and the newest docs/reports/overnight-progress.md entry. Keep explanations simple.

Do not start collaboration, tables, PDF, public library extraction, dependency upgrades or redesign in this batch. Do not publish, push, select a license or alter the frozen V6 baseline. Finish the recovery milestone, clean up only owned test processes and stop.
