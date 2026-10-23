# Android toolbar input-owner repair — 24 October 2026

This separate repair depends on image PR44, which depends on Outline PR43 and
recovery PR35. It is a native qualification finding, not a new feature batch.
No public core API, runtime dependency, document command, format version,
numbering policy, frozen file or approved desktop geometry changes.

## Native finding and cause

On APKb6354efa, actual provider Save As, Keep/close and Open succeeded, preserving
exact text and owned image resources. Native Find/Replace and Undo/Redo also
passed. A real held gesture selected Mixed at UTF-16[22,27]; native Select all
then selected[0,133]. Pressing the actual Copy toolbar button produced "Clipboard
selection changed; operation cancelled", while content, range and history stayed
unchanged. This failure is retained, not overridden by earlier direct-view tests.

MainActivity stored the mounted AndroidEditorView in an ordinary property.
The toolbar could retain its initial null or a detached previous document's host
because host replacement did not invalidate that Compose scope. Commands using
owners.current still targeted the correct model, which obscured the clipboard
control's separate stale view reference. The existing clipboard attachment and
basis fences correctly refused the stale action.

The production correction makes only this adapter reference observable Compose
state. New/Open and mounting another owner now rebind the shell's view-consuming
controls. It adds no editable buffer, history or competing document owner. Core
clipboard session/revision/identity/selection guards remain byte-identical.

## Discriminating mounted regression

The maintained `toolbar-owner` case presses the production New, Clipboard and Copy
controls through accessibility actions. It types distinct text into two owners,
selects and bolds the new owner's text, then requires a completed rich publication
containing exactly second + space + Urdu, all bold. Both owners' content/history
must be unchanged by Copy. It does not substitute a direct input-view Copy call
for the actual toolbar click.

In the same named headless API37 environment, the unchanged strict test fails
against the old production APKd531ffbe: the current owner never publishes. It
passes against repaired APKb216d079, including two clipboard items, current text,
decodable rich bytes and exact formatting. Raw receipts:
`native-20261023/toolbar-owner-headless-before/results.json` and
`native-20261023/toolbar-owner-headless-fixed/results.json`, under the primary
checkout's ignored `tests/local-evidence/repair-batch-20261023` root. APK/test
hashes, raw commands/output and the installed environment accompany each receipt.

The visible emulator has a separate observation: after the corrected owner emits
Rich clipboard published, its clipboard exposes one plain item, matching the
owned expected text, with rich MIME absent. The strict check remains FAIL in
`toolbar-owner-visible-diagnostic/results.json`; no assertion was relaxed.
The same repaired APK passes headless. This is evidence of a visible-host bridge
interaction, not permission to label native rich cross-app transfer PASS.

## Completed automated qualification

Final runtime APK SHA256:
`0149442040d4b55f183a01aa86762a07e1c4f39b36b6bad902d32745f30fd099`.
Final test APK SHA256:
`aa153ab69597959ad1e04f5e075c7602ab42b94c995121169adc45ebf4ac8a66`.
Named environment: owned `composedoc_slice_20261020`, emulator-5580, API37,
Google16KiB x86_64 image, font scale1. Source check/apiCheck and zero Detekt PASS.

| Evidence | Result and receipt under the primary repair-batch-20261023 root |
|---|---|
| Actual toolbar regression | Old app FAIL, repaired app PASS, unchanged strict assertions in the same headless environment; `native-20261023/toolbar-owner-headless-before/` and `toolbar-owner-headless-fixed/` |
| Independent packaged consumers | PASS JVM10, separate Android/peer binaries and metadata outside checkout; `toolbar-final-packaged/results.json` |
| Test synchronization rebuild | PASS fresh binary-only Android/test project after the one test-wait correction; unchanged library/JVM10/peer evidence reused with complete source/hash checks; `toolbar-test-sync-packaged/results.json` |
| Integrated adapter suite | PASS66 unique groups: retained65 plus actual-toolbar case; exact packaged fonts; `toolbar-qualified-66/results.json` |
| Owned process interruption | PASS completed checkpoint -> SIGKILL -> fresh restore and discard -> unchanged durable close -> SIGKILL -> restore; accepted/resources and separate unfinished evidence checked independently in `toolbar-qualified-66/results.json` |
| Cross-UID rich clipboard | PASS actual system transport after publisher death and cleanup; `toolbar-qualified-clipboard/results.json`; fixture actions are instrumented |

The first integrated66 attempt remains FAIL in `toolbar-final-66/results.json`.
Its image test used resources.isNotEmpty as readiness, but earlier groups retain
unused resources for history. It could read the unchanged document before the
asynchronous insertion completed. The test now explicitly starts without an image
and waits for the committed image block. One-Undo, exact text/attributes and stale
selection assertions remain unchanged. The fresh run passes all66. The rebuilt
test APK differs; the runtime APK is byte-identical to the earlier01494420 build.

These development-build inputs must be bound to the committed source before the
final clean gates. Native after-fix Copy/transport/rotation/font-scale checks remain
pending the shared controller's separate Antigravity/Stop qualification reservation.
Do not turn that temporary access dependency, the visible plain-item result, or
the passing mounted suite into a native PASS. All initial failures remain retained.

The final native check must open another owner and copy through its actual control,
verify rich/plain transport separately, and exercise rotation/keyboard/font-scale
where available. Genuine Urdu/Arabic/conversion IME, lower API and TalkBack speech
remain independent gates. End computer control before source work and coordinate
the shared controller with the other session.
