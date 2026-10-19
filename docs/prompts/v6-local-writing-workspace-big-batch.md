# V6 big batch: a dependable local Urdu writing workspace

## Assignment and authority

Work in `D:\Work\WYSIWYG-Text-Editor-Compose`.

The owner authorizes implementation of all six workspace deliveries below plus the image workflow. This assignment supersedes the narrower `docs/prompts/v6-durable-unfinished-recovery-next.md` assignment. That file remains useful recovery detail, but its instruction to stop after recovery no longer applies. The previous V6 repair batch is accepted to its documented scope. Do not restart it or another architecture debate.

Read, in order:

1. `AGENTS.md` and `docs/project/PROJECT-JOURNAL.md`.
2. `docs/project/PRODUCT-ROADMAP.md`, applicable feature IDs in `docs/project/FEATURE-CATALOG.md`, and `docs/project/URDU-ACCEPTANCE.md`.
3. The newest entry in `docs/reports/overnight-progress.md`, `docs/reports/v6-desktop-independent-handoff-audit-20261019.md`, and the completed desktop qualification report.
4. Relevant sections of `docs/plans/v6/editor-v6-standalone.md`, especially D01–D10 and D08 recovery ownership/capacity.
5. Existing writing/recovery guides and the source/tests for the feature you are changing.

Inspect Git status and concurrent work before edits. Preserve all owner documents, previous evidence, unrelated dirty changes and frozen files. Do not message or interrupt another session. No production library/API freeze, license selection, dependency upgrade by default, push, tag or publication is authorized here. If a genuine specification contradiction appears, document a narrow versioned amendment; do not silently rewrite frozen V6.

The product remains the full Urdu-first application and reusable Compose Multiplatform library. This is a useful desktop implementation slice, not completion of the whole product. Separate reusable commands/data/IO contracts from window chrome; do not create another authoritative document owner.

## Outcome and execution policy

A writer can create a document from an Urdu template, insert and adjust images, work across named and untitled documents, resume the workspace, detect conflicting file changes, and recover both committed and durably captured unfinished writing after a crash.

Implement in dependency order: recovery/storage safety; recent/session metadata and conflict handling; templates and image workflow; workspace integration; connected verification. Finish and verify each deliverable before depending on it. Continue without returning for routine design choices or permission already granted by this assignment. Do not stop after the first passing feature. Keep a compact checklist and latest runnable checkpoint in the handoff.

If an external blocker affects one delivery, record precisely what is blocked and continue independent authorized work. Do not bypass the blocker by weakening semantics or mark the whole batch complete. Avoid broad rewrites and repeated full-suite loops: run focused checks during development and an integrated suite at final build checkpoints. Report progress simply, with what the writer can do and what remains.

## 1. Durable unfinished-input recovery

Extend existing LocalRecovery, retained-draft/export/apply and quarantine facilities. Reuse their codecs, checksum/publication paths, resource ownership and failure tests where compatible.

- Preserve active provisional composition, retained composition drafts and quarantined input as separate durable recovery material. Keep named files untouched until explicit Save and preserve committed checkpoints independently.
- Version each record. Capture stable record/document/window ownership, generation, committed basis hash, exact raw UTF-16 and attributes, structural metadata/marker seeds, resources, candidate/marked/selection evidence and available native provenance. Capture owner-consistent immutable state before worker IO.
- Use bounded immutable deltas and pinned bases/resources. Publish prerequisites before references; validate checksums, sizes, ownership and versions. Prevent old write completions replacing newer generations. Coalesce only obsolete captures from the same active candidate, never unrelated unresolved records.
- Distinguish captured, durably published, accepted checkpoint and resolved/discarded states. A normal commit/cancel must not leave misleading active candidates; cleanup must not remove the only durable copy before accepted content is protected.
- Restart with no active native composition. Offer unfinished work separately. Never replay old native callbacks, automatically accept provisional text, resume old OS sessions or overwrite the original.
- Offer inspect, lossless export, Later, separate unsaved attributed-draft recovery and explicit selected-record discard. Preserve quarantine evidence even if it cannot safely become a document. Existing Apply Draft requires exact basis/ownership checks and one undoable transaction. Stale Apply leaves the record intact and offers separate recovery/export.
- Persist lifecycle decisions safely. Test crash windows between Apply, accepted checkpoint and record resolution; do not silently lose data or automatically apply it twice. Failed Apply/Discard persistence must not silently consume the record. New arrivals survive snapshot discard.
- Enforce D08 defaults: 4 MiB resident metadata/decoded-preview cache, 16 unresolved drafts and 64 MiB accounted retained disk payload per document. Count unique pinned bases/resources and abandoned publications awaiting safe cleanup; bound in-flight capture separately. Stream/page previews. No silent FIFO eviction or unaccounted archive.
- At capacity, refuse cancellable operations or apply backpressure and offer export/discard. For noncancelable host input that cannot fit or cannot be persisted, keep available evidence, emit RecoveryCaptureFailed and safely suspend automatic admission. State the supported capture envelope and actual loss window. Process-kill tests do not prove power-loss durability.

