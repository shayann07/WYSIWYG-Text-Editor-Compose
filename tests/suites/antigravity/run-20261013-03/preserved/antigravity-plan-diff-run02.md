# Antigravity Plan Corrections & Normative Diff (`plan-v4.md` -> Revision 4.4)

**Date**: 13 October 2026  
**Validator**: Antigravity Independent Validation Lead  
**Scope**: Falsification audit, coordinator defect reproduction, AWT spike correction, and normative plan audit.  
**Authoritative Target**: [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md)  
**Evidence Run Directory**: [`validation/antigravity/run-20261013-02/`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/validation/antigravity/run-20261013-02/)  

---

## 1. Classification Taxonomy

In compliance with prompt instructions, all audit findings across `plan-v4.md` and the validation harnesses are classified into:
- **(a) Harness violates an already-correct requirement**
- **(b) Specification is ambiguous or incomplete**
- **(c) Specification is contradictory or incorrect**
- **(d) Unresolved platform feasibility**
- **(e) Product policy requiring an explicit decision**

---

## 2. Itemized Audit Findings & Applied Corrections

### Finding 1: Settlement Bypass during `AwaitingSettlement`
- **Classification**: 
  - **(a) Harness violation**: The run-01 coordinator harness checked `if (this.compositionState === 'Composing')` in `dispatchAppCommand()`. When a second command arrived in `AwaitingSettlement`, it executed immediately on the uncommitted text buffer.
  - **(b) Specification incomplete**: §4.4 did not explicitly state how the coordinator handles subsequent `preedit` callbacks arriving while in `AwaitingSettlement`.
- **Reproduced Defect**: 
  Start composition, preedit `"nihao"`, queue Bold (state becomes `AwaitingSettlement`), queue Italic. In run-01, Italic returned `{ status: 'Applied' }` immediately. Subsequent preedit reverted state to `Composing`, dropping the settlement request.
  *(Saved in `validation/antigravity/run-20261013-02/reproduce-coordinator-defects.log`)*.
- **Normative Plan Correction Applied (§4.2, §4.4)**:
  Commands requiring settlement remain queued in strict FIFO order in *both* `Composing` and `AwaitingSettlement`. Subsequent `preedit` callbacks received while in `AwaitingSettlement` maintain the `AwaitingSettlement` state and must not drop, bypass, or cancel the pending settlement request.
- **Verification**: `test-coordinator-repairs.mjs` (Case 1) and `test-mutations.mjs` (Mutation 2).

---

### Finding 2: Inaccurate `CompositionTarget` Extent Calculation
- **Classification**:
  - **(a) Harness violation**: The run-01 harness executed `if (cmd.target === 'composing') { start = 0; end = this.docText.length; }`, making the entire document bold.
  - **(b) Specification incomplete**: §4.5 stated that `CompositionTarget(sessionId)` resolves to the "concrete committed range $[start, end)$ resulting from that composition session", but omitted the exact mathematical algorithms for candidate growth, candidate shrinkage, empty final candidates, partial commits, and target expansion.
- **Reproduced Defect**:
  In `"Hello"`, compose `[1, 2)` (`'e'`), preedit `"X"`, queue Bold, commit `"Y"`. Result: entire document `"HYllo"` `[0, 5)` became bold.
  *(Saved in `validation/antigravity/run-20261013-02/reproduce-coordinator-defects.log`)*.
- **Normative Plan Correction Applied (§4.5)**:
  `CompositionTarget(sessionId)` resolves strictly to the final committed extent $[sessionStart, sessionStart + committedLength)$:
  1. *Standard replacement*: $[sessionStart, sessionStart + len(committedText))$.
  2. *Candidate growth*: $[sessionStart, sessionStart + len(expandedText))$.
  3. *Candidate shrinkage*: $[sessionStart, sessionStart + len(shrunkText))$.
  4. *Empty final candidate (cancellation/deletion)*: $[sessionStart, sessionStart)$ resolves to an empty range. Formatting an empty range updates typing intent only, producing NO document revision bump and NO history group.
  5. *Partial commits*: $[sessionStart, sessionStart + totalFrozenPrefixLength + len(finalCommitText))$.
  6. *Target expansion*: $[newSessionStart, newSessionStart + len(committedText))$ covering the expanded envelope.
  *Fallback to the whole document is strictly forbidden.* Surrounding differently attributed text outside this extent remains untouched.
