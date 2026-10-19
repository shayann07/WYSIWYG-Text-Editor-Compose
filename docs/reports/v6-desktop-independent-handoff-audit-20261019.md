# Independent audit of the completed V6 desktop batch

19 October 2026. Scope: source inspection, exact-build verification, focused executable reruns and inspection/revalidation of recorded native evidence. No production changes and no new live desktop interaction in this audit.

## Verdict

Accept the bounded milestone. No new blocking product defect was demonstrated by this audit. This is not whole-editor, native IME, accessibility, performance, or print qualification.

- Independently validated the build inputs and compiled artifacts using `verification.validate_build`. All 365 captured inputs match current files. Build: `batch-20261019-164203-33756`; manifest SHA256 `9ca8c7eb0bfbc1fa7556b920f2fc50c8623534e391f681686465a43346a86d22`.
- Inspected all 17 recorded integrated stages: PASS, exit zero. Did not rerun all 17 or repeat the process-kill campaign.
- Reran actual compiled `editor.MainKt --check` and `--v6-qualification-check`: both exit zero. New output is under `desktop-prototype/local-evidence/independent-v6-handoff-20261019/`.
- Inspected the normalized retarget/partial acceptance path, explicit-abort pending typography handling, persisted marker seed/migration, sibling lane calculation and focused tests. The zero-count witness is a regression, not evidence of a previously reproduced Kotlin data-loss defect.
- Re-executed the assertions in both `v6-native-final-resume-20261019/audit.py` and `v6-native-finish-20261019/audit.py`, suppressing only their top-level output writes/printing. Both passed against existing transcripts and saved payloads. This revalidates recorded evidence, not fresh native interaction.
- Inspected the saved native English bullet and Urdu numeral crops and short-window Outline/Find screenshot. The former raised/small marker defects are corrected in these captures. This is not an expert Urdu typography assessment.
- Documentation validator: 48 protected historical hashes, original review snapshot intact, 139 active links checked, zero broken links. Its dated source snapshot reports the expected changed VisualRepairChecks.kt; this is not frozen specification corruption.

An initial audit-wrapper attempt read Python source using the Windows default encoding and failed the Urdu expected-string comparison. Reading the unchanged script explicitly as UTF-8 resolved that audit-wrapper error; no fixture, expected value, receipt, or production source was changed.

## Still open

Real composing-IME lifecycle is NOT RUN due to unavailable Japanese native context. Other target platforms, full AT, expert typography, broad DPI, large-document latency and PDF/printing remain unqualified. Provisional composition, retained drafts and quarantined callbacks are still memory-only unless exported. The latter is the next bounded implementation assignment, following V6 D08.

Next prompt: `docs/prompts/v6-durable-unfinished-recovery-next.md`. Preserve the completed V6 batch and frozen specification; no new broad redesign is requested.
