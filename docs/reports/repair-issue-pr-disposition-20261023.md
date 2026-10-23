# Android repair and twelve-issue disposition — 23 October 2026

Main remains e30c984f97c992bbbdb95a7eeaa932a0af7d5c22, clean and equal to
origin/main. Work is isolated under `D:\Work\ComposeDoc-repairs`. No main push,
merge, issue closure, contributor remote change or public review message occurred.
All twelve open issues were refreshed and remain solely assigned to @shayann07.
This is an evidence ledger for the owner's tracker update, not a generated issue
body or contributor PR description. No human attestation is fabricated.

## Recovery delivery

Branch `codex/android-recovery-close`, commit 9d8f60b, reproduces both original
defects before fixing them with actual mounted Android shell/recovery-store tests.
The old equality/empty-queue close shortcut and single overwritten callback are
replaced by bounded owner/revision/generation-specific obligations with exactly one
durable, failed or cancelled outcome. Keep/close publishes a fresh generation and
verifies accepted content and separate unfinished evidence. Discard is fenced before
queued deletion; store ordering/tombstones reject stale publication. Failures keep
writing open with a reason; teardown cancels each request explicitly.

Final separately packaged APK SHA256:
`fcc93ac8736b929474f0e4521415965659aeb58cdfb5a91dc665813629fc1747`.
Stable local copy:
`tests/local-evidence/repair-batch-20261023/delivery/ComposeDoc-android-recovery-9d8f60b.apk`.

| Evidence level | Result |
|---|---|
| Mounted before-fix production shell/store | FAIL both requested witnesses, retained |
| Mounted repaired adapter | PASS63: original54 + nine close/race/failure groups; exact fonts |
| Actual owned SIGKILL/restart | PASS last-completed-checkpoint loss window; PASS discard/unchanged successful close -> kill -> fresh restore |
| Independent binary consumers | PASS10 public-API runtime groups, Android and peer binary-only builds outside checkout |
| Real cross-UID clipboard transport | PASS after publisher process death and cleanup; setup/paste actions instrumented |
| Python Android runner contracts | PASS20, including rejected false close-success receipts |
| Genuine native connected walkthrough | BLOCKED: supported capture reports window outside captured monitor twice; no document inputs sent |
| Lower API | BLOCKED legacy API28 image/startup pairing; minSdk24 unchanged |
| Urdu/Arabic and conversion IME; TalkBack speech | NOT RUN on repaired build; model/English historical evidence is not a substitute |

The measured JSON ledger alongside this report records receipt hashes, scope and
remaining work. A fresh clone lacks machine-local evidence; this is disclosed rather
than interpreting missing receipts as PASS. Full clean final-gate results are recorded
separately in the exact repair commit's local receipt; inspect it before pushing.

## All twelve issues and primary work

| Issue | Finding and covered work | Tests / branch or existing PR | Remaining scope and owner action | Disposition |
|---|---|---|---|---|
| 15 | Imported run17/run19/Gemini closure lacked transitive coverage; additive45-file record,37 imported modules, existing-ledger provenance | six negative tests; docs81; `codex/issue-15-evidence-integrity` 1bbb110 | Human PR text/review; new hash does not prove historical integrity | Implemented/tested; PR metadata pending |
| 17 | Only16 V6 witnesses routinely ran; all25 inherited now execute and compare complete deterministic records | fresh25+16 PASS; six negative comparison tests; `codex/issue-17-inherited-reproduction` 2dc8aba | Depends15, runtime pin from30; hosted checks after human PR text | Implemented/tested; dependency pending |
| 19 | Node runtime drift versus recorded evidence | existingPR30; npm/lock consistency + real drift-warning failure negative PASS | Human review; not CodeRabbit draft-skip approval | Primary existingPR retained |
| 20 | Machine-local crash log and Poppler executable path | existingPR31; follow-up `codex/pr31-tool-discovery-followup` d732c22, four tests PASS | Contributor/owner may apply follow-up to31; no competingPR or remote rewrite; Git history deliberately retained | Residual repaired locally; existingPR not fully ready |
| 21 | Missing archived upstream notices | existingPR32; exact immutable-upstream license and version-header comparisons PASS | Owner/legal review; header correspondence does not attest every archived byte's origin | Primary existingPR retained |
| 22 | G1 requirement split across adjacent paragraphs | `codex/issue-22-g1-proposal` c331a9c; proposal validator/three tests PASS | Explicit owner acceptance of proposed additive record, human PR metadata | PROPOSED, not authoritative |
| 23 | I15/I17 lacked explicit gate references | `codex/issue-23-invariant-map` b15cbef; four tests PASS; I15->G1/G8, I17->G4/G6 | Depends22; explicit owner acceptance; full invariant coverage remains unqualified | PROPOSED, not authoritative |
| 24 | Environments/traces/measurements and capability matrix absent | `codex/issue-24-capabilities` 13c1d91; five schema/reference negatives PASS; CI validation wired | Depends23; accept thresholds/reference hardware, then execute gates; G0-G8 NOT RUN | PROPOSED, scoped representative outcomes only |
| 25 | Gemini amendment/report duplicate | existingPR33; exact96677-byte/hash match; failed hosted attempt diagnosed below | Required Android check still failed; human review and bounded CI retry needed | Covered documentation, CI BLOCKED |
| 26 | Stale active navigation / insufficient link coverage | existingPR34; archive+active negative PASS; suite-index gap reproduced; follow-up `codex/pr34-navigation-followup` fcd959b PASS | Apply follow-up into34 only with owner/contributor agreement; no pinned link text changes | Residual repaired locally; existingPR not fully ready |
| 28 | Archived recipes appear clean-clone runnable | `codex/issue-28-archive-index` bcd735c; external classified index, docs81 PASS | No rejected experiments revived; dependencies/platform constraints remain explicitly NOT RUN | Navigation correction implemented/tested |
| 29 | Tracker trails actual rich-writing/recovery and native limitations | This branch `codex/issue-29-status-review`; measured JSON ledger and report | Owner writes tracker/public update; no full roadmap completion or issue closure | Evidence-backed update ready for review |

