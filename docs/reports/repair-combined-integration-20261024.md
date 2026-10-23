# Combined repair integration candidate — 24 October 2026

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
