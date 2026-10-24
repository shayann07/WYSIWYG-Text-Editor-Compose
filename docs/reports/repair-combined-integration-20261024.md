# Combined repair integration candidate — 24 October 2026

## Delivered verification checkpoint

Status: PARTIAL qualification. The combined source is usable; the native language
walkthrough remains blocked. Android component7960266461f139b6c6d37f49e2d3dce308f37e29
is integrated at071dd84547d9d9c147a56e31d452e60ef581fa00. The documentation commit
containing this section is identified by Git HEAD. Its final clean six-gate receipt
is generated afterward at `tooling/local/final-verification.json`; reuse it only
when `status=PASS` and `commit` equals that exact HEAD. Earlier clean6813760 already
passed all six gates,20 desktop stages and four implementation mutants; that receipt
remains a distinct historical checkpoint, not proof of subsequent source changes.

The intervening085247e checkpoint also passed all six local gates, but hosted run
40045746565 failed its Windows Spotless check. Docs, quality, iOS and Android
(66 groups, both interruption witnesses and cross-UID clipboard) passed. The
Windows formatter required each sameBasis predicate on its own line. A fresh
local no-cache/rerun check passed the original packed form, so a cache-only cause
is not established. The separate7960266 correction changes only line breaks,
preserving every predicate/order. Fresh checks and a second formatter application
leave the canonical form unchanged; complete javap instruction listings are
identical, excluding line/debug metadata. No rule, suppression or threshold changed.
The failed hosted run and original local PASS remain retained. Exact new-commit
CI is required before any readiness decision; an old green local receipt is insufficient.

Current origin/main is679c9886224063ecd5aae1fe8cd5f4f7e140ebd4 after the coordinator's
verified squash merge of PR30. This coder has made no main push, approval, merge,
issue closure or contributor-branch rewrite. All12 issues remain open and assigned
only to the owner. Every original PR30–45 head remains unchanged in the fresh remote
snapshot. Original contributor commits remain in the integration ancestry.

### Runnable artifact and commands

The independently built application APK remains byte-identical:
`0149442040d4b55f183a01aa86762a07e1c4f39b36b6bad902d32745f30fd099`.
Stable copy in the primary checkout:
`tests/local-evidence/integration-20261024/delivery/ComposeDoc-android-writing-repair-01494420.apk`.
The locally executed final diagnostic test APK is0bfcb1bd9f04c740e80d93c744d88d01a316e8a25e2bdb7518fe8eba6e554dd5;
the independently built peer remains4dfb9c4d1b5cb035f6846d5b2fbd5e0f938da5c8dfc5579a691dde9ac1318f86.
The formatting follow-up was independently rebuilt with the same014 app and test
67f94dd02709c5aaa4e222859c5a13e8b78d4fcd75635fee5858e51e77e36c95.
That new test artifact is not byte-identical and was not rerun locally solely for
line wrapping; the exact-SHA hosted Android suite executes the corrected source.
Earlier API28/37 local receipts retain their explicitly identified test versions.

After starting the explicitly owned emulator and installing that APK, launch with:

```powershell
& D:/DevSetup/Android/Sdk/platform-tools/adb.exe -s emulator-5580 shell am start -n dev.shayxo.composedoc.androidsample/.MainActivity
```

Rebuild independently with the documented D-drive environment and a fresh output:

```powershell
python tooling/verification/consumer_check.py --android --android-peer --output-dir tests/local-evidence/android/new-independent-run
python tooling/verification/android_check.py --serial emulator-5580 --avd-name composedoc_slice_20261020 --consumer-receipt tests/local-evidence/android/new-independent-run/results.json --output-dir tests/local-evidence/android/new-adapter-run
python tooling/verification/final_gates.py
```

The last command requires a clean committed tree. Do not target a physical device,
wipe app data, delete recovery entries or replace previous receipt directories.

### Exact results and evidence levels

