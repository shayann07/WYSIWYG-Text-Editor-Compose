# v5.0.0 freeze decision

### 9.7 Freeze decision and change control

**Specification v5.0.0 is frozen as a reviewed semantic contract and conditional architecture baseline.** This freezes the selected ownership, schema, admission/settlement, command/anchor/cell, formatting, history, storage-measure and recovery contracts. It is not a proof of complete correctness, a validated production implementation or a guarantee that each native backend is feasible. The exact frozen artifacts and evidence are identified by v5-freeze-manifest.json.

No unresolved contradiction has a passing-status exception. Known experimental dependencies are explicit gates, not facts assumed solved: real input provenance/cell/gap hosting, DOM/AT behavior, production persistent indices and memory, filesystem durability, import/export pipelines and full-pipeline performance. G0 host feasibility runs before broad production construction when that work is separately authorized. A failed bridge/backend/storage experiment that cannot preserve these contracts is an architecture reopen trigger, not permission to cut required functionality.

Reopen only for a reproducible counterexample, primary-source incompatibility, measured failure of an acceptance gate, or an explicit product requirement change. Record the smallest trace, expected versus observed behavior, affected invariant, selected correction and discriminating regression. Use v5.0.x for compatible clarifications/repairs; version incompatible schema/protocol/product changes explicitly. Preserve this freeze and its evidence. Generic requests to re-audit without new evidence do not force another specification revision.

Release remains blocked on applicable G0-G8 gates. Freeze does not authorize production implementation. The specification is sufficient to direct the next qualified experiments and later implementation without asking coding agents to invent these core contracts.


See v5-validation-report.md for independent acceptance/rejection of Gemini findings and v5-qualification-matrix.csv for open gates. No production implementation was performed.
