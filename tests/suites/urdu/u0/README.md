# Urdu U0 profile and corpus

This is reproducible Desktop evidence for the Urdu-first document application, not full Urdu/native qualification. Fonts remain candidates pending human review. See URDU-ACCEPTANCE.md and reports/overnight-progress.md for scope and current evidence.

Run from the repository root:

```powershell
python desktop-prototype/verify.py
```

This builds once in a new isolated directory and executes desktop, recovery, stress, attached-host, Urdu U0, real implementation mutation and Argon regression checks against the same artifacts. Source, fixture, dependency and font identities, runtime versions, actual timestamps, commands and exit codes are recorded. A changed source or build artifact invalidates the run.

For U0 alone (still a fresh isolated build):

```powershell
python validation/urdu/u0/verify.py
```

`latest-run.json` points to the actual per-run results. RUNNING and FAIL never inherit an earlier PASS. The legacy `results.json` and root contact_sheet.html are historical snapshots and are not refreshed by this runner. Each new run has its own results, logs, specimen PNGs and contact sheet under desktop-prototype/local-evidence/.

The corpus has 309 fixtures: 271 executable, 28 corpus-only typography specimens and 10 future structure/page contracts. Each executable fixture's declared operations are dispatched and recorded in executedOperations; unsupported operations fail. A title describing a keyboard, backspace or structural scenario is not evidence that this operation ran. Inspect the operation list. UTXT-306 through UTXT-309 explicitly execute provisional input, partial prefix acceptance, remaining-candidate commitment, exact attributed one-session Undo/Redo and save/load. Synthetic engine/AWT events do not qualify an OS keyboard or IME.

Metadata correction v1.1 defines `scalarCount` as valid Unicode scalar values; isolated surrogate code points are excluded from that count while remaining present in raw text/code-unit arrays. Only that field changed in UTXT-151 through UTXT-154. Python metadata validation and Java's independent code-point decoder check it. No canonical text, operations or expected editing outcomes were changed by this correction.

The integrated mutation runner compiles copies of EditorEngine.kt with three implementation changes. A passing baseline is mandatory. Only a nonzero test exit containing the intended fixture assertion counts as detection; a compiler error, timeout or unrelated exception fails the run. Input corruption and snapshot/oracle alteration are not implementation mutations.

Pinned candidates are Noto Nastaliq Urdu and Gulzar; Noto Sans Arabic is a separate fallback specimen. Exact binaries, versions, hashes, metadata and upstream license/author notices are in fonts.json and fonts/OFL.txt. The existing SIL OFL notices and embedding metadata are checked; this does not select the project's license. Human Urdu/calligraphic review, production fallback policy and universal clipping qualification remain NOT RUN.

The desktop editor's Typeface selector compares these candidates during editing. Candidate profiles validate font identity and use natural metrics at 26sp; System retains the legacy 18sp/30sp layout. Changing the profile invalidates geometry without changing text, selection, attribution, history or composition. It is a presentation experiment, not persistent per-run document font formatting or a production font choice. Missing or modified pinned fonts are rejected instead of silently selecting another profile.

The automated layout probe renders 15 Compose Paragraph specimens with the pinned fonts and records current measurements, cursor rectangles and line metrics. OpenType table metrics are separate metadata; they do not prove ink bounds, visual correctness, fallback shaping, printing or PDF fidelity.
