# Observable Editing Contract & Semantic Gap Resolution Specification
**Audit Run**: `run-20261014-05`  
**Date**: 14 October 2026  
**Status**: Authoritative Representation-Independent Contract for Candidate Validation  

---

## 1. Scope and Design Principles

This specification defines the representation-independent contract governing text composition, settlement, cancellation, history, and command dispatch in the document editor.

Any coordinator implementation—whether using Candidate A (v4 single live-document buffer with frozen tracking arrays) or Candidate B (v5.3 separated committed-storage and piecewise segment-mapped provisional overlay)—must satisfy these identical observable behaviors.

### Three Orthogonal Facets of Input State
1. **Native Input Effect**:
   The exact requested replacement, marked range, raw selection, coordinate units, and synchronous acknowledgement.
2. **Commitment and Cancellation**:
   Which effects have become permanently committed to storage, which remain provisional, and what each cancellation operation restores.
3. **User Command Intent**:
   Which content a queued command targeted (semantic composition entity vs concrete tracked range) and how that target follows subsequent admitted edits.

---

## 2. Event Specifications & State Transitions

### 2.1 `startComposition(range: [start, end], selection?: [anchor, focus], sessionId?: string)`
- **Preconditions**: Connection is Active. `compositionState == 'Idle'`.
- **Payload**:
  - `range`: `[u, v]` where `0 <= u <= v <= len(docText)`.
  - `selection`: optional `[selStart, selEnd]`. Default: `[u, v]`.
  - `sessionId`: optional string. Default: monotonically generated session ID.
- **Observable Effects**:
  - `compositionState` becomes `'Composing'`.
  - Visible document text and attributes are identical to pre-event document.
  - `markedRange` equals exact `[u, v]`.
  - `rawSelection` equals specified selection.
  - Baseline $B_0$ captures exact attributed units of `docText[u..v)`.
  - Snapshot $S_{\text{before}}$ captures initial pre-session state.
  - `DocumentRevision` remains UNCHANGED (`docRev' == docRev`).
  - `SessionRevision` increments by 1 (`sessRev' == sessRev + 1`).
  - History: No undo entry created.
  - Return: `{ status: 'Accepted', markedRange: [u, v], selection, documentRevision, sessionRevision }`.

### 2.2 `preedit(text: string, range?: [start, end], selection?: [anchor, focus])`
- **Preconditions**: Connection is Active. `compositionState` in `{'Idle', 'Composing', 'AwaitingSettlement'}`.
- **Observable Effects**:
  - If `Idle`: opens composition implicitly at selection.
  - If `AwaitingSettlement`: resumes composition, invalidating pending settlement tokens and fences.
  - If `range` differs from current `markedRange`: retargets to `range` first before applying text replacement.
  - Reconciles candidate text against immutable epoch baseline $B_{\text{epoch}}$ using maximal common prefix/suffix.
  - Visible text is updated: `newText = docText[0..u) + text + docText[v..)`.
  - `markedRange` becomes `[u, u + text.length]`.
  - `DocumentRevision` increments iff visible text or attributes changed.
  - `SessionRevision` increments by 1.
  - History: Deferred until terminal settlement/commit.
  - Pending commands remain queued in FIFO order.

### 2.3 `partialCommit(length: number, selection?: [anchor, focus])`
- **Preconditions**: `compositionState == 'Composing'`, `markedRange != null`, `1 <= length <= len(markedText)`.
- **Observable Effects**:
  - Visible text and visible attributes are 100% IDENTICAL before and after `partialCommit`.
  - Prefix of length `length` from active candidate becomes permanently committed to document storage.
  - `DocumentRevision` remains UNCHANGED (`docRev' == docRev`).
  - `SessionRevision` increments by 1.
  - Active settlement token/fence is invalidated (candidate state changed).
  - New attribution epoch begins: baseline $B_{\text{epoch}}'$ is set to the remaining uncommitted candidate units.
  - `markedRange` becomes `[u + length, end]`.
  - Return: `{ status: 'Accepted', frozenCount, markedRange, documentRevision, sessionRevision }`.

