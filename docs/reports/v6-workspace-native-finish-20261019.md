# Workspace qualification continuation — native tool blocked; two UI races repaired

19 October 2026, Asia/Karachi. This continues the existing workspace batch; no new feature, UI redesign or frozen-plan change. Computer control is **OFF**. Only owned diagnostic/test processes were closed. Original documents, evidence and unresolved recovery records were preserved.

## Current result and launch

The usable build includes two reproduced asynchronous row-selection repairs. Relevant integration and independent mounted handler regressions pass. **Outstanding native qualification is still BLOCKED / NOT RUN.** Two genuinely different supported dialog-targeting approaches failed in the tool before selection input reached the application. No further computer attempts were made, as requested. The new handler fixes have not been natively exercised; older native successes must not be relabeled as results of this build.

```powershell
cd D:\Work\WYSIWYG-Text-Editor-Compose
.\desktop-prototype\write-urdu.cmd --workspace-sample
```

Exact artifact: `desktop-prototype/local-evidence/batch-20261019-202031-28308/build`. Manifest SHA256 **`77595dbfcbae8134a73ba0a530d1538734001117b1e6f1284b06b2bdb3e62a41`**. Integrated receipt: `desktop-prototype/local-evidence/batch-20261019-202031-28308/results.json`. Only three build inputs differ from the preserved192824 build: `UnfinishedRecoveryUi.kt`, `WorkspaceUi.kt`, and `WorkspaceHostChecks.kt`. No model/codec/resource/font/dependency change. Sample and compatibility policies in [WRITING](../guides/WRITING.md) and [RECOVERY](../guides/RECOVERY.md) remain unchanged.

Final results: **20/20 integrated stages PASS**, including the fresh build,20 workspace core groups,9 mounted host groups and25 actual owned child interruptions (13 workspace +9 committed +3 structured). Both supplemental independent handler witnesses PASS on this exact manifest. Source/artifact identity revalidated after the run. Documentation validation:48 historical hashes,33 sealed V6 hashes and no broken local links. Broad previous research/native campaigns were not repeated; the bounded relevant workspace lane was rerun because the two production handlers changed. Runtime/fonts/OS are unchanged from the original report. No new native/DPI/IME/platform claim is made.

## Baseline verification and controlled native diagnosis

The requested192824 manifest SHA256 `c3a33b8e975974ae189c8845ec09c7bbd6b80ba8a0e6301d0a71a24bd390f468` matched; `validate_build` verified current inputs/artifacts **before source edits**, and the existing193008 receipt had19/19 stages PASS. It was reused for diagnosis rather than rebuilt. Later source changes intentionally require the new build above. Existing19-stage/native evidence is dated in [the original batch report](v6-local-writing-workspace-batch.md).

Diagnostic evidence: `desktop-prototype/local-evidence/workspace-native-dialog-diagnosis-20261019/`.

1. Started one blank owned editor, PID31468, isolated recovery directory, exact192824 classes. A pre-existing unrelated Java process was left alone.
2. Current computer-use skill/API discovery exposed `list_apps`, `list_windows`, accessibility elements, screenshot-backed input and `perform_secondary_action`. The ordinary Bold button delivered mouse press/release/click to a JButton and visibly became “Bold on.” Canonical content/history remained empty.
3. Ctrl+N opened the actual modal `Create from template — independent unsaved copy`. The read-only observer recorded the JDialog owned by the editor, active/focused, with focus on `JList/template-list`, selected index0/count3. Actual AWT editor bounds1034×830 at(251,17); dialog393×291 at(571,286). These are diagnostic dimensions, **not** a minimum/square/maximized layout pass. Screenshot crops have different extents and are not substituted for window measurements.
4. The tool's fresh accessibility tree reported nested **element7** as the focused dialog with “Secondary Actions: Raise,” but `list_windows` exposed only its parent editor, not an independent dialog Window identity. No guessed native handle was used.
5. **Approach1:** `perform_secondary_action` on observed element7, `Raise`. Exact failure: **`element 7 has no cached secondary actions for java.exe`**.
6. Refreshed the accessibility observation. **Approach2:** `click` on observed dialog element7, intended to establish direct dialog focus for keyboard selection. Exact failure: **`element 7 is not available in cached app state for java.exe`**. Since the click itself failed, no Down/key input was sent to the disabled parent as a substitute.
7. Stopped at two approaches. The observer recorded **no mouse/key input after the dialog gained focus**; selected index stayed0. UI snapshots still responded. No UI exception was logged. Reset the Node computer runtime and closed only owned PID31468, exit0, through fixture cleanup. This cleanup is not an ordinary close-button test.

Disposition: **tool cannot resolve its own observed dialog element into an actionable cached target**. Application modality/focus was working at this observation. A native handler failure was not reproduced because the selection input never arrived. This does not prove all application dialog behavior correct. There was no redesign to accommodate automation.