- **Verification**: `test-coordinator-repairs.mjs` (Cases 2.1–2.6) and `test-mutations.mjs` (Mutation 3).

---

### Finding 3: Unmark Contract & Settlement Fence Disconnect
- **Classification**:
  - **(a) Harness violation**: The run-01 coordinator harness had no handler for `payload.type === 'unmark'`, returning `{ type: 'NoChange' }`.
  - **(b) Specification ambiguous / incomplete**: §4.4 stated "Clear composition metadata while preserving current text", but §4.5 warned that unmark is not universally a completion fence. The specification lacked clear separation between transient state clearing and adapter settlement fence completion.
- **Reproduced Defect**:
  Preedit `"abc"` followed by `unmark`. In run-01, state remained `Composing`, `markedRange` remained active `[0, 3]`, and no composition history group was created.
  *(Saved in `validation/antigravity/run-20261013-02/reproduce-coordinator-defects.log`)*.
- **Normative Plan Correction Applied (§4.4)**:
  `unmark` preserves accepted text in the document, clears transient composition metadata (`markedRange = null`, `activeSessionId = null`), and records a composition history group from the pre-composition snapshot to the accepted state.
  If the platform adapter settlement contract treats unmark as a terminal completion fence (`settled: true`), transition to `Idle` and drain pending commands against the accepted extent. If the adapter requires an additional completion event (`settled: false`), transient state is cleared but state remains `AwaitingSettlement` until the platform fence arrives.
- **Verification**: `test-coordinator-repairs.mjs` (Cases 3.1 & 3.2) and `test-mutations.mjs` (Mutation 4).

---

### Finding 4: AWT `InputMethodRequests` Contract Inversion on Empty Slices & Geometry Errors
- **Classification**:
  - **(c) Specification contradictory / incorrect**: §6.3 claimed that endpoint boundary affinity (`Association.BEFORE` for end, `Association.AFTER` for begin) is universally sufficient for slice mapping.
  - **(a) Harness violation**: The spike in run-01 threw `StringIndexOutOfBoundsException` on empty slices at boundary $a$, treated `getTextLocation` offset as committed-text-relative instead of composed-text-relative, and returned non-null hits outside composed text.
- **Reproduced Defect**:
  In `"HelloCOMPOSEDWorld"` with marked `[5, 13)`:
  1. `getCommittedText(5, 5, null)` threw `StringIndexOutOfBoundsException: Range [13, 5) out of bounds`. Mapping $5$ with `AFTER` ($13$) and $5$ with `BEFORE` ($5$) produced inverted range `[13, 5)`.
  2. `getTextLocation(leading(0))` returned screen $x = 0$ (committed offset 0) instead of screen $x = 50$ (composed offset 0 -> live offset 5).
  3. `getLocationOffset(10, 50)` returned `TextHitInfo[1L]` instead of `null` for a hit in committed text outside composition.
  *(Saved in `validation/antigravity/run-20261013-02/reproduce-awt-defects.log`)*.
- **Normative Plan Correction Applied (§6.3)**:
  Endpoint affinity alone is mathematically insufficient for empty slices.
  1. Empty slices (`beginIndex == endIndex`) at all boundaries (especially at composition boundary $a$) must explicitly return an empty iterator (`new AttributedString("").getIterator()`) without translating endpoints across the boundary.
  2. Slices crossing composition (`beginIndex < a` and `endIndex > a`) concatenate prefix `fullText.substring(beginIndex, a)` and suffix `fullText.substring(b, endIndex + (b - a))` without exposing composed text $[a, b)$.
  3. `getTextLocation(offset)` is relative to current composed text when active composition exists (leading/trailing semantics, caret rectangle `[screenX, screenY, 2, lineHeight]`).
  4. `getLocationOffset(x, y)` strictly returns `null` for screen hits outside composed text.
- **Verification**: `DesktopAwtInputMethodRequestsSpike.java` (all 66 slices match independent oracle, empty slice at [5, 5) verified, composed geometry verified, null returns verified) and `test-mutations.mjs` (Mutation 5).

---

