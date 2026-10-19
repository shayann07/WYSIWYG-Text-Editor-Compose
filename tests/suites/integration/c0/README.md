# C0 candidate compatibility experiment

This is an isolated experiment using the existing Yjs implementation, pinned to 13.6.33 by package-lock.json. It does not change the production editor authority, choose the production replication algorithm, implement cloud synchronization or pass C0.

Install only this private experiment's dependencies with `npm ci --ignore-scripts --no-audit --no-fund` in this directory. From the repository root run:

```powershell
node validation/integration/c0/candidate.mjs --output=desktop-prototype/local-evidence/c0-new-run
```

Use a new output directory. Results retain the exact fixture/dependency hashes, named trace outcomes and 20 deterministic seeds with 5,000 generated commands each. The generated lane uses current Urdu U0 raw code units in body, two cell flows and header/footer flows. It checks schema validity after publications, convergence after causal delivery of reordered/duplicated transport messages, and reconnect from a full encoded state. This is a small bounded per-unit representation, not a scaling qualification.

Necessary distinctions:

- Y.Text's documented offsets are UTF-16 units; that alone does not prove raw malformed-unit fidelity through its binary transport. The experiment records the actual isolated-surrogate result.
- The numeric-unit adapter preserves each raw UTF-16 value and inline/paragraph attribution. It is experimental schema mapping, not a new CRDT algorithm.
- Raw reordered update application and causal publication admission are separate lanes. The control records incomplete application-unit fields; the guarded lane waits for the captured public Yjs state-vector frontier. Transport may reorder while semantic publication remains deferred. Native input lifecycle and durable queue integration remain unimplemented.
- Selective Undo uses Y.UndoManager with actor origins. It does not reuse the desktop engine's root-snapshot Undo across peer changes.
- `history-session.mjs` uses an isolated synthetic clock to reproduce the default timeout splitting a delayed partial commit into two history groups. Explicit session boundaries keep the entire gesture in one exact Undo/Redo group, independent of the 60-second simulated gap. This is a clock control, not a real IME or implementation mutation.
- A named competing accept/reject trace shows why independent CRDT map conflict resolution cannot implement atomic review decisions by itself. The merged review label and document effect must agree through a typed admission policy.

Fixture client IDs are explicitly assigned before writes for reproducibility. They are not authenticated actor identities or a production allocation scheme. The full C0 structural conflict corpus, permissions/revocation, compaction/retention, native composition deferral, KMP portability and production owner integration remain open. Candidate unit convergence cannot close these requirements.

Primary API references: [Y.Text](https://docs.yjs.dev/api/shared-types/y.text), [Yjs source and UndoManager documentation](https://github.com/yjs/yjs). The installed Yjs package includes its upstream MIT notice. No repository/application license or public distribution decision is made by this experiment.

The integrated parent runner records command exit codes and source/dependency identities. It can reuse a completed C0 execution only when every recorded source, installed dependency, fixture and runtime identity still matches and the saved result artifacts are unchanged. Reuse is labeled `REUSED_VERIFIED_EVIDENCE` with the original command receipts; it is not presented as a new 100,000-operation run. Source/runtime/artifact changes invalidate reuse.

The [structural trial](STRUCTURE.md) adds 10,000 generated mutations and named selective inverses using the frozen tree utilities. The [actual-owner admission trial](OWNER-ADMISSION.md) binds synthetic partial composition, buffered remote work, an atomic native-owner transaction and external grouped Undo/Redo to the real JVM engine. Its terminal and abort/fence cases are separate from real OS qualification. The integrated Python C0 runner now requires `--build-dir` for these owner cases.

The [durable replay experiment](DURABILITY.md) adds actual SQLite transactions and deliberate service-process exits before/after commit. It checks lost acknowledgements, idempotent Undo, replayed gesture history, actual owner attribution and retained denied work. Its process-fault controls are separate from source mutants and do not qualify production autosave or native input durability.

The [checkpoint/offline recovery experiment](COMPACTION.md) adds atomic log compaction, explicit epoch/basis admission, retained selective history and an actual JVM offline-frame case. Its source/runtime-bound V8 checkpoint is tooling only; semantic retention is not garbage-collected and no production schema or native recovery UI is claimed.

The optional [portable checkpoint lane](PORTABLE-CHECKPOINT.md) preserves the same presence-sensitive state through an independent Python transport and actual SQLite adoption. Its six named checks and duplicate execution of the nine process-fault/JVM compaction cases are part of integrated verification; successful worker replies are compared across codecs. It remains a private source-bound experiment, not a production schema decision.

The [writer/compactor contention trial](WRITER-CONTENTION.md) uses actual service processes and explicit transaction barriers. Busy admission retains the unchanged request without acknowledging it; retry, rollback, selective history and expired-epoch recovery run in both codecs. The bounded results do not qualify a general multi-server service.

The [typed identity pairing trial](TYPED-IDENTITY.md) tests explicit text/boundary namespaces, atomic pairing and identity-targeted edits in the actual owner. It prevents the reproduced boundary-label collision while retaining peer units, raw attribution and provisional isolation. Global/durable identity allocation and native lifecycle qualification remain open.

The [identity-preserving transition trial](IDENTITY-TRANSITIONS.md) adds actual owner moves and supported paragraph reorder/Undo without text-ID replacement. It covers a reproduced move/inverse ABA hole with a committed identity-generation stamp and compares 96 additional authority operations against actual owner transitions. These counts are separate from the primary 110,000 generated C0 mutations. Production indexing, IDs and native fences are not qualified.
