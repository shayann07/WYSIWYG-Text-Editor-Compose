# Antigravity Plan Corrections & Normative Diff (`plan-v4.md`)

This document records all normative plan corrections and proposed clarifications identified during independent validation against `plan-v4.md`. Each item includes the concrete counterexample, rationale, affected tests/invariants, exact plan sections, and whether it represents an unambiguous consistency fix or a material product change requiring owner review.

---

### Correction 1: Pre-Composition Snapshot Timing for Attributed History
- **Affected Sections**: §4.2 (Composition state, lines 203–205), §7 (History groups, lines 386–390).
- **Classification**: Unambiguous Consistency Correction (Internal Bug Fix).
- **Owner Review Required**: No (Aligns implementation with existing stated invariant I12 and §7 design policy).
- **Counterexample / Defect**:
  If the editor captures the before-history root/text snapshot upon receiving the native `commit` event (as common naive event listeners do), the live buffer already contains intermediate preedit text (e.g. `"nihaollo"`). When the user subsequently invokes Undo, the editor restores the phonetic preedit string (`"nihaollo"`) instead of the document state from before composition began (`"Hello"`).
- **Rationale**:
  The immutable pre-composition snapshot must be captured and pinned at `startComposition` or `markExisting` *before* any provisional preedit splices touch the buffer. The entire composition session (including intermediate preedits and partial commits) must reference this pinned initial snapshot as its `beforeSnapshot`.
- **Affected Tests**: R05, Invariant I12 (`test-r01-to-r05.mjs`).
- **Proposed Plan Wording Update (§4.2)**:
  > *Amend line 203:* "Composition state contains session ID, **an immutable pre-composition document/selection snapshot captured at composition start/mark-existing**, marked range (possibly empty), raw protocol selection, attribution epoch..."

---

### Correction 2: Boundary Coordinate Affinity in AWT Committed-Text Indexing
- **Affected Sections**: §6.3 (Desktop adapter, lines 358–359).
- **Classification**: Unambiguous Consistency Correction (Specification Precision).
- **Owner Review Required**: No (Directly fulfills the existing statement: "Boundary affinity is explicit").
- **Counterexample / Defect**:
  For an active composition covering marked range $[a, b) = [5, 13)$ in document `"Hello[COMPOSED]World"`, the committed text length is 10. When an external IME queries committed text slice $[0, 5)$ via `getCommittedText(0, 5, null)`, if the boundary coordinate $q = 5$ is evaluated naively as $q \ge a \implies live = q + (b - a)$, $q = 5$ maps to $live = 13$. The resulting substring query `fullText.substring(0, 13)` returns `"HelloCOMPOSED"`, leaking the entire uncommitted composed string into the committed text query and failing contract validation.
- **Rationale**:
  The committed-text mapping function must take explicit coordinate association. For a range $[beginIndex, endIndex)$, `endIndex` at boundary $a$ associates `Association.BEFORE` ($live = a$), while `beginIndex` at boundary $a$ associates `Association.AFTER` ($live = b$).
- **Affected Tests**: R12 (`DesktopAwtInputMethodRequestsSpike.java`).
- **Proposed Plan Wording Update (§6.3)**:
  > *Amend line 358:* "For marked `[a,b)`, a committed-text index q maps to live q before a and `q+(b-a)` at/after a; live points inside marked text have no committed-text coordinate. **Boundary affinity is explicit: range endpoints `[beginIndex, endIndex)` must map `beginIndex` with `Association.AFTER` and `endIndex` with `Association.BEFORE` at marked boundary a, ensuring slice `[0, a)` yields prefix `[0, a)` and never leaks `[a, b)`.**"

---

### Correction 3: Code Unit vs Code Point Iteration in Candidate Reconciliation
- **Affected Sections**: §2.3 (lines 106–107), §4.3 (lines 213–216, 222–228).
- **Classification**: Unambiguous Consistency Correction.
- **Owner Review Required**: No.
- **Counterexample / Defect**:
  In runtimes such as JavaScript/DOM or higher-level Kotlin/Java collections, default string iteration (`for (char of str)`) iterates by Unicode code points (yielding 2 code units for surrogate pairs such as emoji). If baseline $B$ or candidate $C$ is constructed as an array of code-point elements while offsets $p$ and $s$ are computed in UTF-16 code units, array index calculations (such as `bLen - cLen + i`) exceed array boundaries and result in `undefined` attribute lookups.
- **Rationale**:
  `plan-v4.md` mandates raw UTF-16 storage (§2.3, I05). All physical candidate arrays and baseline slices must be explicitly indexed by 16-bit code units (or code-unit offset trees), and all internal iteration must use code-unit indexing.
