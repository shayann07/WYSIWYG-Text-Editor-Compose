# S0 executable compatibility experiment

This private package shape is evidence scaffolding, not a production format or public API. Existing `.astra` files and DocumentFiles remain unchanged.

The integrated command `python desktop-prototype/verify.py` runs this experiment against the same new build as the desktop and Urdu checks. A standalone invocation can use an identity-verified build:

```powershell
python validation/integration/s0/verify.py --build-dir <fresh-build> --output-dir <new-evidence-directory>
```

The actual JVM codec generates and reopens v1/v2/v3 fixtures. An independent parser compares raw units, styles and hard/soft breaks with JVM goldens, maps them into an experimental multiflow package, saves new v3 files, then asks the actual codec to verify the returned content. The package round trip includes ordered sections/subtabs, header variants, footnotes, rich merged-cell ownership, stable experimental unit identifiers, attached/orphaned anchors, retained suggestion preimages/resources and opaque optional payload bytes.

Negative cases reject overlapping/missing table slots, duplicate cell flows/identities, cyclic sections, invalid flow/header/footnote mappings, dangling attached anchors, missing review resources, altered resource hashes, unsupported mandatory schemas/features/extensions, unexpected archive entries and duplicate manifest keys. Rejected attempts preserve the source bundle.

The identity counterexample records actual existing behavior: EditorEngine construction and the prototype file codec allocate local IDs rather than retaining arbitrary imported IDs. The experiment's string identifiers demonstrate package retention only; they do not fix production engine identity mapping. S0 stays open for those mappings, accepted C0/review decisions, atomic multiflow commands and retention lifetimes. No user document is migrated.

The [actual-owner admission checks](../c0/OWNER-ADMISSION.md) now reject stale local identity vectors and cross-process-incarnation packets, including a reproduced same-text replacement counterexample. This closes that unsafe admission case while leaving the import/durable canonical ID counterexample above intact. It is not a stable production identity model.

The [typed runtime pairing experiment](../c0/TYPED-IDENTITY.md) addresses a further text/boundary namespace collision and checks surviving semantic references through peer edits and external Undo. It does not make prototype local IDs durable or choose the production namespace, import/migration policy or identity-aware move API.