## 2. Recent documents and session restoration

Provide a usable recent-document list and restore-last-workspace option using the existing multiple-window model. Tabs are not required; do not rewrite window ownership to add them.

- List meaningful names, paths where needed to disambiguate, last-opened state, missing/moved status and recovery availability. Add Open, Locate moved file, Remove from recents and Clear recents. Removing a recent entry never deletes its file or recovery material.
- Persist workspace metadata with bounded/versioned atomic storage. A corrupt index must be recoverable without discarding documents. Treat paths as references, not permanent document identities.
- Restore named documents only after validating file identity/content basis. For moved, replaced or changed files, disclose the mismatch and require an explicit open/locate/recovery choice. Do not apply old selection offsets blindly to different text.
- Restore supported view/zoom/font preferences, caret/selection and scroll anchor when the document basis and positions remain valid; otherwise use a documented safe default. Window metadata changes do not create content history or mark the document dirty.
- Untitled/unsaved work uses its own recovery identity; it is not silently dropped because it lacks a filename. Distinguish reopening the last saved file from recovering newer content. Do not duplicate the same recovery record into unexplained windows.
- Distinguish deliberate closure from abnormal termination. Offer restoration instead of an endless auto-reopen/crash loop. Empty Undo history on a fresh owner remains acceptable; do not pretend it was restored.
- Keep metadata private/local and bounded. Do not upload filenames, text or images.

## 3. External file-change and overwrite protection

Prevent unnoticed conflicts between editor windows/processes and other applications.

- Track the bytes/content fingerprint of the last successfully opened or saved file, with an explicit absent-file state. Use path/identity metadata as supporting evidence, not mtime alone. Watchers are hints; revalidate at Open/reload/Save admission and immediately before publication as supported.
- Offer a clear conflict state with Keep editing, Reload external version and Save a Copy. Show useful comparison information such as path, existence, size/fingerprint and whether local edits exist; a full visual document diff is not required.
- Reloading dirty content requires explicit confirmation after preserving recoverable local work. Failure to preserve it blocks destructive reload. A changed original must not be overwritten just because an old Save dialog was already open.
- Make Save/Save As transactional, preserve existing originals on failures, and retain later edits made while a write is in flight. A Save completing must not mark a newer document revision clean.
- Cover two editor windows saving the same path, deletion/recreation, file rename, read-only/write-denied destination, and Save As to an existing file.
- Specify the actual concurrency guarantee. A preflight hash plus rename is not a universal filesystem compare-and-swap against arbitrary external writers. Coordinate cooperating editor instances and test the publication race; where safe overwrite cannot be established, fail with a conflict or Save a Copy route. Never claim universal race-free protection unsupported by the platform.

## 4. Reusable Urdu document templates

Ship a small usable template workflow, not static demo screenshots.

- Include Blank Urdu, Urdu Letter and Urdu Article/Report templates with appropriate direction, typography, heading/list styles and sample placeholders. Use original text with an explicit non-expert linguistic-review label where needed. Include one mixed-script example.
- Create from template always creates an independent unsaved document with fresh document/structure identities as required. Editing it never mutates the source template. Copy or safely pin embedded resources so moving/removing the original template cannot break the created document.
- Provide Save as Template, choose template and remove a user-template entry with clear semantics. Never delete an arbitrary source document through template-list cleanup.
- Preserve supported document settings separately from presentation-only zoom/window preferences. For page presets, reuse existing capability. If page size/orientation/margins are not yet editable, add a narrow persisted page-settings command for A4/Letter, portrait/landscape and validated margins, using the existing page layout; do not present no-op controls or claim PDF qualification. Verify reflow, caret reveal, image fitting and Undo policy. Do not reset whole-paragraph shaping/bidi at page seams.
- Version private envelopes explicitly when necessary; retain supported old readers, reject unsupported future versions before mutation, and do not rewrite originals on Open. Templates are not the final production schema or public API freeze.

## 5. Workspace usability and status

Integrate the features into the current shell without a cosmetic rewrite.