| Check | Disposition |
|---|---|
| Independent consumers | PASS: fresh external Gradle Android/test build, binaries/metadata only; JVM10/peer reused with unchanged binary hashes;170 compiled inputs bound |
| API28 fresh binary app014/test94488ae3 | PASS all66 groups and both SIGKILL/fresh-process recovery witnesses; `lower-28-packaged-final/results.json`, including `afterCloseInterruption` |
| API37 headless app014/test0bfcb1bd | PASS all66 groups and both interruption witnesses; `api37-headless-final/results.json` |
| Real OS cross-UID rich transport/publisher death | PASS separately on API28 and API37 headless; `lower-28-clipboard/` and `api37-headless-crossuid/`; fixture actions are injected |
| API37 visible app014/testce0ac6dd | FAIL65/66: strict rich clipboard item check; retained in `api37-cleanup-final/` |
| Current native workflow | BLOCKED: offscreen host window rejected actual Docs-selector coordinates; no product action or new language entry |
| Genuine Urdu/Arabic/conversion IME / current English composition-formatting | BLOCKED by that native interaction prerequisite; synthetic cases remain separate |
| TalkBack speech, expert language review, API24 | NOT RUN; no qualification inferred from mounted actions or API28/37 success |

All paths in this section refer to the primary ignored
`tests/local-evidence/integration-20261024/` root. The dated
[machine ledger](android-qualification-ledger-20261024.json) hashes the receipts and
states their artifact/environment scope. The original ledger is unchanged.

The headless clipboard diagnostic records two items, text/plain plus
application/x-composedoc-fragment, and a content URI owned by the application's
provider. It records publication/observation timestamps and the requesting process;
public ClipboardManager does not expose the current last-writer identity. Payload
text and foreign authorities are not logged. This controlled environment PASS does
not prove the cause of the retained visible failure. The earlier native bridgeOn /
bridgeOff experiment retains its own scope. No rich-format assertion was removed.

### Actual changes in this continuation

The application/engine binaries are unchanged. Test-only API28 repairs replace an
API30-only keyboard oracle with a guarded equivalent, make the AtomicFile failure
fixture fail on both implementations, and synchronize exact longpress publication
with a predeclared two-second deadline. All12 word fixtures published at502–514ms
in API28 while the public timeout reported400ms. A suppressed-delivery negative
reaches the deadline; wrong owners/content/history still fail. Callback cleanup is
unconditional and preserves primary assertion failures. Clipboard diagnostics are
bounded and retain the original exact transport/attribute checks. See
[the API28 report](android-api28-qualification-20261024.md) for the retained failing
before runs, independent geometry values and source-build versus packaged hashes.

The existing PR31 follow-up now also excludes generated replay_pid logs. Actual
Git checks failed before and pass after for root/nested diagnostics while maintained
reports remain visible. The isolated PR32 follow-up corrects the complete ICU NOTICE
path. No historical license bytes, frozen files, public core API, dependency, codec,
numbering behavior or approved desktop geometry changed.

Published repository maps are not byte-identical:325 timestamped SNAPSHOT paths and
metadata changed. The failed overstrict whole-map assertion is retained. All30
library binaries match by normalized coordinate/classifier and exact hash; app/peer
APK equality is checked separately. Fresh binary-only test builds preserve every
previous artifact in its original external directory.

### Native blocker and safe manual continuation

API37 starts and runs the exact014 APK. Its host window reopened at
[525,-787,473,1053], with captured source[516,-826,492,1101]. Actual physical Docs
selection was refused with ArgumentOutOfRange(point). Two supported placement
routes were stopped: Alt+Space opened the installed Gemini launcher, which received
no text and was closed; Win+Shift+Down did not move the emulator. No further window
or hotkey attempts were made. Both owner document hashes, selection and history
are identical before/after; no Gboard preferences changed. The native controller
ended normally and shut down at23:58:18UTC. Screenshots were actually inspected and
copied to `native/screen-01-initial-owned-editor.jpg` and
`native/screen-02-before-refused-point.jpg`.

Manual ten-minute continuation, explicitly NOT RUN here:

1. Position/resize the disposable emulator fully on the captured monitor. Open
   File/New; keep existing unresolved recovery records untouched.
2. Use real installed Gboard keys for English, then offline Urdu/Arabic layouts
   if available. Capture preedit/commit separately; stop at downloads/security.
3. Select backward, format during live composition, inspect settlement and exact
   attributed Undo/Redo. Distinguish genuine conversion from direct commits.
4. Exercise the already implemented headings/opposite-direction lists, image
   picker/validation/resize/replace/history, provider Save/Open and rich/plain
   exchange. Record host clipboard-sharing state and its observed limitation.
5. Rotate, increase font scale, expose the horizontally scrollable controls with
   the keyboard shown, then restore original preferences. Restore only a named
   newly created recovery generation after an owned interruption.
