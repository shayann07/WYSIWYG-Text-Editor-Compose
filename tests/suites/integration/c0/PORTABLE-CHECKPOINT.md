# Optional portable checkpoint feasibility lane (C0/S0, M1)

This is a private, explicitly versioned representation experiment. It does not select a production collaboration algorithm, storage schema, migration policy or public API. The default V8/source/runtime-bound lane remains available. Existing evidence databases and user `.astra` files are not migrated.

The actual checkpoint inventory contains seven Maps, one Set and plain state/history records with present-but-undefined fields. The earlier naive JSON control drops `expectedOwner: undefined` from a Redo inverse and then overwrites a later peer relocation. Merely declaring JSON portable would lose a required history guard.

The optional `portable` lane reuses the authority's existing explicit semantic state projection. Known Maps become entry arrays; the retired-producer Set becomes an array. A reversible tagged-value form preserves plain records, dense arrays, scalar values, negative zero and explicit undefined. JSON strings retain raw UTF-16, including isolated surrogates, through well-formed escaping. Literal tag-shaped user arrays remain ordinary values. This is a value-tree representation; runtime classes, nested collections, cycles, sparse/extended arrays, accessors and unsupported values reject. It is not a general object-graph serializer or a promise about reference aliasing.

The loader checks codec/source compatibility and payload checksum, decodes into temporary values, reconstructs only the known maps/set, independently validates the canonical bundle, and compares the complete existing semantic/history/retention hash before adoption. Unknown state fields, duplicate map/set identities and lost ownership guards refuse. Compatibility remains bound to model/codec/frozen utility source identities; only the portable payload omits Node/V8 runtime tags. Checksums provide corruption detection, not authentication against a writer able to replace both payload and hashes. Full production history/schema validation and migrations remain open.

The grammar is `["c0-portable-tooling-state-1", value]`, with values recursively tagged as `["undefined"]`, `["negative-zero"]`, `["value", scalar]`, `["array", values]` or `["object", [[key, value], ...]]`. Object keys must be unique strings and are defined as own data properties without prototype assignment. The trial bounds payloads to 32 MiB, depth to 128 and traversed values to 1,000,000; these are tooling bounds, not product capacity or heap/performance qualification. UTF-8 decoding is strict.

`checkpoint_reference.py` independently decodes and re-encodes actual payloads in Python. A Python-reserialized checkpoint is adopted by the actual SQLite authority and retains the Redo guard. Both implementations reject eight malformed-input specimens, including bad UTF-8, duplicate keys, unknown versions/tags, deep input and oversize bytes; expected rejection reasons and subprocess exits are recorded. These are corruption/serialization controls, not implementation mutants.

`portable-checkpoint.mjs` runs six named checks, including identical V8/portable authored traces and all four malformed UTXT-151–154 specimens through actual SQLite reopen and exact attributed Undo/Redo. The existing nine-case `compaction-recovery.mjs` runs once per codec on separate new databases. The integrated verifier compares every successful worker receipt and snapshot between lanes, including semantic hashes, allowing only checkpoint byte size to differ. Both lanes retain actual pre/post-COMMIT process-fault controls and the JVM offline-frame/raw-unit projection checks. The publisher/fence in that recovery trace remains explicitly synthetic.

Run all relevant checks on one fresh build:

```powershell
python desktop-prototype/verify.py
```

Focused value/authority checks, without a JVM or UI:

```powershell
node validation/integration/c0/portable-checkpoint.mjs --output=<new-directory> --python=<existing-python>
```

The paired process/JVM trial uses `compaction-recovery.mjs --checkpoint-codec=portable --build=<fresh-verified-build> --output=<new-directory>`. No semantic retention garbage collection, concurrent-server/power-loss qualification, authenticated producer allocation, production recovery UI or real native input qualification is added. C0/S0 remain open.