### 2.4 `targetExpansion` / `retarget(range: [newStart, newEnd], selection?: [anchor, focus])`
- **Preconditions**: `compositionState == 'Composing'`, `0 <= newStart <= newEnd <= len(visibleText)`.
- **Observable Effects**:
  - Exact Proposal Fidelity: `markedRange` becomes exact requested `[newStart, newEnd]`. Silent clamping is strictly prohibited.
  - Visible text and visible attributes remain UNCHANGED.
  - `DocumentRevision` remains UNCHANGED.
  - `SessionRevision` increments by 1.
  - Any pending settlement token is invalidated.
  - New attribution epoch begins with baseline covering the new candidate window.
  - Previously committed prefix units are preserved.

### 2.5 `unmark(settled: boolean, selection?: [anchor, focus])`
- **Case `settled == false` (Nonterminal Unmark)**:
  - `compositionState` transitions `Composing -> AwaitingSettlement`.
  - `markedRange` becomes `null`.
  - Visible text and attributes remain in document.
  - `DocumentRevision` remains UNCHANGED.
  - `SessionRevision` increments by 1.
  - Generates an active settlement token / fence. History push is DEFERRED until terminal settlement.
  - If called when `markedRange == null` (already unmarked): REJECTED as `AlreadyUnmarked`.
- **Case `settled == true` (Terminal Unmark / EndCompositionPreservingText)**:
  - `compositionState` transitions to `Idle`.
  - `markedRange` becomes `null`.
  - All candidate text is committed permanently.
  - History: Exactly 1 `Composition` undo group is recorded iff visible text/attributes differ from pre-session snapshot.
  - Drains and executes pending commands against final committed extent.
  - `SessionRevision` increments by 1; `DocumentRevision` increments iff drained commands modify text/attrs.

### 2.6 `completeSettlement(token?: string, sessionId?: string, epoch?: number)`
- **Preconditions**: `compositionState == 'AwaitingSettlement'`.
- **Guards**:
  - If `markedRange != null`: REJECTED (`CandidateStillActive`).
  - If `epoch` provided and `epoch != connectionEpoch`: REJECTED (`StaleEpoch`).
  - If `sessionId` provided and `sessionId != activeSessionId`: REJECTED (`SessionMismatch`).
  - If `token` provided and `token != activeSettlementToken`: REJECTED (`ObsoleteSettlementToken`).
  - If no tracked settlement evidence: REJECTED (`UnknownTargetExtent`).
- **Observable Effects**:
  - `compositionState` transitions `AwaitingSettlement -> Idle`.
  - Exactly 1 `Composition` undo group recorded if net change occurred.
  - Pending commands drained against concrete settled extent in strict FIFO order.
  - Session state cleared. `SessionRevision` increments by 1.

### 2.7 `commit(text?: string, range?: [start, end], selection?: [anchor, focus])`
- **Preconditions**: Connection Active. Any composition state.
- **Observable Effects**:
  - Finalizes text permanently at `range` (or current `markedRange`, or selection).
  - Clears `markedRange = null`, transitions to `Idle`.
  - Exactly 1 `Composition` undo group recorded iff net change occurred.
  - Drains pending commands against final extent.
  - `DocumentRevision` increments iff document text/attributes changed.
  - `SessionRevision` increments by 1.

### 2.8 Cancellation Operations
1. **`nativeRemoval(range: [start, end], replacementText: string)`**:
   - Represents native IME/host excising uncommitted text or replacing with explicit range/text.
   - Replaces `docText[range[0]..range[1])` with `replacementText`.
   - Clears marked range, transitions to `Idle`.
   - Does NOT restore pre-composition snapshot; applies exact native excision.
2. **`cancel` / `cancelUncommitted` / `applicationAbort`**:
   - Cancels uncommitted provisional work.
   - Any previously committed units (from `partialCommit`) MUST survive.
   - Uncommitted provisional replacements are discarded.
   - Absorbed original units that were never overwritten are preserved.
   - If net committed changes exist: records 1 `Composition` undo group for the committed portion.
   - `CompositionTarget` commands are cancelled with outcome `{ status: 'Cancelled', reason: 'CompositionCancelled' }`.
   - `TrackedRange` commands targeting outside text remain queued or map across the rollback.

---

## 3. Resolution of Known Semantic Gaps