Evidence files: `diagnosis.json`, `supported-dialog-target.json`, `dialog-observer-31468.jsonl`, `dialog-target-0.png`, `dialog-target-1.png`, `protocol.jsonl`, and observer compile/source-hash receipts. The observer is `qualification/WorkspaceDialogObserver.java`: it only reads owned AWT window/focus/list state and delivered events; it never focuses, clicks, selects or mutates content. `qualification/workspace_native_bridge.py --observe-dialogs` runs it around the unchanged production entry point.

![Actual focused modal captured during the two-attempt diagnosis](../../tests/local-evidence/desktop/workspace-native-dialog-diagnosis-20261019/dialog-target-0.png)

## Actual product defects found independently

These are **synthetic mounted tests of real handlers**, separate from the native blocker. They use copied/generated owned data, real confirmation/action listeners, SwingWorker dispatch and actual disk storage. A barrier in the worker pool makes the race deterministic; no implementation formula or source-string check is used as the oracle.

| Defect | Reproduction and independent expectation | Before | Repair / after |
|---|---|---|---|
| Recovery Discard removes the wrong visible row | Capture recordA, confirm Discard, hold worker dispatch, selectB, release worker. Store and list must both containB; onlyA may disappear |192824 FAIL: persistedB, visibleA. Data retained, but unrelatedB disappeared from the chooser until refresh | Capture the immutable selected entry before confirmation; remove that entry when the captured record resolves.202031 PASS: visibleB=persistedB |
| Remove Recent removes the wrong visible row | Start removal ofA, hold worker, selectB, release. Both persisted and visible references must retainB and removeA |192824 FAIL: UI retainsA/removesB | Remove by the captured reference ID, never current selection at completion.202031 PASS |

Changed production files: `UnfinishedRecoveryUi.kt` and `WorkspaceUi.kt`; integrated regressions in `WorkspaceHostChecks.kt`. This is a display/reference consistency repair, not a claim that document data was deleted or corrupt.

Evidence under `desktop-prototype/local-evidence/`:

- `workspace-discard-selection-before-20261019/owned/results.json` and `run.log`: actual old-handler FAIL.
- `workspace-recent-selection-before3-20261019/owned/results.json` and `run.log`: actual old-handler FAIL.
- `workspace-discard-selection-after-20261019/results.json`, `workspace-recent-selection-after-20261019/results.json`: independent202031 mounted checks PASS, exact manifest and witness source hashes recorded.
- `batch-20261019-201700-35824`: intermediate first repair integrated PASS. It is superseded by202031, not the final build.
- The first two recent-witness attempts are retained fixture failures: one incorrectly decoded an image document on the EDT and was correctly rejected; the next supplied invalid processId0 and timed out waiting for two rows. Those helpers were corrected before the discriminating before3 run. They are **not** product-failure evidence or passed tests.

The new integrated groups are “discard completion removes its captured row after selection changes during IO” and “recent remove completion keeps a different row selected during IO.” Both verify observable lists **and** independent persisted IDs. The existing generation/new-arrival, Cancel/Later, storage failures, content/resource/history and geometry evidence remains separately labeled.

## Per-check disposition

Every native cell identifies its evidence scope/build. This continuation diagnosed the preserved192824 build; the final202031 handler repairs have no native pass. All requested outstanding native checks remain open because targeting failed before a destructive or selection action could be qualified.

| Check | Current automated/source evidence | Current native |
|---|---|---|
| Ordinary button / actual delivered event | Observer confirms event delivery | **PASS on preserved192824 diagnostic build** |
| Modal ownership/focus | Observer confirms JDialog→JList focus | **PASS observation on192824**; selection **BLOCKED** |
| A inspect/export, captured-generation discard, new arrival, Cancel/Later, fresh-process persisted result | Existing storage/lifecycle/kill tests PASS; added captured-row regression PASS on202031 | **BLOCKED**; previous inspect/export/Later/core recovery successes remain dated192824 evidence |
| B Locate/mismatch, Remove/Clear, source/recovery preservation | Existing workspace tests PASS; new actual Remove handler race PASS on202031 | **BLOCKED** |
| C user-template removal/Cancel, created-doc/resource survival | Existing clone/source/resource/template-removal checks PASS on202031 | **BLOCKED** |
| D dirty Reload/protected local recovery, clean/dirty/unfinished ordinary close, Cancel/save failure | Existing relevant kernel/writing/quarantine/recovery/workspace integration PASS on202031; source reviewed | **BLOCKED / NOT RUN**; fixture shutdown is not counted |
| E continuous/paged ×50/75/100 resize, preview/release/Escape/history/fitted dimensions/stale target | Six mounted actual-handler rows and existing exact history/resource checks PASS on202031 | **NOT RUN due stopped native control**; earlier100% paged drag is192824 evidence |
| F minimum940×400, normal, roughly square and maximized with Outline+Find/status | Mounted actual window rows940×400,1020×740,940×882,1536×864 PASS on202031 | **NOT RUN due stopped native control**.940×882 is the actual measured near-square mounted row, not940×940 or native maximization |
| New two row fixes through OS clicks | Real-handler mounted regressions PASS | **BLOCKED**, not executed on202031 |

