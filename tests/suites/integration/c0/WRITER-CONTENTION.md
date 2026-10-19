# Writer/compactor contention experiment (C0, M1)

The original adapter threw raw SQLite error 5 (`database is locked`) from `BEGIN IMMEDIATE` when another connection held a writer. The separate-service-process baseline reproduced that exact failure before the response change. It did not establish a successful acknowledgement or delete the pending intent.

`accept` now returns `RetryableBusy`, `acknowledged: false` and the original `retainedIntent` when this specific BEGIN admission fails. The compactor returns the same non-acknowledging outcome without an event payload. A failed BEGIN has not acquired a transaction or applied an event. Only the observed `ERR_SQLITE_ERROR`/errcode 5 case is classified; other errors still propagate. This does not infer the outcome of a failed later COMMIT or lost acknowledgement. Retry scheduling, backoff and production service APIs remain undecided.

`writer-contention.mjs` runs actual separate short-lived service processes on new, initialized databases for both checkpoint codecs. The private worker can hold its real transaction after SQL writes and before COMMIT, announce its PID over stdout, and wait for one parent-owned pipe byte. Commit releases it; abort/EOF exits 83 and rolls it back. This is deterministic transaction ordering, not a timing sleep or a native IME fence. All holders are closed in the finalizer; forced cleanup fails the experiment.

Nine checks cover:

- A held writer blocks both another writer and a compactor. Neither acknowledges, changes state/history nor rotates the epoch. The original request is retained verbatim, then retries once after release; duplicates have no second effect.
- Selective Undo/Redo preserves the peer's exact raw units, identities and attributes after contention.
- A held writer exits 83 before COMMIT. Its SQL writes roll back; both retained requests later apply exactly once.
- A compactor holds its uncommitted checkpoint/epoch change. The blocked writer retains its old request; after compaction that unchanged request receives explicit expired-epoch recovery. A separately named new recovery action is required.
- Connection-local read-only error 8 propagates without acknowledgement or state change; it is not mislabeled Busy.
- Both codecs finish with identical full canonical/history/frontier snapshots and semantic hashes in all three contention scenarios.

The first five checks run in both lanes where applicable. The four UTXT-151–154 malformed-unit specimens appear in pending, rollback, retry and recovery requests. Their raw code units and relevant B/I attributes survive. Request files are immutable and reused by content identity across retries. These are fixture intent files, not a production outbox or autosave implementation. Process exits/read-only failures are labeled controls, not implementation-source mutants.

Run through `python desktop-prototype/verify.py`, or directly:

```powershell
node validation/integration/c0/writer-contention.mjs --output=<new-directory>
```

Reports retain every process command, PID/barrier, actual exit and source/fixture identity. This is bounded contention on an initialized local SQLite WAL database. First-time schema creation races, distributed/network filesystems, throughput/fairness, extended SQLite error codes, general multi-server deployment, power loss, authentication and native input/storage publication remain unqualified. No user document, system setting, public schema or library API is changed. C0 remains open.