- Give New/from template, Open/recent, Save/Save As, recovery and document/image properties clear destinations and working keyboard access. Preserve working Ctrl+F/H/Z/Y and document navigation.
- Show document name, dirty state, successful Save, recovery pending/completed/failed, external conflict and unavailable resources accurately. A spinner or queued write is not a durable success.
- Separate transient notices from persistent actionable warnings. Errors keep entered values and offer retry/cancel; no silent dialog closure or inert buttons.
- Make long Urdu/English filenames, mixed-direction paths and empty lists readable. Layout direction must not alter stored strings. Disambiguate equal filenames from different folders.
- Preserve editing focus/selection when appropriate after dialogs; avoid accidental typing into the wrong document. Test keyboard-only navigation and visible focus. Do not call this screen-reader qualification without actual AT evidence.
- Verify the actual supported minimum window size, a normal window, maximized view and a roughly square window. Outline + Find + recovery/status must remain reachable without covering essential editing controls. Use scrolling/overflow intentionally rather than truncating actions.

## 6. One connected writing workflow

Make the following workflow executable and actually demonstrate it on one final build:

1. Create an Urdu article from a template; confirm the template remains unchanged.
2. Type mixed Urdu/English/numbers, format, add a nested list and insert/adjust an image.
3. Save to a disposable file; open a second document and change view/selection state.
4. Change the first file externally through an owned test fixture. Attempt Save in the editor, inspect the conflict and use Save a Copy without overwriting either version.
5. Create unfinished input/recovery work in an isolated test-owned session; wait for its durable-publication receipt, then terminate that owned process.
6. Relaunch, restore the workspace and inspect committed and unfinished recovery separately. Restore a draft as an unsaved document, export quarantine evidence if present, and Save As.
7. Close/reopen the saved result and independently compare exact supported content, formatting, paragraph/list settings, page settings and image resources. Confirm no old native callbacks or active composition resumed.
8. Exercise Later and selected discard with new arrivals; unrelated records and source documents must survive.

Use synthetic composition only where required for deterministic fault injection, and label it. Real UI restore controls are still mandatory when computer control is available. This workflow is additional to targeted tests, not a substitute for them.

## 7. Image workflow improvements

Build on the existing PNG/JPEG block-image implementation. Keep its atomic image semantics, resource hashing, worker decoding and bounds; no new image engine, remote downloads, SVG, floating text wrap, crop editor or generated art is required.

- Make Insert image, select, replace image, delete, dimensions and alt text discoverable. Reuse existing successful behavior rather than duplicating it. Add original-size/Fit to content controls with clear requested-versus-fitted size information.
- Provide aspect-preserving resizing through validated numeric controls and direct on-canvas resize handles. A completed drag is one undoable command; previews must not generate hundreds of history entries/checkpoints. Escape cancels the drag. Guard a captured target against stale content, composition or document replacement. Apply view/zoom transforms exactly once.
- Replace image preserves the selected image's structural placement and suitable user metadata while explicitly updating its resource/dimensions. Decide and document alt-text preservation rather than silently inventing a description. Undo restores the exact old resource and properties.
- Alt text persists through Save/Open, rich clipboard, templates and all recovery paths, and appears in the accessible object description where supported. Decorative/empty-description behavior must be explicit. Do not claim full AT qualification from an object property alone.
- Test inline text around the block, RTL/LTR lists, headings, empty paragraphs, cross-page positions and very tall/wide images. Keep text editable before/after; no accidental image-to-text flattening or lost landing paragraph.
- Keep source-file independence: images are embedded/owned resources, not fragile file paths. Test moving/removing the original image, copying between processes and duplicating a document/template. Reuse identical resource bytes where safe, retain bytes needed by Undo/redo/drafts, and enforce aggregate limits.
- Test invalid signatures, corrupt/truncated files, supported transparent PNG and JPEG, orientation metadata if supported, oversized encoded/decoded dimensions and unsupported formats. Disclose unsupported orientation/format behavior rather than silently displaying a wrong result. Decode and validation stay off the editing/UI thread, with bounded queue and cancellation/stale completion checks.

## Strict test routine — applies to every delivery

Maintain a coverage table with feature, exact build, operation, independent expected outcome, executed check and status: PASS / FAIL / NOT RUN / BLOCKED. Keep automated, synthetic-host, mounted-render and actual-desktop evidence separate. No global “everything works” conclusion.

### A. Focused correctness and regression checks

- Inspect the actual previous behavior; reproduce claimed existing bugs before fixing where feasible. New features need independently specified expected behavior, not artificial old-build failures.
- Exercise production commands/IO paths. Do not validate only a parallel ideal model or generate expected values using the implementation under test.
- For mutations compare exact raw units, attributes, structural identity according to clone/migration rules, marker metadata, resources, selection/dirty/history behavior and expected revisions. Check Undo/Redo and file/recovery round trips.
- Use Urdu Nastaliq and mixed scripts as primary fixtures. Include Arabic marks, Persian ZWNJ, Hebrew, decomposed Latin, Indic conjuncts and emoji/non-BMP/raw-surrogate preservation where relevant. Do not claim native keyboard coverage for pasted strings.
- Exercise cancellation, no-op, invalid input, stale basis, selection changes during dialogs, late background completion, repeated clicks, multiple windows and reentrancy. Failed commands must not partially mutate canonical state.
- Use deterministic seeds and preserve minimal counterexamples. Include meaningful negative controls for the important guarantees: stale Save must conflict; unresolved draft must not be evicted; old generation must not overwrite new; template creation must not alter its source; resize cancellation must not enter history.