- **Affected Tests**: R02, R14, Property-I07-I08 (`reference-model.mjs`, `test-r14-and-properties.mjs`).
- **Proposed Plan Wording Update (§4.3)**:
  > *Clarify line 213:* "B: immutable attributed baseline slice **(array of 16-bit code units with per-unit attribute maps)**; C: current raw candidate string in the epoch's live island..."

---

### Correction 4: Implicit Composition Session Initialization and Snapshot Pinning on Raw Preedit Callback
- **Affected Sections**: §4.1 (lines 198–199), §4.2 (lines 203–205), §4.4 (Event transition table, line 243).
- **Classification**: Bridge Robustness Specification & Undo State Integrity.
- **Owner Review Required**: No (Directly fulfills Invariant I12 and Regression R05).
- **Counterexample / Defect**:
  Certain platform IMEs (e.g. Windows AWT, some third-party Android keyboards) emit text replacement / composing text callbacks without issuing an explicit prior `startComposition` or `setComposingRegion` event. If the input coordinator naively mutates the document buffer without pinning `compositionBeforeSnapshot`, the intermediate preedit text mutates the document in-place. Upon `commit`, the snapshot falls back to the dirty preedit string (e.g. `"kaHello"`), corrupting the history stack and causing Undo to restore phonetic text instead of the baseline document (`"Hello"`).
- **Rationale**:
  The coordinator must gracefully initialize a composition session at the current collapsed logical selection `[caret, caret)` and immediately pin the immutable pre-composition document/selection snapshot `compositionBeforeSnapshot` *before* applying any provisional text splice.
- **Affected Tests**: R05, R06, Invariant I12 (`protocol-coordinator.mjs`, `test-r01-to-r05.mjs`, `test-r06-to-r10.mjs`).
- **Proposed Plan Wording Update (§4.4)**:
  > *Amend line 243:* "Preedit: **if marked range is null, initialize composition session at current caret `[caret, caret)` and immediately pin the immutable pre-composition document/selection snapshot before splicing;** apply exact splice; reconcile attributes; update marked range and raw selection; publish once."
- **Proposed Plan Wording Update (§4.1)**:
  > *Amend line 198:* "A raw preedit callback received in Idle state implicitly begins a composition session anchored at the logical selection caret, capturing the pre-composition baseline snapshot before any text buffer modification."

---

### Correction 5: Structural Native Admission for Cross-Paragraph Replacements with Newlines
- **Affected Sections**: §2.6 (Structural command compilation, lines 153–155), §6.1 (Admission rules, lines 325–327).
- **Classification**: Clarification of Design Policy.
- **Owner Review Required**: Yes (Product design confirmation on soft breaks vs structural block splits).
- **Counterexample / Defect**:
  A native replacement covering text across two paragraphs (e.g. `"para1\npara2"`) replaces the selection with `"text1\ntext2\ntext3"`.
  Under §2.6 line 154: "opaque native LF inside a text block is a soft break, not an inferred Enter command."
  Under §6.1 Rule 2: "A noncomposing replacement across ordinary compatible same-parent paragraphs can compile into joins plus text splice only if its resulting projection equals the proposed plaintext result exactly."
  In the resulting document projection, the joined paragraph contains soft line breaks (`\n`), which matches the plaintext characters, but does NOT create new structural `<paragraph>` blocks.
- **Rationale**:
  This behavior is intentional (avoiding arbitrary structural node synthesis from unverified native plaintext strings), but needs explicit clarification so adapter implementers do not attempt to invoke Enter command compilers during native replacement admission.
- **Affected Tests**: R04, Invariant I13 (`test-r01-to-r05.mjs`).
- **Proposed Plan Wording Update (§6.1 Rule 2)**:
  > *Amend line 325:* "A noncomposing replacement across ordinary compatible same-parent paragraphs can compile into joins plus text splice only if its resulting projection equals the proposed plaintext result exactly. **Any embedded LF characters inside the replacement payload are admitted as soft line breaks, not inferred structural block divisions. Structural block creation remains reserved for explicit Enter and rich paste commands.**"

---

### Correction 6: Platform Evidence Scope and Qualification Status
- **Affected Sections**: §8 (Phase 0 and Gate G0, lines 406–411), §9.1 (Existing evidence, lines 474–488).
- **Classification**: Normative Qualification Scope Clarification.
- **Owner Review Required**: No (Preserves strict honesty rules of the validation prompt).
- **Details**:
  - Desktop / JVM / Windows: Gate G0 Desktop public AWT host contract and coordinate indexing are **PASS** (verified via `DesktopAwtInputMethodRequestsSpike.java`).
  - Android, iOS, Web: Gate G0 Android, iOS, and Web remain **NOT RUN** because this execution occurred in a headless Windows environment without Android emulators, macOS Xcode toolchains, or browser automation harnesses.
  - Skia Patched Shaper: Status remains **FAIL / REJECTED** (compilation failure and baseline crash under MSVC).