Each new issue branch has one primary purpose. Issue17 starts from15; issue23 starts
from22; issue24 starts from23. Other new issue branches start at e30c984. Recovery is
one separate interacting-defect PR. The two contributor follow-up branches start at
their exact contributor head commits and preserve those commits/authorship. They
are proposed patches for the existing PRs, not replacement competing PRs.

## Complete contributor-PR review

Full issues, PR bodies/diffs, commits, comments, inline comments, head SHAs and
checks were read and refreshed. All five remain drafts, with no submitted human
review. CodeRabbit says review skipped because draft; that is not approval.
Historical governance failures are retained; later successful governance checks
do not erase them. Latest substantive checks are green except33's Android check.

| PR / exact tested head | Covered acceptance | Residual findings / readiness |
|---|---|---|
| 30 / a5d05ed7bb6321737796fce8d9208a195d589c83 | Node24.20.0 pin, CI references, both package/lock engine+dependency consistency, clean install, frozen hashes/V6 PASS. Disposable runtime drift warns; real witness failure still exits nonzero. | No concrete code defect found. Draft/human review remains; no automatic approval. Coordinate17's sealed-environment policy with this pin. |
| 31 / e7aaaec4c5619c7761caa5d28a4de7fd58b3221b | Ignores future hs_err files and removes current unsealed log; supports explicit environment/PATH lookup; frozen hashes/V6 PASS. | Explicit invalid path reproduced raw FileNotFoundError; nonzero version probe ignored. Follow-up adds actionable missing/launch/timeout/version errors, selected-tool tests and dependency-free help. Full PDF qualification is outside this fix. History is unchanged. |
| 32 / cb9d535ebe9874db64cc04db3bb93a12b17e48b5 | Added licenses byte-exact against upstream immutable Skia/ICU revisions; archived milestone150 and ICU74.2 version headers also byte-exact. Frozen hashes/V6 PASS. | No concrete notice mismatch found. Full upstream snapshot provenance is not inferred from two headers. Human review remains. |
| 33 / 8a7b2c953b0ebe9b8d301c3121302b028e5aa9f0 | External index describes existing duplicate; both files SHA256 0d5102326261b14f20253a816368806d7d1fa0e31dc228e70015b3dbc6add9c4. History follows duplicate back to f4ff82e; no alternate file found. Frozen hashes/V6 PASS. | Hosted Android failure investigated, not dismissed. Required failed check blocks merge readiness; no rerun substituted for its raw evidence. |
| 34 / e1b3768a0776623b1141d9e194a32334b07c9b38 | Actual validator rejects broken active and unsealed archive links, preserves81 hashes, dated organization chronology and pinned links. V6 PASS. | Maintained suite-index broken link escaped validator (reproduced). Follow-up adds explicit maintained suite/archive/review indexes and actual-validator negative regression. Owner/contributor review remains. |

Upstream immutable provenance for32:
Skia `f9b0d17912fad78861ad343797db3f33084c19cc`, license SHA256
`5f787c1dee3c56547f09ccc2906ab5f5293c4d8dd6c8654e573216c38e908dbd`;
ICU `2d029329c82c7792b985024b2bdab5fc7278fbc8`, license SHA256
`17510cf7a58b4879b887ec05a45d72cf1b73544dd9ec7e72f20110ed104229ee`.
Sources are the respective upstream GitHub LICENSE and version-header files at
those commits. Download/hash/diff receipts remain under the evidence root.

### PR33 failed run: exact evidence