### B. IO and interruption checks

- Use owned temporary files/recovery stores, explicit child PIDs and bounded timeouts. Never kill unrelated JVMs, fill the real disk, alter real permissions broadly or delete owner recovery data.
- Inject disk-full/write-denial/slow writes without stressing the user's storage. Terminate owned processes at a finite documented set of actual publication/lifecycle boundaries and restore in fresh JVMs.
- Test before/after resource publication, record publication, committed checkpoint, Apply receipt, discard and workspace-index publication. Enumerate exact expected permitted outcomes; do not demand unavailable data before publication or silently accept lost published data.
- Preserve named originals, prior good fallback, unconsumed records and new arrivals. Check malformed input and cleanup reachability so missing bases/resources are surfaced instead of silently ignored.

### C. Final build and regression discipline

- Record sources, dependencies, fonts, runtime, build manifest/hash and OS scale. Use one freshly identified final build for integrated verification and final native walkthroughs.
- Rerun the relevant existing kernel, V6 complex-cluster/marker, history, structure, clipboard, file, committed recovery/quarantine and Urdu suites, plus all new integrated checks. Verify frozen manifests unchanged.
- Fail runners on child nonzero exit, timeout, uncaught UI-thread exceptions, missing outputs or stale build identities. Never turn a watchdog timeout into PASS.
- If source changes after native testing, rebuild and repeat the affected native steps on the new identified build. Keep previous receipts as history rather than editing them into success.
- Measure bounded UI responsiveness for image decoding and recovery IO on representative documents. Record measurements and limits; do not claim large-document performance or all-platform qualification.

### D. Mandatory actual desktop verification

Read and use the supported computer-use skill/tools. Inspect actual windows before acting. Test only owned disposable documents; avoid unrelated applications/files. Stop interaction if the tool detects user activity, retain progress and continue noninteractive work where possible. Resume interactive steps only when permitted. Do not emulate native verification with terminal-driven internal commands.

Perform real clicks/keys/dialog actions for template creation, recent/Locate, Save/Save As/conflict, recovery inspect/restore/export/Later/discard, image insertion/replacement/alt text/numeric resize/direct drag/cancel, Undo/Redo and fresh reopen. Internal snapshot channels may read exact state and set up fault fixtures; they must not secretly perform the actions claimed as UI evidence.

Inspect screenshots yourself, including list markers, image handles/fitting, Urdu text, dialog validation, long filenames and minimum/normal/square/maximized windows. Cover continuous and paged views and all supported zoom levels for image transforms; use a declared high-risk matrix rather than claiming an unrun Cartesian product. Pair visual checks with independent exact payload assertions.

Use existing configured input only. No new language/font installations or repeated Japanese setup attempts in this batch. A real OS candidate lifecycle remains unqualified unless it actually runs. Full AT, expert Urdu typography, other DPI/platforms and PDF/printing remain separate gates.

If native control is unavailable or the user is active, report the precise unrun checks and finish independent work. Never substitute automated screenshots for actual interaction or claim final qualification without the required evidence.

## Deliverables and stopping condition

Create `docs/reports/v6-local-writing-workspace-batch.md` with per-delivery results, exact build/test commands, observed defects and fixes, before/after evidence where applicable, codec changes, responsiveness observations and remaining limitations. Update `docs/guides/WRITING.md`, `docs/guides/RECOVERY.md` and the newest handoff entry. Correct superseded current instructions in the guides so the writer does not have to reconcile conflicting version claims; preserve historical evidence separately.

Provide one launch command and a ten-minute user walkthrough. Include a useful disposable template/image sample that opens unsaved; do not replace historical samples. State what is implemented versus verified, exactly what can still be lost before recovery publication, and which native checks remain open.

Do not declare completion after one delivery or merely green unit tests. Complete all independent work in this assignment, fix reproducible failures in its scope, and report partial delivery explicitly if a genuine blocker remains. Close only owned test processes, restore temporary input state, stop computer control and leave the workspace usable.

Do not start tables, collaboration, semantic PDF export, public library extraction or a new architecture investigation in this batch. These remain product requirements for later assignments. Do not publish/push/tag or grant licensing terms. Stop after this integrated local-writing milestone and its honest handoff.