### Finding 5: Scoping of Gate G0 Desktop Native Host Qualification
- **Classification**:
  - **(a) Report violation**: The run-01 report marked Gate G0 Desktop as `PASS` based exclusively on a headless Java unit test with dummy screen geometry (`x = live * 10`).
  - **(d) Unresolved platform feasibility**: A headless interface implementation is a unit test, not a qualified native host. Real Compose/AWT host window embedding, live OS IME interaction, real selection, geometry, settlement, and editable accessibility remain unrun.
- **Normative Plan Correction Applied (§6.3, §8)**:
  Gate G0 Desktop is strictly reclassified as **NOT RUN / INCOMPLETE**. A passing unit spike on `InputMethodRequests` establishes the coordinate translation unit only. The native host qualification gate requires a running Compose Multiplatform / AWT desktop window with live IME input, real font geometry, settlement, and screen reader interaction.

---

### Finding 6: Vacuous Candidate Path Independence Property Test
- **Classification**:
  - **(a) Harness violation**: In run-01 `test-r14-and-properties.mjs`, `reconcileCandidateAttributes(B, cMid, defaultSeed)` was called and its return value discarded, followed by calling `reconcileCandidateAttributes(B, cFinal, defaultSeed)` with identical arguments as Path 1. This proved nothing about stateful candidate paths.
- **Repair Applied**:
  Replaced with a stateful test driving two independent `ProtocolCoordinator` instances ($coord_1$ and $coord_2$). Both start with identical baseline $B$. $coord_1$ receives a randomized multi-step detour sequence $C_{1, 1} \to C_{1, 2} \to \dots \to C_{final}$. $coord_2$ receives a completely different detour sequence $C_{2, 1} \to C_{2, 2} \to \dots \to C_{final}$. Both reach $C_{final}$.
  The resulting document text and attributes are compared unit-by-unit, and verified against an independent reference oracle.
  Separately demonstrated that when the attribution epoch resets (via partial commit), resulting attributes across the epoch boundary deliberately diverge.
- **Verification**: `test-r14-and-properties.mjs` (10,000 stateful fuzzing iterations, 10,000 oracle comparisons, zero failures).

---

### Finding 7: Commit Replacement Termination without Custom "finish" Flag
- **Classification**:
  - **(b) Specification incomplete**: §4.4 did not clarify whether standard native commit callbacks terminate composition in the absence of a custom "finish" boolean.
- **Repair Applied**:
  In real OS IMEs (e.g. Windows IME, AWT `inputMethodTextChanged`, macOS `insertText`), commits standardly arrive without a bespoke "finish" flag. The coordinator must treat any non-partial commit (`isPartial !== true`) as terminal: it transitions state to `Idle`, clears transient marked range, finalizes the composition history group, and drains queued commands.
- **Verification**: `test-coordinator-repairs.mjs` (Audit 4.1).

---

### Finding 8: Implicit Composition Initialization from Active Selection
- **Classification**:
  - **(a) Harness violation**: The run-01 coordinator defaulted to `[0, 0]` when marked range was null.
  - **(b) Specification incomplete**: §4.4 stated `[caret, caret)`, overlooking non-collapsed selections.
- **Repair Applied**:
  When a raw preedit arrives in `Idle` state without an explicit replacement range, the composition session initializes at the active logical selection `[anchor, focus]`. Non-collapsed selections are preserved as the replacement range, and pinned in `compositionBeforeSnapshot`.
- **Verification**: `test-coordinator-repairs.mjs` (Audit 4.2).

---

### Finding 9: Cancellation Erased Partial Commit Frozen Prefix (Invariant I08 & R03)
- **Classification**:
  - **(a) Harness violation**: The prior coordinator unconditionally restored `this.compositionBeforeSnapshot.text` on cancellation, wiping out already committed partial units and violating Invariant I08.
  - **(b) Specification incomplete**: §4.4 stated "Cancellation: Apply supplied replacement/deletion. Restore a baseline only when the platform explicitly supplies that behavior", but omitted the explicit distinction between cancellation with vs without prior partial commits.
- **Repair Applied (§4.4, §4.5)**:
  When cancellation arrives after one or more partial commits, the frozen prefix units remain committed in the document under Invariant I08. The uncommitted candidate text in `markedRange` is removed, a single `Composition` history group is created for the net partial commit(s), and pending commands targeting the aborted candidate are cancelled (`CompositionCancelled`).
- **Verification**: `test-coordinator-repairs.mjs` (Audit 4.7) and `test-mutations.mjs` (Mutation 6).

