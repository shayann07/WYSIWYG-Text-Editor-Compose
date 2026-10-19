# Typed canonical/native identity pairing (M1 / S0)

This private experiment pairs one supported paragraph flow with the actual JVM owner. It does not select a durable ID representation, migrate user documents, freeze a public API or qualify native input. The caller supplies an accepted canonical projection and its corresponding actual committed owner frame; this component is not an authentication, replication-admission or native-fence mechanism.

The saved baseline exposed a namespace collision in an auxiliary header: a real text unit had ID `boundary:header-second`, which also appeared as the frozen projector's generated boundary label. A flat string-ID map selected the hard separator at offset 4 instead of the intended character at offset 0. The actual owner applied that positional patch and removed the boundary. This was an identity-key representation control, not a source mutant or a defect in the positional reducer.

Projection units now carry explicit provenance. References are keyed by domain, flow and opaque ID; paragraph identity comes from `rightId`, never from parsing a label prefix. The legacy `tokenId` field remains diagnostic and must not be used as a universal identity namespace.

| Domain | ID source | Target behavior |
|---|---|---|
| `text-unit` | Canonical unit ID | Selective raw-unit deletion supported |
| `paragraph-boundary` | Right paragraph node ID | Requires a structural command; never treated as text |

Both the prefix-collision example and equal literal text/node ID strings are tested. Document, flow, endpoint nonce and pairing-generation context prevent accidental reference reuse across scopes. These are runtime references, not durable serialized anchors or authenticated identities.

`NativeIdentityPairing.bind` stages the complete mapping. It checks supported document/flow projection, ordered native decimal-string IDs, exact committed raw payload/kinds/styles, typed canonical uniqueness and previously observed native-ID claims. Stale frames refuse. A native ID cannot be relabeled as another canonical identity merely because the text looks equal. A retained canonical identity also cannot silently acquire a new native ID: explicit lifecycle/resynchronization support is required. A removed identity restored by an admitted Undo can pair with a fresh native ID; its old semantic reference becomes usable again within the same pairing generation. Failed staging preserves the prior mapping and all claims.

The target compiler accepts text references, resolves their current identity positions and groups consecutive indices. Discontiguous deletions are sent in descending ranges as one actual owner transaction, preserving interleaved peer units. Current forward/backward selection maps through the deleted units. The command carries `canonicalBasis` from the same owner frame; it cannot refresh a previously prepared command's basis. The real owner still refuses a delayed stale identity/epoch.

Only committed unit IDs enter the mapping. Provisional composition content is excluded, and command compilation defers while composing or waiting for owner work. Abort requires a fresh pairing against the new frame/epoch. The tests use synthetic owner composition and do not establish a real native retirement fence.

`typed-identity.mjs` runs eight checks with two actual JVM endpoints: namespace-safe deletion and Undo, cross-context refusal, repeated-Urdu targets through peer prefix changes, scattered deletion/Undo preserving peer identity/attributes and backward selection, atomic malformed/stale pairing rejection, same-text local-identity rewrite refusal followed by explicit new canonical identity admission, composition isolation, and UTXT-151–154 raw-unit selective deletion. Corrupt pairing data and alternative key policies are controls, not implementation-source mutants. Both owned JVMs must close.

Run all relevant checks through `python desktop-prototype/verify.py`. A focused run accepts:

```powershell
node validation/integration/c0/typed-identity.mjs --build=<fresh-verified-build> --output=<new-directory>
```

Initial full projection in this runner is fixture setup. Ordinary tested changes use granular edits and identity targets. Unsupported rich flows are retained in the canonical model and refuse projection; they are not flattened. First-bind receipt provenance remains the caller's responsibility. Claims are conservatively retained for the pairing's lifetime; no capacity, persistence or GC qualification is added. Identity-aware moves, full-root resync/fence policy, canonical namespace/import consistency across every flow, global ID allocation and production engine/codec retention remain open C0/S0 work. A new pairing generation is not permission to bypass native lifecycle qualification. M2 remains gated.

The later [identity-preserving transition trial](IDENTITY-TRANSITIONS.md) adds the bounded paragraph move path and a monotonic committed identity generation against move/inverse ABA. It narrows the native move gap without selecting a production index, global ID model, richer structure mapping or resync/fence policy.
