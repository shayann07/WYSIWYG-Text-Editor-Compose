# Identity-preserving paragraph transitions (M1 / S0)

The actual-owner baseline rejected a `move` edit, while the ordered authority could reorder two identical-looking paragraphs. Rebinding unchanged text to the new canonical order would reassign retained identities and was correctly refused. Full replacement is not the normal-edit solution: it discards native unit identities.

The private `CanonicalProbeEdit.Move` now moves a nonempty committed UTF-16 span without recreating its `TextUnit` values. Destination is measured in the shortened sequence after removal. Moves stage in the same atomic transaction as raw insertions, removals and attributes. Invalid ranges/destinations or a later failing edit roll the entire document/selection back; no partial result or local snapshot history is published. This is a feasibility boundary, not a public library move API.

`NativeIdentityPairing.compileTransition` consumes an accepted supported paragraph projection. It removes retired identities, reorders retained spans, inserts new identities and applies changed B/I flags. Text-unit IDs and explicit paragraph-boundary provenance stay separate. Moving a paragraph to the front removes its old boundary and introduces a boundary before the old first paragraph; retained text IDs survive. Literal soft LF stays text. A retained identity cannot change its character/kind. Unsupported rich structure is refused rather than flattened. The staged edit sequence is simulated and checked against the requested typed payload before sending it.

Selection endpoints prefer their immediate surviving right unit, then their immediate surviving left unit, then the nearest surviving neighbor. This preserves the tested interior caret/backward selections through move and Undo. It is an explicit prototype policy; full hierarchical association/affinity remains a library qualification task.

The new move exposed an actual ABA defect: one identity-only move followed by its inverse restores the same text and ordered IDs, so revision/vector alone accepted an old request. A monotonic owner-local `identityGeneration` now advances once when a successful publication changes the final ordered committed IDs. Canonical basis and in-memory recovery drafts capture it. Caret-only changes, attributes without identity change, rejected transactions and provisional-only composition do not advance it. Semantic document revision rules remain unchanged. Failed staging never advances the generation. A single atomic final no-op is not an externally changed identity basis.

Recovery also rejects a retained draft after an identity-only move/inverse, even when its old exact root returns. Host teardown can refresh a draft's visible revision only if its captured committed identity generation still matches. This is an in-memory owner stamp, not durable identity allocation or a schema migration; `.astra` v1–v3 remain unchanged. A new owner is still distinguished by the private endpoint incarnation.

The later owner-scope repair also binds an in-memory capsule to an opaque per-engine marker. Equal local root/revision/generation values in another engine are not proof of the same recovery context. Owned capture and copy preserve that marker; host rebase first checks owner membership. This is independent of the JSON endpoint nonce and is not a durable DocumentId or authentication credential.

`identity-transition.mjs` executes five groups against the actual JVM:

- Identical-looking paragraph move and external Undo, retaining every text-unit ID and backward selection, without full-root replacement.
- Identity-only move/inverse restoring the exact text/ID vector, with the old generation refused.
- A valid transition followed by an invalid later move: full rollback and retry of the unchanged captured command.
- Four deterministic seeds, 96 accepted authority operations (24 moves, 24 inserts, 24 removals, 24 formats), compared with actual owner payload and surviving identities. The focused run produced 93 native transactions and three unchanged projections; counts are recorded separately.
- Composition deferral, abort refusing to release replica work by itself, an explicitly labeled synthetic fence, stale-epoch refusal and fresh transition admission.

The four malformed UTXT-151–154 fixtures participate in the generated lane. Kernel checks separately preserve moved raw attribution/hard-break values and assert atomic rollback. The identity-generation baseline fails the intended stale-basis assertion before the fix. Invalid inputs, process controls and synthetic fences are not implementation mutants or real OS qualification.

Run `python desktop-prototype/verify.py`, or use `identity-transition.mjs --build=<fresh-verified-build> --output=<new-directory>`. The owned JVM closes. The array-based reference compiler is bounded evidence, not a persistent index or scaling claim. Caller authority/receipt provenance, global/durable IDs, rich-flow import/moves, production resync/native fences and API/schema selection remain open. Neither a new pairing nor an engine abort grants native lifecycle permission.
