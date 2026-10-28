# Android memory-service responsiveness fixtures

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
