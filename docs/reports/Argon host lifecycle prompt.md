Work in D:\Work\WYSIWYG-Text-Editor-Compose.

Current reported HEAD: 1e42b2a. Qualify the new AWT host replacement and close its concrete lifecycle gaps. Do not start storage, pagination or new editor features in this task.

Read applicable AGENTS.md and preserve existing work. The uncommitted README/v5.1 documents belong to Codex's scope amendment: do not modify or commit them. Keep all original v5.0.0 hashes unchanged. No push or tag changes.

MISSION: establish what the new dedicated InputContext and EditorSurface replacement actually guarantee, and make failures explicit.

1. HOST REPLACEMENT LIFECYCLE

Test repeated recovery through the actual mounted editor:
- Replace the input host 100 times using fixture text.
- Verify exactly one active input surface remains at layer 100.
- Old hosts reject ordinary text, caret, key and focus callbacks.
- The current host receives subsequent input, including repeated identical text.
- Save/Open callbacks, toolbar actions, diagnostics, accessibility notifications and engine observers target the current host.
- Preserve the selected keyboard/locale and composition-enabled state where the public API permits it.
- Check timers, input contexts and retained host references with explicit lifecycle counters. Do not claim a heap-leak proof solely from object counts.
- Cover partial commits, attributed Undo/Redo, discard-close, ordinary close and removal during replacement.

2. FAILURE PATHS

abortAndRetireComposition currently wraps context teardown operations in runCatching. Inspect every swallowed failure.

Inject failures in context completion/removal/disposal and replacement creation/attachment. Record diagnostics and preserve recoverable state. Do not mark a new context qualified or report successful recovery when teardown or binding failed.

Show which public platform guarantees establish that old-context input cannot be delivered as new-host input. A synthetic event whose source is manually set to the old component proves only the application's old-source guard.

3. REAL INPUT, ONLY IF ACCESSIBLE

Use one short fixture-only interactive smoke test if the environment permits: Japanese preedit and commit; recovery/host replacement; another Japanese composition; switch to Urdu/Arabic and type; verify selection, Undo and candidate placement.

Keep English as the default. Do not change more system settings, fight user activity, use repeated screenshot loops, or classify injected text as native IME qualification.

If the environment cannot run this test, mark native context-replacement qualification NOT RUN and provide a short manual procedure. Do not fake a passing result or spend the session trying to bypass foreground restrictions.

4. RELEASE CLAIMS AND REPRODUCTION

Run the current deterministic suite from an isolated build. Preserve the original RecoveryAudit regression.

Update critical-correctness-repair.md and critical-correctness-results.json. Application-path isolation, lifecycle/failure-path tests, and real native qualification must have separate verdicts.

If automatic abort-and-continue cannot be justified on a target, expose the conservative recovery capability explicitly rather than silently claiming support.

Make small local commits for demonstrated fixes/tests. Stop after this task. Report what is proven, what remains unrun, and whether any concrete failure remains. No broader redesign.