6. For TalkBack, the owner handles permissions and provides actual speech evidence;
   focus/actions alone do not qualify it. Export failed recovery evidence rather
   than discarding it. Close only owned test windows.

This continuation preserves the earlier build-specific native image/provider/
Outline/recovery findings; it does not turn them into a blanket current native PASS.
Desktop Narrator, physical/advanced printing, genuine desktop composing input,
semantic PDF, expert typography and other platforms/DPI remain separately open.

### Issue/PR readiness and owner actions

| Issue | Primary PR / exact tested stream | Current disposition and remaining action |
|---|---|---|
|15|36 /1bbb110|Closure37modules/45files and negative integrity tests covered; human governance/current-base review pending |
|17|37 /2dc8aba|Fresh25+16 comparison covered in combined Node-pinned tree; original head's docs failure remains; update/retest its base before landing |
|19|30 /a5d05ed|Coordinator merged normally to main679c988; do not infer closure of the still-open issue |
|20|31 /e7aaaec + owner follow-up8e9d8be|Tool discovery/failures and crash/replay exclusion tested; contributor follow-up landing permission/review pending |
|21|32 /cb9d535 + owner follow-up38fb069|Immutable upstream notices checked; valid NOTICE-path finding fixed in candidate only; original PR/thread remains pending |
|22|38 /c331a9c|Consolidated G1 remains PROPOSED; owner normative acceptance and governance pending |
|23|39 /b15cbef|I15/I17 mappings remain PROPOSED; owner acceptance and dependent base pending |
|24|40 /13c1d91|Measurable proposed gates/schema covered; thresholds/reference hardware unaccepted; no product gates qualified by creating a matrix |
|25|33 /8a7b2c9|Existing documentation used; retained Android failure analysis/artifacts in prior disposition; original Android check still failed |
|26|34 /e1b3768 + owner follow-upfcd959b|Navigation/broken-link negatives covered; contributor adoption/current-base review pending |
|28|41 /bcd735c|Archive classifications/index covered; original Android failure remains separate; no rejected probes restarted |
|29|42 /601aef0 plus this dated ledger|Evidence-backed current qualifications; tracker remains open and original hosted failure remains recorded |

Recovery35/e60f9ee, Outline43/0dbcc57, picker44/ae91306 and toolbar45/c0202d3
remain separate original PRs with preserved author text. The combined APK contains
their repairs. New API28 test-only branch7960266 is based on45; its landing route or
owner-written PR metadata remains a coordinator decision. No competing contributor
PR was created. Contributor branches/descriptions are untouched. Missing truthful
human attestations, self-approval restrictions, required current-base checks and
unresolved review threads remain real merge gates. Proposed specifications remain
PROPOSED. The coordinator alone executes any authorized merge after review.

## Historical construction checkpoint

Status: IN PROGRESS. This is an isolated verification branch, not a merged release
or a declaration that each constituent PR is ready. Historical receipts and frozen
specifications remain unchanged. The coordinator owns approvals and merge execution.

## Candidate and provenance

Base: `e30c984f97c992bbbdb95a7eeaa932a0af7d5c22`. Constructed integration:
`752e03bc06f4ba18b4593d662804d8bbe88a69c9`, branch
`codex/repair-integration-20261024`. Local ancestry-preserving merges retain all
contributor commits. They do not authorize merge commits on protected main.

| Input | Exact original head | Additional dependency |
|---|---|---|
| PR30 | a5d05ed7bb6321737796fce8d9208a195d589c83 | Node24.20.0 pin |
| PR31 | e7aaaec4c5619c7761caa5d28a4de7fd58b3221b | Tested follow-up d732c22b70254e9407a7e41ace4a8eaa36dfff4a; adoption route pending |
| PR32 | cb9d535ebe9874db64cc04db3bb93a12b17e48b5 | Upstream notices |
| PR33 | 8a7b2c953b0ebe9b8d301c3121302b028e5aa9f0 | Retained hosted failures remain separate |
| PR34 | e1b3768a0776623b1141d9e194a32334b07c9b38 | Tested follow-up fcd959b89ab578d9793d80e75657639ace0ec164; adoption route pending |
| PR35 | e60f9ee6ed4eba1ae8ce4f10c8d01b9835eb495f | Recovery repair |
| PR36 | 1bbb110293a6eea826ccda4a367b9813e38cb76b | Evidence closure |
| PR37 | 2dc8aba5a84c59039c8e657471699a0bfbf3bd8d | PR36 and runtime pin |
| PR38 | c331a9c8e09deafefb4f618cab8fa10a51766e59 | PROPOSED G1 clarification |
| PR39 | b15cbef701b32aaa03fdc43287ca398c8e02af03 | PR38; PROPOSED invariant mapping |
| PR40 | 13c1d9111b37dbdd7a01f5d2dc36ad92d49bc072 | PR39; PROPOSED measurements |
| PR41 | bcd735c5d1a3d047ed3f8ef327493b5a7c8f66b6 | Archive index |
| PR42 | 601aef0c5825d63d37c722bcd0599a24fef64b3c | Qualification ledger |
| PR43 | 0dbcc57dcc90f06c7a0da7e93d4ae511bb554ea0 | PR35 |
| PR44 | ae91306c3dec6c0f320916e259efa0bf45286346 | PR43 |
| PR45 | c0202d3f676c0fafee8e12410ed81612e5e978bb | PR44 |