No FAIL is hidden as PASS. The two product regressions fail before and pass after. Missing native checks are not product passes. Genuine composing IME, other DPI/platforms, full AT, expert typography and PDF/print remain separate unqualified gates.

## Manual checklist for the remaining native gates

Fresh disposable assets: `desktop-prototype/local-evidence/workspace-manual-checks-20261019/` with `fixtures.json`, `baseline.astra`, `changed-external.astra`, `source.png`, and **only** `read-only-target.astra` deliberately given the Windows read-only file attribute for Save-failure testing. Source files and system settings were untouched. Do not use real documents. Existing unresolved records in the earlier store were preserved and need not be deleted to run this checklist.

Use the final build in a fresh isolated run; actual actions below are yours, not pre-recorded native passes:

```powershell
python desktop-prototype/qualification/workspace_native_bridge.py --build-dir desktop-prototype/local-evidence/batch-20261019-202031-28308/build --output-dir desktop-prototype/local-evidence/workspace-manual-checks-20261019/run
```

The bridge accepts `snapshot|label`, `select N|label`, `external-change|label`, `seed-synthetic-unfinished|label`, `seed-new-arrival|label`, `kill-owned`, `restart`, `shutdown`. These inspect state or set up owned faults only. Use real mouse/keyboard/dialog controls for the operations being tested. Choose the editor index from a fresh snapshot before fault injection.

1. **Recovery:** create/save a disposable article; seed synthetic unfinished input and wait for published= generation with no pending/running failure. Actual kill/restart; Inspect/export one record. Cancel Discard and use Later, reopen and confirm both remain. To test new arrivals, open the live owner's ordinary close confirmation, seed a new arrival on that same selected owner while the confirmation is pending, then continue the captured discard; the new arrival must keep the window/work available. Verify exact outcome after a fresh process. Discard only the identified disposable record; do not use a broad cleanup action on older stores.
2. **Recents:** save two disposable documents, move one within the test folder, then Locate it. For a changed file use the external fixture and confirm mismatch disclosure and safe starting selection. Remove one reference, then Clear; verify both files and recovery bytes still exist. Change list selection while an operation is pending if practical; only the captured reference may disappear.
3. **Templates:** save a disposable user template containing an image and create a copy. Cancel removal once; confirm it remains. Remove that template, then save/reopen the previously created document and confirm its text/image/alt survive.
4. **Reload/close:** edit a named document, run `external-change`, attempt Save and choose Reload. Confirm local content is protected and external content opens separately. Test actual close button with clean, dirty and unfinished states; Cancel/Keep Editing must preserve them. Attempt close→Save to the owned read-only target, explicitly accepting the test overwrite prompt; an IO error must keep the editor/work. If that filesystem permits the replacement, it did not demonstrate a failure—record NOT RUN and use a bounded injected-denial fixture rather than changing real permissions.
5. **Images:** in each continuous/paged ×50/75/100 row select the image; drag/release, Undo/Redo, then start another drag and press Escape. Compare snapshots: one Undo entry on completed release, none for canceled preview; exact requested width/resource/alt restored. Check Fit versus requested width. Make a captured target stale through a supported intervening action and verify refusal without mutation.
6. **Windows:** inspect actual940×400, normal, roughly-square and maximized sizes with Outline+Find and recovery/status reachable. Read actual bounds (not screenshot crop size); verify image handles, control scrolling, focus and no clipped required actions. Save screenshots and snapshot labels. If the OS prevents an exact dimension, record that instead of calling a nearby size940×400.

The full writer-facing walkthrough remains in [WRITING](../guides/WRITING.md). This checklist is the outstanding qualification work, not a new feature assignment.

## Verification commands and stopping point

```powershell
python desktop-prototype/verify.py --workspace-milestone
python desktop-prototype/qualification/unfinished_selection_witness.py --build-dir desktop-prototype/local-evidence/batch-20261019-202031-28308/build --records desktop-prototype/local-evidence/workspace-native-header-fixed-20261019/recovery/unfinished-records --output-dir D:\YourUnusedFolder\discard-regression
python desktop-prototype/qualification/unfinished_selection_witness.py --mode recent --build-dir desktop-prototype/local-evidence/batch-20261019-202031-28308/build --records desktop-prototype/samples/disposable-workspace-v8.astra --output-dir D:\YourUnusedFolder\recent-regression
python planning/validate_docs.py
```

Stop computer control after each bounded interactive session. Do not rerun the two failed tool approaches without a changed targeting capability. Do not label the entire workspace batch natively complete, combine old/native and new/mounted results into one PASS, discard unresolved work, or begin another feature batch. The reusable command/model/codec contracts and frozen specifications are unchanged by these two shell repairs.
