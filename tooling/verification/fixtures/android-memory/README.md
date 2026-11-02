# Android memory-service responsiveness fixtures

Current observer contract, 2 November 2026: pre-install and pre-instrumentation
observations read `adb exec-out cat /proc/meminfo` once. A successful bounded,
strictly parsed kernel observation is KERNEL_OBSERVED, not proof of AM readiness,
available capacity or boot completion. Existing AM/package/unlocked-user probes
and the installation broadcast barrier remain separate. The required stable
fields are MemTotal, MemFree, MemAvailable, Buffers, Cached, SwapTotal and SwapFree,
all unique nonnegative kB integers with ordinary total/free bounds. Other kernel
fields are allowed; architecture-specific fields are not required. Empty, denied,
truncated-required-field, malformed, duplicate, bad-unit, nonzero-exit, stderr or
late responses block execution. No RAM sufficiency threshold is introduced.

The global Android dump is not passive: Android17 MemBinder disables the app
freezer around a process-memory traversal. Reference source and a retained local
image's bytecode confirm that behavior, without proving the exact hosted binary
or historical LMK cause. Consequently it is no longer an admission observer.
An explicitly intrusive post-failure diagnostic may still capture it. The original
118-byte failures remain BLOCKED historical receipts; their parser and negative
contracts remain tested below, not relabeled as passing kernel observations.

The following paragraphs describe the retained historical global-dump contract,
not the current pre-install/pre-instrumentation command.

These are exact raw diagnostics from disposable hosted API37 emulators. File
hashes, sizes and original local evidence paths are in `provenance.json`.
The repository's `* -text` attribute preserves their original bytes.

- `api37-responsive.txt`: complete diagnostic retained from passing PR35 head
  `058d2f8db40aa193e0753b65f43748f3be5f8543`, CI run `40333158819`.
- `api37-timeout.txt`: 118-byte diagnostic retained from failing PR35 head
  `3d812e6a1297be9537d8df8da93add7d6fe33c9a`, CI run `40464372511`.
  Two service timeouts are explicit; command exit zero is insufficient.

The reader establishes a completed, parseable memory-service response only. It
does not establish sufficient free RAM, completed boot or editor qualification.
No free-memory threshold is used. Older Android output formats are explicitly
unqualified by this new guard and retain their existing lane behavior.

Run the host regressions without an emulator:

```sh
python -m unittest discover -s tooling/verification -p 'test_android_memory_readiness.py' -v
```

The production runner calls this guard once before installation and once before
cold instrumentation, within its existing setup deadline. Rejection is BLOCKED,
exits nonzero and leaves editor/recovery/clipboard NOT RUN. No polling, test retry,
warm attachment, priority adjustment or instrumentation timeout extension occurs.

A successful `android_check` qualifies its editor groups and recovery witnesses
only. Clipboard stays NOT RUN in that receipt: the separate `android_clipboard.py`
workflow step owns cross-process clipboard qualification and its own receipt.