Only workflow/handoff conflicts required resolution. Full documentation-test
discovery includes navigation, proposal and Android-ledger tests. Strict inherited
reproduction covers 25 inherited groups and 16 V6 witnesses instead of duplicating
the standalone V6 step. Poppler tests remain explicit. The six final-gate receipt
shape is retained; documentation-contract tests are nested within its verification
contract gate. No app/engine conflict required a semantic resolution.

Machine-local provenance and raw conflicts:
`tests/local-evidence/integration-20261024/integration-sequence.json` and its
`merge-*` directories in the primary checkout. The inventory captures full original
PR diffs, comments, reviews, checks and heads. A combined PASS never substitutes for
required checks or approval on an individual PR's actual current-base head.

## Verification checklist

| Check | Result at this checkpoint |
|---|---|
| Protected files | PASS: all81 hashes |
| Imported-file integrity | PASS:37 imported modules/45 covered files |
| Documentation regressions | PASS:14 tests, including integrity/navigation/proposals/ledger |
| Poppler discovery failures | PASS:4 tests |
| Proposed-record validation | PASS:3 validators; no normative acceptance implied |
| Fresh inherited reproduction | PASS:25 inherited +16 V6; six negative comparator tests PASS |
| Independent binary consumers | PASS: JVM10, Android/test/peer packaged outside checkout;168 inputs and all APK hashes match c020 |
| Android66 + two owned-process interruption witnesses | NOT RUN on combined candidate |
| Cross-UID clipboard including publisher death | NOT RUN on combined candidate |
| Clean exact-commit six final gates | NOT RUN on combined candidate |
| Same-build native continuation | NOT RUN on combined candidate |

## Hosted tap failure boundary

PR45 run40025253551/job120659707919 at bdf1e35 completed65/66 groups. Its
independent Latin-prefix tap expected selection(2,2); the original receipt omitted
the actual failing range, contemporaneous origin and delivery times. Therefore it
does not establish whether delivery timing, reflow or a callback affected the range.
These remain hypotheses, not root-cause findings. Later c020 local/hosted passes do
not explain the original failure. Existing failure diagnostics/focused entry retain
the exact expected range and font oracle. No speculative input correction or
assertion relaxation is included in this integration.

## Native and merge boundaries

Previous APK01494420 native receipts retain their separately identified build and
operation scope. Emulator host clipboard sharingOn strips rich metadata; sharingOff
allows the recorded native rich transfer, and the original setting was restored.
No broad multilingual/IME/AT qualification follows from those observations.

An already installed API28 image and emulator legacy binary are being investigated
without downloads or minSdk changes. The first direct binary probe exposed missing
DLL search paths, then an unsupported `-no-netsim` option. Both raw failures are
retained. A fresh isolated AVD probe with documented legacy options boots API28; mounted suite is running;
startup alone will not qualify editing. Existing API37 evidence is separate.

Offline Gboard layout configuration is authorized only on the identified owned AVD,
with original configuration restored. Downloads, account/security prompts and
TalkBack permission acceptance remain owner boundaries. Computer control stays off
for repository work and must be coordinated with the other desktop session.

Contributor branches/descriptions remain untouched. Original PR31/34 residual fixes
are present only in this disposable candidate until the coordinator selects their
landing route. Proposed amendments remain PROPOSED. Missing human attestations,
self-approval restrictions, current-base required checks and unresolved review
threads must not be bypassed. No issue closure or package publication is implied.