---

### Finding 10: Direct Commit Extent in Idle State Defaulted to Offset Zero
- **Classification**:
  - **(a) Harness violation**: In direct commit without prior composition, `this.sessionStartOffset` defaulted to 0, causing `lastSettledExtent` to resolve to `[0, commitText.length]` instead of the active selection extent.
  - **(b) Specification incomplete**: §4.5 specified extent calculation rules for 6 composition cases, but omitted direct commit in Idle state.
- **Repair Applied (§4.5)**:
  When a direct commit arrives in `Idle` state, `sessionStartOffset` initializes to the active selection anchor `Math.min(selection[0], selection[1])`, resolving extent strictly to `[sessionStart, sessionStart + commitText.length]`.
- **Verification**: `test-coordinator-repairs.mjs` (Audit 4.8) and `test-mutations.mjs` (Mutation 7).

---

### Finding 11: Missing Focus Loss and Disposal Lifecycle Methods
- **Classification**:
  - **(a) Harness violation**: The coordinator lacked explicit `onFocusLost()` and `dispose()` entry points; pending commands were not explicitly expired with distinct cancellation reasons.
  - **(b) Specification incomplete**: §4.5 stated pending commands are cancelled on focus loss/disposal, but did not define explicit lifecycle methods or cancellation outcome structures.
- **Repair Applied (§4.4, §4.5)**:
  Implemented `onFocusLost(strategy)` (cancelling pending composition commands with `FocusLost` and settling or cancelling active preedit) and `dispose()` (cancelling all pending commands with `Disposed` and closing connection).
- **Verification**: `test-coordinator-repairs.mjs` (Audit 4.10) and `test-mutations.mjs` (Mutation 8).

---

### Finding 12: Missing AWT Selected Text, Insert Position, and Unsupported Attributes Contracts
- **Classification**:
  - **(a) Harness violation**: The AWT spike did not verify `getSelectedText()`, `getInsertPositionOffset()`, `cancelLatestCommittedText()`, or requested text attributes in its test suite; vertical line boundary was inclusive on the lower edge.
  - **(b) Specification incomplete**: §6.3 omitted explicit contracts for selected text normalization, insert position offset, and unsupported text attribute queries.
- **Repair Applied (§6.3)**:
  Corrected vertical bounds check (`y < screenOriginY || y >= screenOriginY + lineHeight`); verified `getSelectedText()` across forward, collapsed, and reversed ranges; verified `getInsertPositionOffset()`; verified `cancelLatestCommittedText()` returns null; and verified that requested text attributes cleanly return empty attribute sets for unsupported keys.
- **Verification**: `DesktopAwtInputMethodRequestsSpike.java` (Test Cases 8–11).

---

## 3. Normative Plan Text Differences Summary

| Section | Paragraph / Table | Substance of Applied Change | Classification |
|---|---|---|---|
| Header | Title / Revision | Bumped revision to 4.4; noted comprehensive transition contracts, partial commit preservation, focus/disposal rules, and G0 scoping | (b) Specification completeness |
| §4.2 | Composition state | Explicit FIFO command queueing across Composing & AwaitingSettlement; preedit retention of settlement | (a) Harness fix / (b) Spec completeness |
| §4.4 | Event transition table | Full 7-column normative transition table defining exact state transitions, text/attrs/selection, pending commands, history groups, revisions, and receipts | (b) Specification completeness |
| §4.5 | Settlement semantics | Exact 7-case extent resolution (including direct commit); CompositionTarget(sessionId) matching; focus loss and disposal rules; partial commit cancellation preservation | (b) Specification completeness |
| §6.3 | Platform adapters (AWT) | Empty slice exception fix; cross-composition slice concatenation; composed-text geometry; exclusive lower bound; getSelectedText normalization; getInsertPositionOffset; unsupported attributes and cancelLatestCommittedText | (c) Specification correction / (b) Completeness / (d) Feasibility scoping |
| §8 | Phase 0 Gate G0 | Clarified G0 Desktop is NOT RUN / INCOMPLETE for native host integration | (d) Feasibility scoping |
| §9.2 | Regression cases R05 & R12 | Updated descriptions for R05 (FIFO queueing, extent, unmark, partial commit cancellation, direct commit) and R12 (full AWT InputMethodRequests contract) | (b) Specification completeness |