Run39680895653, job119512879664, attempt3, head8a7b2c9 started
2026-10-21T22:05:19Z and ended22:10:37Z. Downloaded retained Android artifact
12385637422 (name ends0a76e006...). Instrumentation reports Process crashed and
has no result file. At22:10:19.191 logcat explicitly records lowmemorykiller
terminating dev.shayxo.composedoc.androidsample PID3907, oom_score_adj700,
for a min-watermark breach. ActivityManager records that same process death;
several other system apps were killed too. This is evidence of memory pressure
for this attempt, not a general explanation for every absent result. Other failed
attempts are not silently classified. Raw artifact and job metadata are retained.

## Reproduce and connected walkthrough

See the recovery branch's integration guide/report for build/install and all
Android/consumer commands. Launch the identified repaired APK with:

```powershell
D:\DevSetup\Android\Sdk\platform-tools\adb.exe -s emulator-5580 shell am start -n dev.shayxo.composedoc.androidsample/.MainActivity
```

The existing ten-minute checklist in `android-rich-writing-20261021.md` remains the
native checklist: keyboard/backward selection/composition formatting/history; nested
RTL/LTR lists/headings; image picker/invalid width/replace/cancel; rich/plain clipboard;
actual provider Open/Save/SaveAs; recovery restore/discard/close; rotation/keyboard
compact/font scale; actual TalkBack speech when available. For the new repair, add:
discard only a captured disposable generation, close unchanged with Keep recovery,
relaunch and restore; two documents closing; cancel or fail close and verify the
draft stays open. Do not confuse instrumented setup with physical keyboard input.

Other reproducible commands live on their issue branches:

```sh
python tooling/docs/validate_docs.py
python -m unittest discover -s tooling/docs -p 'test_*.py'
python tooling/verification/reproduce_inherited.py
python -m unittest discover -s tooling/verification -p 'test_inherited_*.py'
python tooling/docs/validate_proposals.py
python -m unittest discover -s tooling/verification -p 'test_poppler_tool.py'
```

Run only commands present on the selected branch; dependency branches are explicit
above. No new runtime dependencies were installed. D-drive Gradle setup is retained;
no global cache deletion, user document cleanup or input-setting change occurred.

## Owner actions / stop boundary

After reviewing concrete branches, supply exact human-written title/body text for
the eight new primary PRs (recovery,15,17,22,23,24,28,29). CONTRIBUTING.md requires
this and prohibits generated public messages/attestations. Existing contributor
descriptions stay untouched. Separately accept/reject the three proposed additive
records; until accepted none changes normative authority. The owner decides whether
to incorporate31/34 follow-ups and request fresh hosted checks. No merge or issue
closure is performed here. Native positioning/keyboard/AT/lower-API limitations
remain explicit; this batch does not qualify the entire roadmap or all platforms.

## Final local checkpoint

All six final gates PASS on clean commit
9d8f60bf148b10848e91c82e2352832b74cf4cd8. The first invocation's missing
locked Yjs dependency failure remains retained; the complete second invocation
passes without changing that commit. Desktop workspace21 stages and four mutants
pass. A separate binding receipt verifies all166 compilation/resource inputs of
the pre-commit packaged APK equal this clean commit; the original build receipt
was not rewritten. Public core API dumps and desktop source are unchanged.

Eight tested issue/follow-up branches were pushed under codex/ names, followed by
the recovery branch after its gates passed. No PR was created without human text.
Main and contributor heads remain unchanged. Follow-up commits include their own
CI regression steps; they are for existing PR31/34, not competing PRs.

A read-only ADB screenshot after automated launch was inspected:
`tests/local-evidence/repair-batch-20261023/native-launch-diagnostic/screen-after-start.png`.
It shows the final APK's mixed Urdu/Latin editable sample. This is launch/render
diagnostic evidence only. Toolbar/status rows extend beyond the viewport; actual
horizontal-scroll discoverability/reachability remains untested, not dismissed.
No new native keyboard, SAF, image-picker, rotation or AT result is claimed.

A fresh isolated API28 attempt corrected AVD identity, used an absolute image
path, removed the emulator-home override and explicitly selected qemu2. Installed
emulator37.1.2 still exited before registration with Unknown AVD name and missing
kernel_cmdline.txt. Raw attempt is `lower-api-retry/`; no device was wiped or
minimum API raised. A compatible lower-API emulator remains required.

One bounded rerun of PR33's failed Android job was requested after preserving
attempt3's full artifact, job metadata and explicit LMK logcat. Attempt4 uses
the same contributor SHA. Its outcome is recorded below when available; neither
a later success nor a missing result erases the original failure.

Issue15/17 versus contributor30 compatibility was executed in a disposable
detached checkout: applied30's exact patch onto17 (which includes15), retained
new CI checks while resolving the Node-pin context overlap, then ran transitive
integrity, protected hashes and all25+16 deterministic comparisons. PASS.
Receipt: `integrity-runtime-compat-results/comparison.json`; exact patch/input
identities and the context resolution are retained beside it. No commit, merge
or contributor-branch update was used for this compatibility test.
