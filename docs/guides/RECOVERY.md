# Accepted and unfinished local recovery

Current workspace implementation: 20 October 2026. Pages/font/marker continuation: [current batch report](../reports/portable-fonts-markers-pages-20261020.md). Exact executed results and native limitations: [workspace batch report](../reports/v6-local-writing-workspace-batch.md). Prior guide bytes are preserved in [the pre-workspace recovery guide](../archive/recovery-through-v6-desktop-20261019.md); its memory-only claims describe the older build.

Current build202031 passes25 automated owned-process interruption cases and captured-target UI regressions. Recovery Discard now removes its captured row when the write completes, even if another row was selected during IO; persisted record/generation semantics are unchanged. The actual desktop kill/relaunch remains dated192824 evidence:264-unit accepted document, separate282-unit attributed candidate, exact raw quarantine export and fresh saved-result Open. Synthetic composition is labeled; no OS candidate session resumed. Native selected-discard/new-arrival and cleanup remain BLOCKED after two supported targeting attempts failed before delivering dialog input. Exported/saved copies and both original unresolved records are kept. See [the precise diagnosis/manual checklist](../reports/v6-workspace-native-finish-20261019.md); no native discard success is claimed.

## What is protected

Accepted document roots still use separate automatic copies, two alternating checksummed records, a forced temporary write and atomic replacement. Scheduling is two seconds plus queue/IO. Named files change only through explicit Save. Untitled and multiple windows have distinct recovery ownership. Old successful generations cannot be overwritten by a late older generation.

Unfinished writing now has a separate private version-1 store under `recovery/unfinished-records/`. Owner-consistent immutable captures contain an exact committed base, an immutable byte delta for the attributed visible draft, identity sidecars, structure/marker/page data, owned resources, selection/mark evidence and available native proposal metadata. Raw UTF-16, including lone surrogates, is preserved. Raw invalid quarantine counts are evidence, not accepted edit ranges.

The existing document codec remains the content validator; recovery additionally preserves exact local identities needed for basis evidence. Content-addressed prerequisite slabs are published before record references. Resources remain pinned within those exact base/delta slabs. Identical slabs are shared; repeated resource bytes in different bases are conservatively counted, not treated as free storage. Records alternate generations with checksums and an explicit resolution marker. Corrupt/incomplete records retain valid fallbacks or remain visible as unreadable evidence; missing prerequisites fail restoration without consuming the record.

The runtime has one in-flight owner batch and one coalesced newest owner snapshot. Only obsolete captures of the same active candidate may be superseded. Independent retained drafts and quarantined callbacks remain distinct. Scheduling is 700 ms plus encoding/queue/disk time; wait for the published receipt. On failure, the writer sees RecoveryCaptureFailed and automatic input admission pauses. Retry occurs for pending storage work; exporting/discarding selected work may free capacity. A failed persistence decision does not consume the selected in-memory/durable evidence.

## Restoring and inspecting

On restart there is **no active composition**. No native handles/session are resumed and no old callback is replayed. Recovery Copies offers accepted content; Unfinished recovery offers unfinished drafts and quarantine separately. Current live owners are hidden from crash recovery lists.

- Inspect loads only one selected draft, shows provenance and a bounded first-2048-unit preview. Export preserves all captured units/attributes/resources.
- Restore unsaved draft opens a separate attributed document with fresh owner/structure identity and empty Undo. It does not overwrite a named original or consume the recovery record. Repeated restore of the same record focuses the already open copy rather than duplicating it without explanation.
- Lossless export writes a new `.astra-recovery` evidence bundle containing the versioned record, exact base and delta, lengths and checksums. It refuses existing destinations. It is evidence for rescue, not a callback replay program. To obtain an ordinary `.astra` draft, Restore unsaved draft and Save As.
- Quarantined callbacks are inspect/export-only. Do not interpret their unverified coordinates or text as accepted document edits.
- Later closes the chooser and keeps work. Explicit Discard affects only the selected captured generation. Newly arriving records/generations survive stale discard attempts. Failure retains the record and displays an error.
- Apply in the live retained-draft controls still requires the originating runtime owner plus exact committed root/revision/identity generation. A restarted/stale owner cannot Apply. Use separate draft recovery/export instead. One successful Apply is one Undo transaction.

Normal acceptance/cancellation and explicit resolution are persisted. An accepted result is protected by a separately discoverable committed rescue copy **before** the unfinished record is resolved. A crash before that resolution can offer both the accepted result and older unfinished evidence; neither is automatically applied. A failed Apply-resolution write keeps the record, even when the applied result exists in memory. Save/accepted checkpoints never authorize replaying or silently discarding unfinished input. Deliberate close waits for unfinished discard decisions; new arrivals/revision changes keep the window open.

## Capacity and supported envelope