### 3.1 Gap 4.1: Formatting Target versus Insertion Provenance
- **Command Intent Contract**:
  - A queued formatting command targeting `ActiveComposition(sessionId)` targets the **entire semantic candidate of that session** as visible at settlement time, NOT merely the inserted characters.
  - Example 1: Existing `"cat"`, candidate `"cats"`. The target of `ActiveComposition` is `"cats"` (length 4). Both `"cat"` and `"s"` are formatted.
  - Example 2: Existing `"cat"`, unchanged candidate `"cat"`. The target is `"cat"` (length 3). All 3 units are formatted.
  - Example 3: Combining mark inserted beside base character. Formatting applies to the outward grapheme-cluster closure, intentionally covering both base character and combining mark.
  - Example 4: Disjoint retargeting. If a composition was retargeted across disjoint islands, the target consists of the set of tracked constituent segments/islands, NOT their convex hull. Intervening text between islands is NOT formatted. Outward grapheme-cluster expansion is applied per constituent island.
  - Example 5: Empty candidate result. If the composition terminates with `""`, the target extent is empty `[pos, pos)`. The command updates `typingIntent` with the format patch; `docRev` does not bump; no empty undo group is created.
  - Example 6: Cancelled composition. `ActiveComposition(sessionId)` commands are terminated with outcome `{ status: 'Cancelled', reason: 'CompositionCancelled' }`.
- **Provenance Independence**: Units already bearing the target attribute inside the target range are valid no-ops. Units strictly outside the grapheme-expanded target range remain untouched.

### 3.2 Gap 4.2: Retargeting versus Superseding Committed Content
- Retargeting (`targetExpansion` / `retarget`) is purely a candidate window update; it NEVER commits or deletes content on its own.
- Committed content created by earlier operations (such as `partialCommit`) is permanent in document storage relative to provisional cancellation.
- If a subsequent provisional candidate encompasses previously committed content, cancellation of that subsequent provisional work restores the committed text, NOT the pre-session document.
- Only an admitted operation that explicitly commits a replacement over committed coordinates supersedes committed text.
- Requested ranges are NEVER silently clamped.

### 3.3 Gap 4.3: Partial Commits and Absorbed Original Content
- **Required Trace**:
  1. Committed `"abcd"`.
  2. Replace `"b"` provisionally with `"XYZ"` -> visible `"aXYZcd"`.
  3. Expand target to include original `"c"` -> visible `"aXYZcd"`, overlay covers `"b"` (`[1, 2)`) and `"c"` (`[2, 3)`).
  4. Retarget marked window to `"Y"` only (visible index `[2, 3)`).
  5. Partially commit `"Y"` (length 1).
  6. Cancel remaining provisional work.
- **Contractual Resolution**:
  - In a linear text document, committed storage is contiguous up to the cut boundary.
  - Committing `"Y"` cuts at visible offset 3 (after `"XY"`).
  - Therefore, the preceding provisional `"X"` is ALSO committed, because an uncommitted island cannot exist before a committed cut in the same linear span.
  - The committed storage $D_c$ becomes `"aXYcd"` (length 5, replacing `"b"` with `"XY"`).
  - Original `"c"` sits in the remaining overlay mapped to $D_c[3, 4)$ as an absorbed unchanged unit.
  - Cancelling the remaining provisional work discards uncommitted `"Z"` and leaves $D_c$, preserving original `"c"`!
  - Final observable document after cancel: `"aXYcd"`. Original `"c"` survives intact.

### 3.4 Gap 4.4: Deletions with Zero Visible Length
- **Required Traces**:
  1. *Deletion at cut boundary*: Committed `"abc"`, candidate `"ac"` provisionally deletes `"b"` ($D_c[1, 2) \to \varepsilon$). Partially commit first visible character `"a"` ($p_{\text{cut}} = 1$). Cancel remainder. Deletion sits at visible offset 1. Because visible session units (`"c"`) remain after $p_{\text{cut}}$, the deletion belongs to the remaining provisional effect and is cancelled, restoring `"abc"`.
  2. *Deletion strictly before cut*: Committed `"abc"`, candidate `"ac"` deletes `"b"`. Partially commit `"ac"` ($p_{\text{cut}} = 2$). Cancel remainder. Deletion is at visible offset 1 < 2, so it is permanently committed, yielding `"ac"`.
  3. *Deletion after retargeted cut boundary*: Committed `"abcde"`, candidate `"bd"` deletes `"c"`. Retarget to `"b"` only and partially commit visible `"b"` ($p_{\text{cut}} = 1$). Deletion of `"c"` sits at visible offset 2 > $p_{\text{cut}}$. Deletion belongs to remaining provisional effect and is cancelled, restoring `"abcde"`.
- **Exact Ownership Rule**:
  - A zero-length deletion segment represents deletion of original span $[d_{\text{start}}, d_{\text{end}})$.
  - In visible coordinates, it has visible length 0 and sits at visible offset $p_{\text{deletion}}$.
  - When `partialCommit(k)` cuts at visible offset $p_{\text{cut}}$:
    * A zero-length deletion segment belongs to the **committed effect** iff $p_{\text{cut}} > p_{\text{deletion}}$ (the cut strictly passes the deletion point) OR ($p_{\text{cut}} == p_{\text{deletion}}$ AND no visible session units remain after $p_{\text{cut}}$ in the active composition session).
    * If $p_{\text{cut}} \le p_{\text{deletion}}$ while visible session units remain after $p_{\text{cut}}$: the deletion belongs to the **remaining provisional effect**!
  - In trace 1: `"a"` is visible index 0..1. The deletion of `"b"` is at visible offset 1. `"c"` is visible index 1..2.
    The cut is at visible offset 1 ($p_{\text{cut}} = 1$).
    Because $p_{\text{cut}} == p_{\text{deletion}}$ and visible unit `"c"` remains after cut, the deletion of `"b"` belongs to the **remaining provisional effect**, NOT the committed prefix!
    The committed prefix consists only of `"a"`, consuming $D_c[0, 1)$.
    When the remainder is cancelled, all remaining provisional effects are reverted, restoring `"b"` and `"c"`.
    **Expected Result**: Visible text after cancel is `"abc"`.
- **Corner Cases**:
  - Multiple consecutive deletions at same boundary: if cut $\le$ boundary while units remain, all belong to remaining effect; if cut $>$ boundary or entire session is cut, all are committed.
  - Insertion beside deletion: if cut includes insertion, preceding deletion is committed.
  - Entire candidate deleted: visible length 0. `partialCommit` with length $> 0$ is rejected (`CommittedLengthExceedsCandidate`).
  - Full partial commit ($k = \text{len}$): cut passes all deletion segments; all deletions and insertions are committed.

### 3.5 Gap 4.5: Replacement Rollback Ownership
- **Required Trace**:
  1. Original `"b"` provisionally becomes `"XYZ"`.
  2. Retarget and replace interior `"Y"` with `"UVW"`.
  3. Partially commit or cancel.
- **Contractual Resolution**:
  - The replacement of `"b"` has a single atomic rollback obligation: if uncommitted, restore `"b"`.
  - Interior edits to `"XYZ"` mutate the provisional units without duplicating or discarding the rollback obligation to `"b"`.
  - When `partialCommit` cuts through a multi-unit replacement of an atomic character `"b"`:
    The committed prefix replaces `"b"` in committed storage $D_c$. The rollback obligation to `"b"` is SATISFIED and extinguished.
    The remaining uncommitted units become a pure insertion segment at the commit boundary in the new $D_c$.
    If subsequent cancel occurs, the uncommitted tail is dropped, leaving the committed replacement intact.
    The rollback obligation is never duplicated, never lost, and has exactly one owner at every instant.

### 3.6 Gap 4.6: Attribution and Identity
- **Contractual Resolution**:
  - Detour baseline reconciliation: Within an attribution epoch, if candidate deviates (`"ab"` $\to$ `"x"` $\to$ `"ab"`), the original baseline attributes (e.g. bold `'a'`, italic `'b'`) and token identities are 100% restored.
  - Suffix epoch reset: When `'a'` is partially committed, `'a'` freezes into committed storage with its bold attribute. A new attribution epoch begins for `'b'` (italic). Suffix detour (`"b"` $\to$ `"x"` $\to$ `"b"`) restores italic `'b'`.
  - Token IDs: Deterministic IDs must be used to prove identity without relying on arbitrary random strings.

### 3.7 Gap 4.7: Settlement and Lifecycle
- **Contractual Resolution**:
  - Monotonically issued settlement tokens and fence IDs.
  - New candidate input (`preedit`, `targetExpansion`, `partialCommit`) invalidates pending settlement tokens immediately.
  - Settlement completion is rejected if candidate is active (`CandidateStillActive`), token is obsolete (`ObsoleteSettlementToken`), or session/epoch mismatches.
  - Settling never creates a new history session or replaces the original preimage snapshot.
  - Connection replacement / disposal finalizes any active composition with net change into `undoStack` before tear-down.

---
This specification is frozen and serves as the single evaluation standard for both Candidate A and Candidate B.