Per-document defaults: 16 unresolved records, 4 MiB accounted resident record metadata/decoded-preview budget, and 64 MiB accounted retained disk payload. Disk accounting includes unique prerequisite slabs, both record slots, accepted rescue staging, decision markers and abandoned temporary publications pending safe cleanup. There is no silent FIFO eviction or unaccounted archive. A small decision reserve (64 KiB within the default disk budget) lets selected discard publish even when the ordinary payload budget is full. Conservative cleanup preserves unresolved/fallback prerequisites; unreadable records block aggressive cleanup.

Each individual record header is bounded to 256,000 bytes. Current document/active composition and worker captures have separate budgets; this is not a total JVM heap bound. One captured batch is bounded by a conservative 64 MiB estimate; at most one executing plus one pending batch is retained. Document snapshots retain the existing 100,000-unit / bounded resource/file envelope. Provenance has a bounded capture envelope; oversized evidence emits failure rather than claiming success.

Noncancelable callbacks have a reserved live quarantine envelope: combined unresolved count at most 16, at most 100,000 UTF-16 units per callback and 1 MiB live callback text. Beyond it, existing available evidence remains and RecoveryCaptureFailed explicitly states that incoming data could not be retained; automatic admission is suspended. This is an honest loss boundary, not unconditional protection from arbitrary OS input/storage exhaustion. Cancellable operations must be refused/backpressured at capacity; export/discard before retrying.

## What can still be lost

- Keystrokes/candidates after the last completed accepted or unfinished publication.
- Captures still queued/being encoded/written, or data exceeding the declared envelope.
- Input arriving when storage is unavailable or after the reserved noncancelable envelope is exhausted; the visible failure does not mean it was saved.
- Native platform state, callback authority, selection intent not captured yet and previous-process Undo/Redo history.

Process termination tests exercise finite publication/lifecycle boundaries. They do not prove power-loss durability, disk-controller behavior, arbitrary filesystem compare-and-swap or every OS IME lifecycle. Provisional input remains separate from canonical accepted content even after it becomes durable recovery evidence.

## Files and compatibility

New canonical documents are private `.astra` v9, reading v1–v8 with documented defaults. Pre-v8 files receive A4 portrait / 14 mm uniform margins without rewriting the source. Pre-v9 files receive ordinary hard-break kinds, default keep/widow/orphan rules and no running flows. v7 marker migration remains preserved. Explicit page breaks, paragraph pagination and separate attributed header/footer flows survive committed recovery, retained-draft export/application and Save/Open. Rich fragments are v5, reading v1–v4; destination page setup and running flows are retained on paste. Workspace index and template/recovery envelopes are private version 1 and reject unsupported/malformed versions. These are prototype versions, not production schema/API freezes.

Workspace uses two bounded atomic metadata slots plus a cooperating-process lock. Corrupt newest metadata falls back; corrupt metadata never deletes documents or recovery. Recents and template cleanup do not delete arbitrary source files. View/zoom/window metadata is independent of canonical content and history.

Use `--recovery-dir=D:\YourUnusedTestFolder\recovery` for disposable tests; never fill the real disk or kill unrelated Java processes. See [WRITING.md](WRITING.md) for launch and the connected ten-minute exercise.

## Android recovery repair branches — 24 October 2026

The rules above describe the desktop workspace. Android has separate private
storage and does not assume that content URIs are filesystem paths. Recovery
PR35 (`codex/android-recovery-close`, e60f9ee) makes Keep recovery & close create
and read back a fresh generation for its captured owner/content and separate
unfinished evidence, even when an earlier unchanged checkpoint was discarded.
Every bounded close request receives one durable, failed or cancelled outcome;
failure leaves the document open. A delayed pre-discard write cannot revive the
discarded generation. Queue emptiness is not proof that content is recoverable.

Actual native Save As, captured-generation discard, cancelled close, unchanged
Keep/close, owned process kill and fresh explicit restore were exercised on the
identified initial repair APK. Saved/restored bytes and independently expected
attributes match. Later source/build receipts are separate; this does not qualify
every subsequent native control or power-loss durability. Restore/export failures
must preserve their source records. See the Android integration guide and the
dated repair disposition for branch identities and outstanding qualifications.

The latest toolbar candidatebdf1e35 / APK01494420 also passes the retained nine
mounted close/race/failure groups and both actual owned SIGKILL witnesses in the
66-group packaged campaign. Its interruption receipts independently compare accepted
text, attributed structure, resources and separate unfinished evidence. They do not
prove keystrokes after the last completed checkpoint survive, nor force-stop,
power-loss or genuine composing-IME qualification. Native provider SaveAs/close/Open
on earlierb6354efa preserves exact document/resource bytes; that scoped result is
recorded separately from the latest candidate's instrumented recovery checks.
