### A. Verdict

**REVISIONS REQUIRED** before freezing `v6.0.0`.

* **Why `ARCHITECTURAL RECONSIDERATION REQUIRED` is not warranted:** The foundational split in v6 §1.1—a single headless Kotlin Multiplatform (KMP) semantic transaction owner factoring committed state $D_c$ and segment-mapped provisional composition overlay $\mathcal{O}$ into visible state $D_v = \Pi(D_c, \mathcal{O})$, one active native input flow per viewport (`§4.12.6`), derived platform/DOM projections, and a shared immutable `PagePlan` driving both paged preview and PDF output (`§6.5`, Annex B §10)—is sound and verified by live upstream inspection. None of the inspected Compose editors ([ComposeTextEditor](https://github.com/Darkrock-Studios/ComposeTextEditor), [Compose Rich Editor](https://github.com/MohamedRejeb/compose-rich-editor), [Cascade Editor](https://github.com/linreal/cascade-editor), or [TextKit](https://github.com/jjrodcast/TextKit)) can replace the semantic document owner without breaking cross-paragraph IME composition, raw internal-grapheme attribution (`I06`), or single-authority in-place tables (`F14`/`F15`).
* **Why `READY FOR PLAN FREEZE WITH EXPLICIT OPEN GATES` cannot yet be granted:** There are **10 concrete specification defects, cross-annex contradictions, and reuse-boundary gaps** (`F-01` through `F-10` below) inside `editor-v6-standalone.md`. Several of these will cause deterministic data loss, broken Undo stacks, or immediate KMP build/FFI failures if left unresolved before `Q0`, `RU0`, and `S0/C0/GP0` execute.

### B. Prioritized Findings

Evidence levels used below follow v6 §3.3: `INSPECTED SOURCE` (verified live against upstream repository/docs in this review), `REPORTED EVIDENCE` (taken as reported in `editor-v6-standalone.md` without claiming local test execution), and `MODEL COUNTEREXAMPLE` (step-by-step formal trace against the normative specification rules).

#### F-01 (CRITICAL — Priority 3): Queued `Undo` Before Queued `Format` in the Settlement LIFO Action Ledger Leaves an Unconsumed Ephemeral Slot and Corrupts Subsequent `Undo`

* **Section & Classification:** v6 §4.1 (`Ownership state machine`), Annex B Library Plan §4.4 (`partialCommit(k)`), §4.5 (`abortProvisional`), §4.6 (`Settlement evidence and a LIFO action ledger for Undo`, Rules 1–5) — `CONFIRMED DEFECT` (`MODEL COUNTEREXAMPLE`).
* **Violated Contracts:** `I12`, `V6-I02`, Annex B §4.6 Rules 3–4, §4.12.2.
* **Counterexample Trace:**
1. Start with committed $D_c = \text{"Hello "}$, prior history entry $H_1$ (`"" -> "Hello "`), $S_0 = \text{"Hello "}$.
2. Begin composition at offset 6: `preedit("ab")` $\implies D_v = \text{"Hello ab"}$, marked $m = [6,8)$.
3. Execute `partialCommit(1)` (`k=1`): `"a"` is accepted into $D_c$ ($D_c = \text{"Hello a"}$), residual `"b"` stays in $\mathcal{O}$ with $m = [0,1)$. No history entry is pushed yet (§4.4).
4. User queues `Undo_1` (intending to undo the composition), then clicks `Bold` (`Format(Bold)` on the active composition target, capturing session units `"a"` + `"b"`), then queues `Undo_2`, and finally triggers `abortProvisional` (or terminal settlement).
5. At settlement (§4.6 Rule 2): `"b"` is discarded, net accepted change $D_c = \text{"Hello a"} \neq S_0$ pushes actual composition history group $H_2$ (`"Hello " -> "Hello a"`). The LIFO ledger is seeded with $[H_1, H_2]$.
6. At §4.6 Rule 3 ("Drain application commands FIFO"):
* `Undo_1` drains **first**: it pops $H_2$ from the ledger and restores $S_0 = \text{"Hello "}$ (removing `"a"` from $D_c$). Ledger is now $[H_1]$.
* `Format(Bold)` drains **second**: its captured session target (`"a"`) was just removed by `Undo_1`! Per §4.7 and §4.12.2 ("An empty settled composition target cancels the composition-format action with `TargetDeleted` and one `CommandWithoutHistory` ledger credit"), `Format(Bold)` cancels and pushes `CommandWithoutHistory` onto the ledger. Ledger is now $[H_1, \text{CommandWithoutHistory}]$.
-Wait: `Undo_2` drains **third**: it pops `CommandWithoutHistory` (the slot from `Format(Bold)` that never applied because `Undo_1` already wiped its target!) instead of popping $H_1$ (`"Hello "`)! Even worse, if the user did **not** queue `Undo_2` (only `Undo_1` then `Format(Bold)`), the ledger finishes settlement with an unconsumed ephemeral slot and `Format(Bold)` silently fails because an earlier `Undo` in the same queue invalidated its basis, OR if `Format(Bold)` targeted an explicit range inside `"Hello "` and succeeded ($H_3$), `Undo_1` undid $H_2$ ("a") while `Format(Bold)` stayed applied, reversing user chronological expectation.




* **Proposed Correction:** Amend Annex B §4.6 Rules 3–4 so that: (a) queuing an `Undo` during composition acts as a **settlement barrier** for composition-targeted commands admitted *before* that `Undo` (an `Undo` admitted before `Format(CompositionTarget)` cannot retroactively delete the target of a `Format` admitted *after* the `Undo` and still credit that `Format` with an ephemeral `CommandWithoutHistory` slot); and (b) a `CommandWithoutHistory` credit is pushed **only** if there is a still-unexecuted `Undo` in the settlement queue with `undo.ticket > command.ticket`.
* **Acceptance Test:** Execute trace `D_c="Hello " -> preedit("ab") -> partialCommit(1) -> queue(Undo_1) -> queue(Format(Bold, target=Composition)) -> queue(Undo_2) -> abortProvisional()`. Assert final $D_c == \text{""}$, `Redo` stack contains `[H_2("Hello a"), H_1("Hello ")]`, and `Format(Bold)` emits `Cancelled(TargetDeletedByPriorUndo)` with zero orphan ephemeral slots.

#### F-02 (CRITICAL — Priority 3 & 6): Contradiction Between `R08` (`TableMap` Merged Cells), `F15`, and Annex B §4.11.1 / §4.11.4 Rectangular Selection & Duplicate-Flow Rejection

* **Section & Classification:** v6 §1.4 (`R08`), v6 §2.1, Feature Catalogue `F14`/`F15`, Annex B §1.1, §4.11.1, §4.11.2 (`insertTable`), §4.11.3, §4.11.4, §11.8 — `CONFIRMED DEFECT` (`INSPECTED SOURCE` + `MODEL COUNTEREXAMPLE`).
* **Violated Contracts:** `I03`, `I04`, `V6-I02`, `F15`, `F31`, Annex B §4.11.1 ("Duplicate flow discovery must reject").
* **Evidence & Counterexample Trace:**
1. In [ProseMirror/prosemirror-tables](https://github.com/ProseMirror/prosemirror-tables) (`src/tablemap.ts`, v1.8.5, `INSPECTED SOURCE`), `TableMap.map` is a `width * height` flat array where a cell with `colspan=2, rowspan=1` at row 0 repeats the **same** cell start offset at `map[0]` and `map[1]`.
2. Suppose a 2×2 table has top row merged into cell $C_{0,0}$ (`FlowId = F_0`, `colspan=2`) and bottom row $C_{1,0}$ (`F_1`), $C_{1,1}$ (`F_2`).
3. The user selects the 2×2 rectangle (`Selection.Cells(table, anchor=C_{0,0}, focus=C_{1,1})`) and starts typing Urdu (`preedit("اردو")`).
4. Annex B §4.11.4 states: *"A cell selection retains directional endpoint CellIds but enumerates its rectangle in row-major order. Bind the active native connection to the top-left flow... On the first preedit, stage a cell-clearing prelude for the whole selected rectangle and attach the ordinary structural overlay in the top-left cell."*
5. Enumerating the 2×2 grid slots $(0,0), (0,1), (1,0), (1,1)$ via `TableMap.map` yields flows $[F_0, F_0, F_1, F_2]$. Slot $(0,1)$ resolves to $F_0$ again—which either triggers §4.11.1's hard rejection (*"Duplicate flow discovery must reject before insertion into a map could conceal the duplicate"*) or treats $(0,1)$ as a "secondary cell" whose prelude clears $F_0$ after the primary overlay was attached!
6. Furthermore, when `mergeCells` (`F15`) merges $C_{0,0}$ (`F_0`) and $C_{0,1}$ (`F_01`, containing paragraph $P_2$ with a comment anchor `A_1`), Annex B §4.11.3 states: *"An explicitly deleted flow always marks affected anchors deleted."* If `mergeCells` deletes $F_{01}$, comment anchor `A_1` on surviving paragraph $P_2$ is falsely marked `deleted = true`, violating `F31` ("Anchors survive moves")! Meanwhile, Annex B §4.11.2 (`insertTable`) still contains contradictory text saying *"Nested and merged tables remain outside the fixed product scope."*


* **Proposed Correction:**
1. Remove the stale "merged tables remain outside the fixed product scope" clause in Annex B §4.11.2 (`insertTable`).
2. Specify in v6 §1.4 (`R08`) and Annex B §4.11.4 that rectangular cell enumeration over a `TableMap` `Rect` MUST deduplicate covering `CellId`/`FlowId`s in row-major top-left corner order (`TableMap.cellsInRect(rect)` semantics), with the primary active flow bound to the unique cell covering `(rect.top, rect.left)` and the cell-clearing prelude applied only to the remaining **distinct** `CellId`s in the rectangle.
3. Specify in `S0` / §11.8 that `mergeCells` executes `MoveNodes` of all non-empty blocks from absorbed cells into the surviving top-left `FlowId` **before** retiring absorbed `CellId`/`FlowId` containers, so anchors inside moved blocks follow their `NodeId` into the surviving `FlowId` under §4.11.3's block-move rule (`deleted = false`).


* **Acceptance Test:** Merge $(0,0)$ and $(0,1)$ in a 2×2 table where $(0,1)$ has Urdu text with a registered `PositionRef` anchor and a `Comment` (`F31`); verify the anchor maps to $F_{0,0}$ with `deleted == false`. Then select the 2×2 rectangle, run `preedit("الف") -> partialCommit(1) -> abortProvisional() -> Undo()`, and verify exact restoration of the merged 2×2 table without duplicate-flow rejection.

#### F-03 (HIGH — Priority 3): `partialCommit(k)` Followed by `retire/retarget` Over the Newly Accepted Prefix and `abortProvisional` Corrupts Rollback Preimage

* **Section & Classification:** v6 §4.1 (`Retarget`, `Partial commit`, `Application abort`), Annex B §4.4 (`Composition overlay, attribution epochs and partial commitment`) — `CONFIRMED DEFECT` (`MODEL COUNTEREXAMPLE`).
* **Violated Contracts:** `I08`, `I12`, `V6-I02`.
* **Counterexample Trace:**
1. Initial $D_c = \text{"abcd"}$, $S_0 = \text{"abcd"}$.
2. Replace `"bc"` ($[1,3)$) with preedit `"XYZ"` $\implies D_v = \text{"aXYZd"}$, $m = [1,4)$.
3. Execute `partialCommit(2)` (`k=2`, cut at $q = 1+2 = 3$): per §4.4, `"XY"` is frozen into $D_c$, consuming the entire replacement footprint $[1,3)$ in $D_c$ (*"When cutting a replacement segment, accepting a positive prefix consumes its entire old footprint; the residual units become an insertion at the accepted boundary"*). Thus $D_c = \text{"aXYd"}$, and $\mathcal{O}$ has a zero-footprint insertion segment `"Z"` at $D_c$ offset 3 ($m = [0,1)$, $D_v = \text{"aXYZd"}$).
4. Now the IME issues an explicit retarget within the **same** session to $D_v$ range $[1,4)$ (`"XYZ"`, reconverting the just-committed `"XY"` plus residual `"Z"`) with new preedit `"PQ"`.
5. Per §4.4: *"Explicit retarget over previously committed text creates a new rollback obligation against CURRENT Dc, not the old S0 content."* Thus the segment covering `"XY"` has rollback footprint `"XY"` in current $D_c$ (`"aXYd"`).
6. Now the user cancels the composition (`abortProvisional`). Per §4.5: *"Discard remaining overlay. Preserve accepted partial effects in Dc. Group net accepted change iff nonempty."*
7. Discarding the overlay restores current $D_c = \text{"aXYd"}$ and pushes one `Composition` history group (`S0="abcd" -> Dc="aXYd"`). That works when retargeting replaces `"XYZ"`.
8. **Now consider when step 4 is `nativeRemoval` or `partialCommit` on the retargeted `"PQ"**`: Suppose at step 4 (`D_v = "aPQd"`, where `"PQ"` replaces $D_c[1..3) = \text{"XY"}$), the IME calls `partialCommit(1)` (committing `"P"`) and then `abortProvisional` (canceling `"Q"`).
* By §4.4's rule (*"When cutting a replacement segment, accepting a positive prefix consumes its entire old footprint"*), accepting `"P"` consumes the entire $D_c$ footprint $[1,3)$ (`"XY"`)!
* Then `abortProvisional` discards residual `"Q"`, leaving $D_c = \text{"aPd"}$!
* Notice what happened: `"Y"` (which was **committed** in step 3 via `partialCommit(2)`!) was **erased** by canceling `"Q"`, violating `I08` (*"Partial commit freezes committed attributes; later suffix cancellation cannot erase that prefix"*) and v6 §4.1 (*"What must not happen: erase accepted prefix on cancel"*)!




* **Proposed Correction:** Amend Annex B §4.4 so that when an active session retargets back over a prefix that was already frozen by an earlier `partialCommit` in the same session, either: (a) the retarget is rejected by the admission classifier unless the current session first settles (`SettlementRequired`), or (b) the replacement segment's $D_c$ footprint is split 1-for-1 against the frozen prefix units during `partialCommit`, so canceling a shorter replacement suffix restores the unreplaced trailing frozen-prefix units rather than consuming the entire frozen prefix footprint on the first committed code unit.
* **Acceptance Test:** Execute `D_c="abcd" -> preedit([1,3), "XYZ") -> partialCommit(2) [freezes "XY"] -> retarget([1,4), "PQ") -> partialCommit(1) [commits "P"] -> abortProvisional()`. Assert whether step 4 is rejected as `CannotUnfreezeCommittedPrefixInSameSession` or preserves/accounts for frozen prefix semantics under `I08`.

#### F-04 (HIGH — Priority 4): Web ProseMirror Composition + Collaborative Remote Updates Deadlock Between DOM Protection (`§6.3`) and Single-Owner Materialization (`§6.4`)

* **Section & Classification:** v6 §6.3, §6.4, Annex U (`ProseMirror/prosemirror-view`), Annex B §4.12.6, §6.4, §11.4 — `CONFIRMED DEFECT` (`INSPECTED SOURCE` + `MODEL COUNTEREXAMPLE`).
* **Violated Contracts:** `V6-I01`, `V6-I04`, `I13`, `I17`, Annex B §6.4, §11.4.
* **Evidence & Counterexample Trace:**
1. First (`INSPECTED SOURCE`), note that [ProseMirror/prosemirror-view](https://github.com/prosemirror/prosemirror-view) was **archived on GitHub on 1 April 2026** (commit `ca4c78e9b56f1b164c0b3758b59d8748f11b7534` adds the forwarding link to `[https://code.haverbeke.berlin/prosemirror/prosemirror-view/](https://code.haverbeke.berlin/prosemirror/prosemirror-view/)`). In `src/input.ts`, ProseMirror tracks `view.composing` and uses `view.docView.protect()` / DOM mutation observers on the active `Text` node and its parent inline/block wrappers; any `view.updateState(newState)` that replaces or splits the composing block node or modifies ancestor wrappers aborts active browser IME composition in Chrome/Safari/Firefox.
2. Under v6 §6.3 and Annex B §6.4: *"Protect the composing Text leaf and wrapper chain from replace/reparent/split, even when edits are numerically outside the marked range."*
3. Under v6 §6.4: *"the replication engine owns convergent replicated state; the semantic owner validates/compiles local requests and publishes the canonical materialization atomically. Remote changes pass through that same materialization/validation boundary."*
4. **Trace:** User A is composing Urdu inside paragraph $P_1$ on Web (`composing = true`). Concurrently, Remote Peer B wraps $P_1$ in a blockquote (`wrapQuote`), changes $P_1$'s heading level, or splits $P_1$ 10 characters before User A's caret.
5. If the semantic owner immediately materializes Peer B's remote transaction into $D_c$ and $D_v$:
* Either it updates ProseMirror (`view.updateState`), which splits/reparents $P_1$'s DOM wrapper chain and **destroys User A's live IME composition**, violating v6 §6.3;
* Or it Withholds the DOM update from ProseMirror while advancing $D_c$ / `DocumentRevision`, which causes ProseMirror's DOM tree and positions to diverge from the semantic owner's $D_v$. Then, when User A's IME fires the next candidate `beforeinput`/`input` or `partialCommit`, ProseMirror's transaction steps carry pre-split DOM positions that no longer match $D_c$, causing the synchronous admission bridge (§6.4) to reject the noncancelable browser mutation and throw the user into fault recovery on ordinary collaborative typing!


6. Conversely, if the semantic owner buffers Peer B's update without applying it to the local CRDT replica, local `partialCommit` works, **but** if the CRDT engine (`Y.Doc`) already applied Peer B's binary packet, the CRDT's internal offsets have shifted while $D_c$'s offsets have not!


* **Proposed Correction:** Specify an explicit **Two-Queue CRDT Frontier Bridge** in v6 §6.3–§6.4 and Annex B §11.4:
1. Incoming remote binary/operation packets during local composition (`composing == true`) are placed in a **Remote Staging Queue** *before* calling `ydoc.transact` / `applyUpdate` whenever preflight inspection (or a shadow replica diff) shows they touch the active `FlowId`'s protected block or its ancestor chain.
2. Disjoint remote edits (e.g., in another block/cell/section whose DOM wrapper does not ancestor-wrap the composing block) may be applied and projected via `prosemirror-transform` `StepMap` without touching the protected DOM subtree.
3. When local `partialCommit` or terminal `commit` occurs, the local edit is applied to the local CRDT replica **first** (at its exact pre-remote-queue frontier), emitted to peers, and on terminal settlement the Remote Staging Queue is applied to the CRDT and materialized into $D_c$ in one batch.


* **Acceptance Test:** On Web (`prosemirror-view`), start an active composition in $P_1$, deliver a remote `SplitTextBlock` and `wrapQuote` on $P_1$ mid-composition, perform `partialCommit(2)`, continue preedit, and commit. Verify the DOM `Text` node identity (`===`) survives until `compositionend`, and both peers converge to identical text, quote structure, and attributes.

#### F-05 (HIGH — Priority 4): Raw Ill-Formed UTF-16 Contract (`I05` / `V6-I02`) Is Incompatible with Unescaped `Yjs` Binary Updates, `Yrs` (`y-crdt`), and `Automerge` Strings

* **Section & Classification:** v6 §2.2, §6.4 (`R11`), Annex B §2.3 (`Canonical text and attributes`), `I05`, `V6-I02` — `VALID REQUIREMENT GAP` (`INSPECTED SOURCE`).
* **Violated Contracts:** `I05`, `V6-I02`, v6 §6.4 ("If raw ill-formed UTF-16 cannot be represented, either encode it reversibly under a documented schema or block adoption; do not silently replace it").
* **Evidence (`INSPECTED SOURCE`):**
* [yjs/yjs](https://github.com/yjs/yjs) (`v13.6.33`) stores JS strings in memory, but its binary document update encoder (`lib0/encoding.writeVarString`) UTF-8 encodes string content over the wire and into `y-indexeddb`, replacing any lone UTF-16 surrogate (`0xD800..0xDFFF`) with `U+FFFD` (`\uFFFD`).
* [y-crdt/y-crdt](https://github.com/y-crdt/y-crdt) (`yrs`, Rust) and [automerge/automerge](https://github.com/automerge/automerge) (`v3.5.0`, Rust) store text chunks in Rust `String` / UTF-8 buffers. Even when configured with UTF-16 offset indexing (`OffsetKind::Utf16` in `yrs` or `TextEncoding::Utf16CodeUnit` in `automerge`), Rust strings cannot hold unpaired UTF-16 surrogates.


* **Counterexample Trace:**
1. Paragraph contains `"ا😀ب"` (`\u0627\uD83D\uDE00\u0628`).
2. Native UTF-16 deletion (`deleteSurroundingText(1, 0)` at offset 3) deletes `\uDE00`, leaving isolated high surrogate `\uD83D` in $D_c$ (`Annex B §2.3`: accepted and preserved losslessly).
3. Replicating via `Y.Text` binary sync or `yrs`/`automerge` FFI converts `\uD83D` to `U+FFFD` (`\uFFFD`). When a second native edit appends `\uDE00` or when the user undoes/round-trips, $D_c$ on Peer B has `\uFFFD` instead of `\uD83D`, violating `I05` and `V6-I02`.


* **Proposed Correction:** Specify the exact reversible CRDT encoding in v6 §6.4 now rather than leaving it open: represent every isolated surrogate code unit (`0xD800..0xDFFF`) in `Y.Text` (and `Automerge`) as a 1-unit embedded inline atom (`Y.Text.insertEmbed(index, { rawUtf16Unit: 0xD83D }, attrs)`). Because an embedded item in `Y.Text` has length `1` in `OffsetKind::Utf16`, this preserves 1-to-1 UTF-16 offset alignment between $D_c$ and `Y.Text`/`Yrs` across all platforms without custom CRDT forks.
* **Acceptance Test:** Insert `"ا😀ب"`, delete `\uDE00` via raw UTF-16 deletion on Peer A, sync binary update through `Yrs` (Rust) to Peer B, verify Peer B's $D_c$ contains exact code unit `0xD83D` at `Utf16Offset(1)` with matching attributes, and verify `Undo` on Peer A restores `😀` (`\uD83D\uDE00`) on both peers.

#### F-06 (HIGH — Priority 4): Direct Use of `Y.UndoManager` on Hierarchical Block Splits/Joins Violates `V6-I08` (Erases Concurrent Peer Text)

* **Section & Classification:** v6 §6.4 (`R11`), `V6-I08`, Annex B §11.5 (`Local Undo versus collaborative Undo`) — `CONFIRMED DEFECT` (`INSPECTED SOURCE` + `MODEL COUNTEREXAMPLE`).
* **Violated Contracts:** `V6-I08` ("Collaborative Undo cannot restore a root that erases unrelated remote work"), `I03`, Annex B §11.5.
* **Evidence & Counterexample Trace:**
1. In [yjs/yjs](https://github.com/yjs/yjs) (`src/utils/UndoManager.js`, `INSPECTED SOURCE`), `UndoManager` records each transaction's `DeleteSet` and inserted `Item` structs per tracked origin.
2. Suppose Paragraph $P_1$ (`NodeId = B1`) is represented as a `Y.XmlElement("paragraph")` (or `Y.Map`) containing `Y.Text("سلام دنیا")`.
3. User A presses `Enter` after `"سلام "` (`SplitTextBlock`). Under Annex B §2.2 ("Splits retain the left ID and allocate the right ID"), this deletes `"دنیا"` from $B_1$ and inserts a new block node $B_2$ containing `Y.Text("دنیا")`.
4. User B concurrently types `" پیاری"` inside $B_2$'s `Y.Text`, so $B_2$ now contains `"دنیا پیاری"`.
5. User A triggers collaborative `Undo`. `Y.UndoManager` undoes User A's `SplitTextBlock` transaction by **deleting the inserted `Y.XmlElement` $B_2$** (which destroys User B's child insertion `" پیاری"` inside $B_2$!) and re-inserting only `"دنیا"` into $B_1$. User B's accepted edit `" پیاری"` is permanently lost!


* **Proposed Correction:** Specify in v6 §6.4 that `Y.UndoManager` may only be used if either:
* **(Option 1 — Flat Separator Representation inside CRDT Flow):** Within each `FlowId`, represent all inline text and `BoundaryUnit` block separators inside a **single continuous `Y.Text**` per flow (mirroring the engine's own §4.3 tagged provenance projection `[TextUnits..., BoundaryUnit(rightLeafId, attrs), TextUnits...]`), so `SplitTextBlock` is simply a 1-unit `insertEmbed` of a `BoundaryUnit` into the flow's `Y.Text` and `JoinTextBlocks` is a 1-unit delete of that `BoundaryUnit`! Under this representation, User A's `SplitTextBlock` only inserted a 1-unit `BoundaryUnit`, User B's `" پیاری"` was inserted into the same flow `Y.Text`, and `Y.UndoManager` undoing User A's split simply deletes the 1-unit `BoundaryUnit`, cleanly joining `"سلام دنیا پیاری"` with zero loss of User B's work!
* **(Option 2 — Owner-Compiled Semantic Inverse):** Compile collaborative `Undo` of structural splits/joins in the semantic owner (`CollaborativeHistoryPort`) as forward semantic `JoinTextBlocks` / `SplitTextBlock` operations rather than raw CRDT subtree deletion.
* Note how **Option 1** directly reuses the engine's existing §4.3 `BoundaryUnit` tagged-tape design and makes `Y.UndoManager` work out of the box for both text and block splits/joins!


* **Acceptance Test:** Execute the two-peer trace (`P1="سلام دنیا"` $\to$ Peer A `Enter` at 5 $\to$ Peer B inserts `" پیاری"` at end of $P_2$ $\to$ Peer A `Undo`). Assert both peers converge to a single paragraph $P_1$ (`NodeId = B1`) with text `"سلام دنیا پیاری"`.

#### F-07 (HIGH — Priority 5): Dominant-Style List Marker Rule (`§5`), Intrinsic-Width Opposite-Direction List Column (`§5` / `V6-I05`), and Circular Renderer Oracles (`§3.2` / `V6-I03`)

* **Section & Classification:** v6 §3.1, §3.2, §5, `V6-I03`, `V6-I04`, `V6-I05`, Annex B `URDU-ACCEPTANCE.md` §2–§3 — `CONFIRMED DEFECT` (`MODEL COUNTEREXAMPLE`).
* **Violated Contracts:** `V6-I03`, `V6-I04`, `V6-I05`, `I16`.
* **Counterexample Traces:**
1. **Dominant-Style List Marker Reflow Oscillation (v6 §5):** v6 §5 specifies list marker typography as *"dominant raw text family/size across an item; ties use preview defaults; empty items use recorded typing seed."*
* Suppose an RTL Urdu list item has 3 UTF-16 units of 14pt Nastaliq (`"ارد"`) and 2 UTF-16 units of 28pt Latin (`"AB"`). The marker is rendered in 14pt Nastaliq.
* The user types one supplementary-plane character or emoji `😀` (`U+1F600`, 1 grapheme = **2 UTF-16 units**) at the end of `"AB"` in 28pt.
* Raw UTF-16 unit counts are now 3 (14pt) vs. 4 (28pt). Even though the paragraph has 3 graphemes of 14pt and 3 graphemes of 28pt, counting raw UTF-16 units flips the majority to 28pt! Furthermore, typing a single character at the end of a 500-word paragraph that tips a 250-vs-250 tie mutates the list marker's font family/size and first-line baseline, invalidating `ParagraphCacheKey` and causing the entire paragraph and sibling list gutter to jump vertically and horizontally mid-keystroke.


2. **Opposite-Direction Nested List Intrinsic-Width Collapse (v6 §5, `V6-I05`):** v6 §5 states: *"An opposite-direction child uses a constrained intrinsic-width column near its parent's nested edge."*
* Suppose an RTL root list (width `500pt`, right gutter `36pt`) contains a nested LTR child list item whose text is 30 words of English.
* Because 30 words of English has a `maxIntrinsicWidth` $> 1500\text{pt}$, clamping it to available width (`464pt`) makes its "intrinsic-width column" fill the entire `464pt` width—placing the LTR bullet at the **far left page margin** (`x = 0`), visually detached by `400+pt` from a short 2-word sibling LTR item right above it whose intrinsic width was only `80pt` (placed at `x = 384pt` near the right edge)! Thus two consecutive LTR sibling items in the same nested list have misaligned bullets (`x = 384pt` vs `x = 0pt`)!


3. **Circular Bidi &Nastaliq Geometry Oracles (v6 §3.2, `V6-I03`):** v6 §3.2 states *"Use backend queries as diagnostic agreement, not sole proof against the same backend's bug"*, yet no external non-Skia layout/shaping oracle is specified for `Q0`. In Nastaliq, cursive exit/entry baselines (`GPOS` `curs` feature) stack diagonally so ink bounds (`getBounds()`) routinely exceed typographical `ascent`/`descent`. Checking `selectionRects` and `caret` against Skiko's own `Paragraph` metrics without comparing against an independent `hb-shape` glyph-position trace and an independent UAX #9 (`ICU4J Bidi`) level/reorder table leaves `V6-I03` unfulfilled.


* **Proposed Correction:**
1. Replace the dominant-style list marker policy in v6 §5 with deterministic **block-level marker styling** (marker inherits the list item's block-level font family/size property, or the first character's/empty-block seed's font family/size if explicitly configured—never a dynamic majority vote across the item's text units).
2. Replace the "constrained intrinsic-width column" rule in v6 §5 with a **shared list-level or item-start gutter rule**: all sibling items at nesting depth $d$ within the same list share a single aligned marker column; an opposite-direction nested list either indents from the parent's start edge using the child list's full available width or aligns all sibling markers to a shared measured gutter, never per-item intrinsic text width.
3. Require `Q0` (`V6-1`) to validate bidi levels/reordering against `ICU4J Bidi` (or Unicode 16.0.0 `BidiTest.txt` / `BidiCharacterTest.txt`) and validate Nastaliq glyph cluster positions/ink bounds against standalone `hb-shape` output on the pinned font file.


* **Acceptance Test:**
* *Marker stability:* In a list item with `"ارد"` (14pt) + `"AB"` (28pt), append `"😀CD"` (28pt) and verify the list marker's font size and first-line baseline do not jump.
* *Mixed-direction sibling alignment:* In an RTL list, create two nested LTR child items—one with 2 words (`"Short item"`) and one with 40 words (wrapping across 2 lines)—and assert `markerRect(item1).left == markerRect(item2).left`.



#### F-08 (HIGH — Priority 2 & 6): PDF Backends (`PDFBox`, `PdfKmp`, `KitePDF`) Use Character-String Emission Rather Than Shaped-Glyph + `/ActualText` Emission Required for Nastaliq (`GP0` / `V6-I09`)

* **Section & Classification:** v6 §1.4 (`R12`, `R13`, `R14`), §6.5 (`Pagination and PDF`), `V6-I09`, Annex U, Annex B §10 — `VALID REQUIREMENT GAP` (`INSPECTED SOURCE`).
* **Violated Contracts:** `V6-I09`, `F08`, `F38`, `U4`, Annex B §10 ("paged preview, PDF export and printing must use the same immutable page plan: identical page count, line/page breaks, glyph placement").
* **Evidence (`INSPECTED SOURCE`):**
* [Apache PDFBox](https://pdfbox.apache.org/) (`v3.0.8` / `v2.0.37`) is JVM-only (cannot run on iOS or Web/Wasm) and its standard `PDPageContentStream.showText(String)` maps Unicode characters through the font's `cmap` without HarfBuzz `GSUB`/`GPOS` cursive attachment, breaking Urdu/Nastaliq shaping unless raw GIDs and positioned matrices are emitted manually.
* [ConaMobileDev/PdfKmp](https://github.com/ConaMobileDev/PdfKmp) (`9985a335...`) and [yuroyami/KitePDF](https://github.com/yuroyami/KitePDF) (`d177c784...`, `v0.13.0`, Apache-2.0) expose character-string `showText(String)` APIs (`PdfBuilder().text(...)` / `showText(...)`). If `PagePlan` passes pre-broken strings to `showText`, the PDF writer re-maps characters without HarfBuzz's 2D Nastaliq $(dx, dy)$ cursive offsets and mark positioning—producing broken, unjoined, or overlapping Nastaliq glyphs that do not match the Skia preview!
* Conversely, v6 §6.5 notes that converting glyphs to vector outlines plus an invisible text layer failed reading-order, diacritic, and selection-box checks.


* **Proposed Correction:** Specify in v6 §6.5 and `R12`/`R14` that the PDF backend **must not** call character-level `showText(String)` on Urdu/shaped text. Instead, `GP0` must evaluate **Skia's built-in `SkPDF` document backend** (already linked in Skiko/Skia across Desktop, Android, iOS, and Wasm) against **PDFBox/KitePDF raw GID stream emission**, under this exact contract:
1. Emit exact HarfBuzz/Skia **Glyph IDs (GIDs)** at their `PagePlan` `(x, y)` glyph positions inside a `CIDFontType2` / `Identity-H` font subset.
2. Populate the font's `/ToUnicode` CMap for $1$-to-$1$ and $M$-to-$1$ ligatures, and emit ISO 32000-1 §14.9.4 **`/Span << /ActualText (FEFF...) >> BDC ... EMC`** around every multi-glyph or complex Nastaliq cluster using the cluster's exact UTF-16 source slice from `PagePlan`. (In Skia `SkPDF`, this requires passing both the UTF-8 source text and the `clusters` array into `SkTextBlobBuilder::allocRunTextPos`—omitting those arrays causes `SkPDF` to omit `/ActualText` and break text extraction!)


* **Acceptance Test (`GP0`):** Export a 1-page `PagePlan` containing `"مُحَمَّد — پاکستان ۱۲۳ — English"` in Nastaliq to PDF, extract logical text via `PDFBox` `PDFTextStripper` (and `pdftotext`), and assert exact UTF-16 code-unit equality with $D_c$, glyph placement $\le 0.25\text{ pt}$ against preview rasterization, and non-zero bounding boxes on text selection in Acrobat/Chrome PDF viewer.

#### F-09 (MEDIUM — Priority 6): Unbounded Memory Retention in Durable Provisional Recovery Drafts & Quarantined Proposals (`§7`, Annex B §4.10 / §7.1)

* **Section & Classification:** v6 §7, `V6-I07`, Annex B §4.10, §4.12.5, §7.1 — `VALID REQUIREMENT GAP` (`MODEL COUNTEREXAMPLE`).
* **Violated Contracts:** `V6-I07`, Annex B §4.10, §8 Phase 8 (`<= 128 MiB` core + bounded history ceiling).
* **Counterexample Trace:**
1. Annex B §7.1 specifies: *"Recorded state. Store revisions, active flow/selection/typing metadata, composition-active flag, complete canonical Dc and Dv (or equivalent lossless deltas), and unresolved recovery drafts... Unresolved drafts survive later autosave/checkpoint cycles. Neither a new ordinary snapshot nor old-journal cleanup discards them."*
2. Neither §4.10 nor §4.12.5 (`pendingBytes` = 8 MiB) includes a byte ceiling or count limit for retained unresolved `recoveryDrafts` or `Fault(recoveryRecord)` quarantined proposals (`V6-I07`).
3. If a 1,000,000-unit document experiences 4 interrupted sessions or noncancelable DOM quarantine faults before the user dismisses the recovery banner, retaining 4 full `Dc` + `Dv` copies in memory consumes $>100\text{ MiB}$, blowing past the 128 MiB memory budget or causing OOM on mobile targets.


* **Proposed Correction:** Add a dedicated `Recovery and Quarantine Pool` row to the §4.10 limit table:
* Persist recovery drafts as compact **segment-overlay deltas** `(baseDurableSeq, basis, overlaySegments, cellPreludeIds, quarantinePayload)` rather than full duplicated $D_c$ and $D_v$ document trees.
* Cap in-memory retained recovery/quarantine metadata at **at most 4 MiB** (keeping only lightweight draft headers/summaries in memory while storing the draft payload on disk/IndexedDB until `recoverDraft(draftId)` or export is explicitly invoked), and cap on-disk unresolved drafts per document at **at most 16 drafts / 64 MiB** with FIFO archival warning.


* **Acceptance Test:** Trigger 10 sequential crash-recovery cycles with active cross-paragraph composition on a 100,000-unit document without dismissing prior drafts; assert in-memory retained draft metadata stays $\le 4\text{ MiB}$ while all 10 drafts remain inspectable and applicable from disk.

#### F-10 (MEDIUM — Priority 1, 2, 7 & 8): Upstream Toolchain ABI Mismatch (`Kotlin 2.2.20` vs `2.3.21`/`2.4.x`), Stale Archive URLs, and Unpinned License/Release Metadata in Annex U

* **Section & Classification:** v6 §1.3, §1.4, §6.1, §8, Annex U — `UNSUPPORTED CLAIM` / `VALID REQUIREMENT GAP` (`INSPECTED SOURCE`).
* **Violated Contracts:** `V6-I06`, v6 §1.4 ADR rules, Annex B §8 Phase 0.
* **Evidence (`INSPECTED SOURCE`):**
1. **Kotlin KMP ABI Incompatibility:** v6 §6.1 pins the repository's Desktop baseline at **`Kotlin 2.2.20`, `Compose 1.12.1`, `Skiko 0.150.1**`, and Annex B §8 explicitly dismisses `Kotlin 2.4.20` and `Unicode 18.0` as unverified assertions. However, live inspection of the exact commits pinned in Annex U on 19 October 2026 shows:
* [MohamedRejeb/compose-rich-editor](https://github.com/MohamedRejeb/compose-rich-editor) (`b972d50f...`, `v1.2.0`) compiles against **Kotlin `2.4.10**` and Compose `1.11.1`/`1.12.1`.
* [linreal/cascade-editor](https://github.com/linreal/cascade-editor) (`29c4d483...`, `v1.9.3`) compiles against **Kotlin `2.3.21**` and Compose `1.11.1`.
* [yuroyami/KitePDF](https://github.com/yuroyami/KitePDF) (`d177c784...`, `v0.13.0`) compiles against **Kotlin `2.4.21**` and Compose `1.13.0-alpha02`.
* [Unicode Standard Annex #41](https://www.unicode.org/reports/tr41/) (`tr29`/`tr9`) is at **Version 18.0** as of October 2026.
* Attempting to consume released Maven artifacts compiled with Kotlin `2.3.21` or `2.4.x` inside a `Kotlin 2.2.20` build fails immediately with an incompatible Kotlin metadata/klib ABI version error!


2. **Archived Upstream Repository:** [ProseMirror/prosemirror-view](https://github.com/prosemirror/prosemirror-view) was archived on GitHub on 1 April 2026 (`ca4c78e` is a forwarding commit to `https://code.haverbeke.berlin/prosemirror/prosemirror-view/`). Annex U pins `ca4c78e` without noting the repository migration or retrieving the npm release version.
3. **Missing License & Release Metadata in Annex U:** Annex U lists `Release metadata: not retrieved` and `License evidence: not freshly resolved` for [automerge/automerge](https://github.com/automerge/automerge) (live check confirms **MIT license**, latest release **`v3.5.0`**) and [yuroyami/KitePDF](https://github.com/yuroyami/KitePDF) (live check confirms **Apache-2.0 license**, latest release **`v0.13.0`**, plus a mandatory `NOTICE` file covering bundled third-party fonts, glyph lists, CMaps, encoding tables, and hyphenation patterns).


* **Proposed Correction:**
1. Update Annex U with the verified release tags (`Automerge v3.5.0` MIT, `KitePDF v0.13.0` Apache-2.0 + `NOTICE` obligations) and record `code.haverbeke.berlin/prosemirror/prosemirror-view` as the live upstream alongside the npm release pin.
2. Add an explicit **Toolchain Alignment Rule** in v6 §6.1: source-level adaptation (`ADAPT`) can target the project's `Kotlin 2.2.20` toolchain, whereas binary dependency evaluation (`EVALUATE` for `KitePDF 0.13.0`, `Cascade 1.9.3`, or `ComposeTextEditor 3.1.1`) requires either aligning the test harness Kotlin compiler to $\ge$ the dependency's klib ABI version or building the donor from pinned source.
3. Unbundle `V6-3 (S0+C0+GP0)` in v6 §8 into **`V6-3a (S0+C0-Model)`** (co-deciding the hierarchical/CRDT block-boundary and merged-cell representation so `F-02` and `F-06` are settled together) and **`V6-3b (GP0)`** (running in parallel on the `PagePlan` $\to$ `SkPDF`/GID + `/ActualText` bridge).


* **Acceptance Test:** `python planning/build_v6_docs.py` verifies all Annex U license/release fields are populated without `not freshly resolved` placeholders for candidate dependencies, and Gradle dependency resolution passes in the `RU0`/`GP0` spike modules.

### C. Concrete Reuse Decision Table

The table below audits every proposed component across Priorities 1 and 2, separating **Direct Dependency**, **Source/Algorithm Adaptation**, **Reference-Only**, and **Rejected** paths based on inspected upstream sources.

| Component / Upstream | Inspected Version & Commit | Ownership Assumptions in Upstream | Recommended Reuse Mode | Exact Files / APIs to Reuse or Adapt | Why Not More / Why Not Less |
| --- | --- | --- | --- | --- | --- |
| **[Compose/Skiko/Skia](https://skia.org/docs/user/modules/)** (`R01`) | CMP `1.12.1` / Skiko `0.150.1` | Stateless paragraph shaping (`SkParagraph`/HarfBuzz/ICU) and canvas draw calls | **DIRECT DEPENDENCY** (Native layout, shaping, rendering, and `SkPDF` candidate) | `androidx.compose.ui.text.Paragraph`, `org.jetbrains.skia.paragraph.*`, `org.jetbrains.skia.Document.makePDF` | Reimplementing OpenType Nastaliq shaping (`GSUB`/`GPOS`) or bidi in Kotlin is high-risk waste; however, Skia's `Paragraph` cannot own document state or cross-block composition. |
| **[Compose Foundation Text Input](https://developer.android.com/reference/kotlin/androidx/compose/foundation/text/input/TextFieldBuffer)** (`R02`) | `1.12.1` (`TextFieldState`, `TextFieldBuffer`, `ExpandPolicy`, `TrackedRange`) | Single-buffer `TextFieldState` owns internal gap buffer, undo history, and `OutputTransformation` | **REFERENCE ONLY** (for main editor); **DIRECT DEPENDENCY** only for app chrome inputs (dialogs, search, comments) | `BasicTextField(state)` for search/dialogs/comment boxes; reference `ExpandPolicy` semantics for test comparison | `TextFieldState` clears composition on style changes in tested Desktop paths, cannot span hierarchical flows/tables, and hides overlapping span connectivity (`§5.1`). |
| **[Darkrock-Studios/ComposeTextEditor](https://github.com/Darkrock-Studios/ComposeTextEditor)** (`R03`) | `v3.1.1` (`6f0a433e42169ad547ca7a5497ca30f99feadf55`, MIT) | Mutates its own line/char `TextEditorState` and internal Undo manager directly from AWT/Compose callbacks | **ADAPT (Source-level, with MIT notice)** | Adapt AWT coordinate/hit-info translation patterns from `SkikoTextEditorInputMethodRequest.kt` and pointer-affinity cases from `PointerAffinityTest.kt`; **do not** adopt `ImeEditLogic.kt` state mutations | `ImeEditLogic.kt` mutates paragraph lists in place and lacks $D_c/\mathcal{O}$ segment overlay, attribution epochs, and cross-container structural compilation (`§4.3`). |
| **[MohamedRejeb/compose-rich-editor](https://github.com/MohamedRejeb/compose-rich-editor)** (`R04`) | `v1.2.0` (`b972d50f046615fcc96bc9bbac44e6e15eabdea7`, Apache-2.0) | `RichTextState` wraps a `BasicTextField` over `List<RichParagraph>` (`RichTextDocument.kt`) | **REFERENCE ONLY** (Downgrade from `EVALUATE` for codecs) | Inspect `RichParagraph.kt` and trigger UX patterns (`@mention`/`/slash`) for `F41` reference only | Structural list bugs (e.g. issue `#858`) and destructive span merging violate `I06`; [Cascade Editor](https://github.com/linreal/cascade-editor) has a strictly superior report-bearing HTML/Markdown codec architecture. |
| **[linreal/cascade-editor](https://github.com/linreal/cascade-editor)** (`R05`) | `v1.9.3` (`29c4d4835aca9b6391fecd28b58cb5213d7a5d1d`, MIT) | Per-block `TextFieldState` inside a `LazyColumn`; native selection cannot cross blocks; flat `0..5` outline indentation | **ADAPT (Codec & iOS packaging patterns, with MIT notice)**; **REJECT** for native surface owner | Adapt report-bearing codec design (`HtmlProfile`, `MarkdownSchema.analyze()`, structured conversion warnings) and iOS XCFramework/SPM packaging scripts (`distribution/swift-package`) | Cannot replace the editor core because per-block `TextFieldState` prohibits cross-paragraph native selection/IME composition (`§1.1`, `§4.3`), and has no tables or pagination. |
| **[jjrodcast/TextKit](https://github.com/jjrodcast/TextKit)** (`R06`) | `1.0.0-rc02` (`8163e757a379add25c8d2f7f1f89b30673cef3aa`, MIT) | `BasicTextField` with rope piece-table; tables are `EmbedTypes.Table` one-line placeholders edited in a popup (`TextKitEmbedPopup`) with separate table Undo | **REFERENCE ONLY** | Study `TextKitEditableTable` UI affordances for cell merge/split menus only | Popup-only table editing with a separate Undo stack violates `F14`, `F15`, `V6-I01`, and in-place paginated table editing (`GP0`). |
| **[ProseMirror](https://prosemirror.net/docs/ref/) (`view` / `model` / `transform`)** (`R07`) | [prosemirror-view](https://github.com/prosemirror/prosemirror-view) `ca4c78e` (note: moved to `code.haverbeke.berlin`), `prosemirror-transform` `662b7a9` (MIT) | `EditorState` owns immutable tree + step history; `EditorView` (`src/input.ts`) manages contentEditable DOM and composition | **DIRECT DEPENDENCY (Web target only)** via controlled projection bridge (`§6.3`) | `EditorView`, `EditorState`, `Transaction`, `StepMap` (`src/map.ts`), `src/domcoords.ts` (disable `prosemirror-history` and `prosemirror-collab`) | Building a bespoke contentEditable browser IME/selection/accessibility engine from scratch is massive unnecessary custom work; ProseMirror must be used strictly as the Web DOM view/input adapter under the `F-04` staging bridge. |
| **[ProseMirror/prosemirror-tables](https://github.com/ProseMirror/prosemirror-tables)** (`R08`) | `v1.8.5` (`eb522f25959a1e4515a4ff5ce7e3a939f19c55e9`, MIT) | `TableMap` (`width`, `height`, flat `map` array) maps grid `(row, col)` to relative ProseMirror node offsets | **ADAPT (Algorithm + tests into `editor-core` Kotlin, with MIT notice)**; **DIRECT DEPENDENCY** on Web | Port `TableMap` (`rectBetween`, `cellsInRect`, `nextCell`, `colCount`, `fitSlice`) from `src/tablemap.ts` to index `(RowId, CellId, FlowId)` instead of relative character offsets | `TableMap` already solves `rowspan`/`colspan` rectangular closure, ragged/overlapping cell detection, and directional navigation; porting ~250 LOC of pure grid math with `CellId` deduplication (`F-02`) saves weeks of custom table geometry bugs. |
| **[facebook/lexical](https://github.com/facebook/lexical)** (`R09`) | `v0.52.0` (`3ac4eb86e55b85672b9a49fe74b13ee07f379031`, MIT) | Double-buffered mutable-during-update `EditorState` reconciled directly to browser DOM | **REFERENCE ONLY** | Differential browser IME/selection test cases from `LexicalSelection.ts` | Mixing Lexical and ProseMirror on Web is redundant; Lexical's DOM-coupled state machine does not fit native Compose targets. |
| **[Unicode 16.0.0 Data](https://www.unicode.org/reports/tr41/) & [ICU Boundary Analysis**](https://unicode-org.github.io/icu/userguide/boundaryanalysis/) (`R10`) | Unicode 16.0.0 tables (pinned); ICU4J (Desktop/Android) / `NSString`+ICU (iOS) / `Intl.Segmenter` (Web) | Standard UAX #29 / UAX #9 tables and dictionary word-break iterators | **DIRECT DEPENDENCY** (Official Unicode data files + platform ICU word-break with versioned wrapper) | Pinned Unicode 16.0.0 `GraphemeBreakProperty` + `Indic_Conjunct_Break` tables in `editor-unicode`; ICU `BreakIterator` for Thai/CJK/Urdu word navigation | Grapheme segmentation must be deterministic across all 4 targets (`I05`, `§4.12.4`), whereas dictionary word-break should reuse ICU rather than shipping custom CJK/Thai dictionaries. |
| **[Yjs](https://github.com/yjs/yjs) / [Yrs (`y-crdt`)](https://github.com/y-crdt/y-crdt) vs [Automerge**](https://github.com/automerge/automerge) (`R11`) | Yjs `v13.6.33` (`47a4940`), Yrs `ac33960` (`ykt`/`yffi`/`ywasm`), Automerge `v3.5.0` (`f4b14f0`, MIT) | Replicated CRDT document (`Y.Doc` / `Automerge`) owns convergent state and `Y.UndoManager` (`src/utils/UndoManager.js`) | **EVALUATE for Direct Dependency in `C0**` (Prefer **Yjs/Yrs** with `OffsetKind::Utf16` + `F-05` inline-atom surrogate escape + `F-06` flat-flow `BoundaryUnit` mapping) | `yjs` on Web, `y-crdt` (`yrs`/`ykt` C-FFI/JNI) on Android/iOS/Desktop; `Y.UndoManager` scoped to flow `Y.Text` with `BoundaryUnit` embeds | Never invent a custom CRDT. Using `Y.Text` per flow with embedded `BoundaryUnit` items and embedded raw-surrogate atoms aligns Yjs/Yrs directly with the engine's §4.3 tagged provenance model and satisfies `V6-I08`. |
| **Skia `SkPDF` / [Apache PDFBox](https://pdfbox.apache.org/) / [KitePDF**](https://github.com/yuroyami/KitePDF) (`R12`, `R14`) | Skia `SkPDF` (in Skiko `0.150.1`); PDFBox `v3.0.8` (Apache-2.0); KitePDF `v0.13.0` (`d177c78`, Apache-2.0) | Skia `SkPDF` consumes `SkCanvas`/`SkTextBlob`; PDFBox is JVM-only; KitePDF is pure KMP (`commonMain`) | **EVALUATE in `GP0**`: **1st choice:** Skia `SkPDF` with cluster+UTF-8 `SkTextBlob` (`/ActualText`); **2nd choice:** PDFBox (JVM) / KitePDF (KMP) extended with raw GID + `/ToUnicode` + `/ActualText` emission | `org.jetbrains.skia.Document.makePDF`, `TextBlobBuilderRunHandler` (with cluster+text arrays); `PDFBox` `PDFTextStripper` as the independent verification oracle | Neither `PdfKmp` (`R12`) nor `KmPDF` (`R13`) can emit shaped Nastaliq GIDs with `/ActualText` semantic spans; Skia `SkPDF` is already bundled on all Skiko targets and avoids a second font subsetter if cluster/text buffers are populated (`F-08`). |

### D. Exact Specification Edits for Genuine Gaps

Apply the following 7 surgical edits to `editor-v6-standalone.md` (and `planning/v6-core.md`) before plan freeze:

1. **Edit 1 — Fix LIFO Action Ledger Ordering When `Undo` Precedes `Format` in Settlement Queue (Annex B §4.6, Rules 1 & 3, and v6 §4.1):**
* *Replace Annex B §4.6 Rule 3 with:*
> "3. Drain application commands FIFO. Every admitted history-producing command contributes either its actual new history ID or one ephemeral `CommandWithoutHistory` slot if cancelled/rejected/no-op, **provided that** a composition-targeted command whose target was removed by an earlier `Undo` executed in the *same* settlement drainage emits `Cancelled(TargetUndoneInSameBatch)` and pushes **no** `CommandWithoutHistory` slot. An ephemeral `CommandWithoutHistory` slot is pushed only when an unexecuted `Undo` with `undo.ticket > command.ticket` remains in the settlement queue."




2. **Edit 2 — Reconcile Merged-Cell Identity, `TableMap` Deduplication, and Anchor Survival (`v6 §1.4 R08`, Annex B §4.11.2, §4.11.3, §4.11.4, §11.8):**
* *In Annex B §4.11.2 (`insertTable`), delete:* `"Nested and merged tables remain outside the fixed product scope."` and replace with: `"Nested tables remain an open owner scope decision; merged/split cells (`F15`) are governed by `S0`and the`TableMap` (`R08`) grid contract."`
* *In Annex B §4.11.4, append to paragraph 1:*
> "When a table contains merged cells (`rowspan > 1` or `colspan > 1`), rectangular enumeration over `TableMap` MUST deduplicate covering `CellId`/`FlowId`s in row-major top-left corner order (`cellsInRect`). The primary native connection binds to the unique cell covering `(rect.top, rect.left)`, and the cell-clearing prelude stages clears only for the remaining distinct `CellId`s."


* *In Annex B §11.8, append to paragraph 1:*
> "`mergeCells` moves all non-empty blocks from absorbed cells into the surviving top-left cell's `FlowId` via `MoveNodes` before removing absorbed cell flows, so `PositionRef` and `Comment` anchors inside absorbed cells follow their moved `NodeId` into the surviving `FlowId` with `deleted = false`."




3. **Edit 3 — Protect Frozen `partialCommit` Prefixes from Same-Session Retarget Erasure (Annex B §4.4):**
* *In Annex B §4.4, replace `"Explicit retarget over previously committed text creates a new rollback obligation against CURRENT Dc, not the old S0 content."` with:*
> "Explicit retarget over previously committed text outside the current session's frozen partial-commit prefix creates a new rollback obligation against CURRENT `Dc`. Within the same unsettled session, a retarget that overlaps units already frozen by an earlier `partialCommit` MUST either reject with `SettlementRequired` or preserve 1-for-1 unit rollback footprints for the frozen prefix so that a subsequent partial commit + `abortProvisional` can never erase previously frozen prefix units (`I08`)."




4. **Edit 4 — Specify the Two-Queue CRDT Frontier Bridge During Active Composition and Reversible Raw-UTF-16 / `BoundaryUnit` CRDT Mapping (`v6 §6.3`, `v6 §6.4`, Annex B §11.4, §11.5):**
* *In v6 §6.4, add normative requirements:*
> "- **Raw UTF-16 Escape in CRDT:** Represent any isolated UTF-16 surrogate unit (`0xD800..0xDFFF`) inside `Y.Text` / `Yrs` / `Automerge` as a 1-unit inline embed `{ rawUtf16Unit: Int, attrs: Map }` so `OffsetKind::Utf16` offsets remain 1-to-1 with `Dc` without UTF-8 replacement loss (`I05`, `V6-I02`).
> * **Structural Split/Join in CRDT (`V6-I08`):** Represent per-flow inline content and block boundaries (`BoundaryUnit`) in a single flow sequence (or compile collaborative `Undo` of `SplitTextBlock`/`JoinTextBlocks` as forward semantic join/split commands) so undoing a split via `Y.UndoManager` deletes only the `BoundaryUnit` separator and never deletes a peer's concurrent text inside the right-hand block.
> * **Composing-DOM & CRDT Staging Queue:** While a flow has an active composition (`composing == true`), remote CRDT updates whose target intersects the composing block or its protected DOM wrapper chain are held in a per-document **Remote Staging Queue** prior to local CRDT application. Local `partialCommit` and terminal `commit` apply against the pre-queue CRDT frontier first; staged remote updates apply and materialize into `Dc` immediately upon qualified settlement (or trigger `V6-I07` draft recovery if the staging queue exceeds 64 operations)."
> 
> 




5. **Edit 5 — Replace Dominant-Style List Marker Vote and Intrinsic-Width Opposite-Direction List Columns (`v6 §5`, `V6-I05`):**
* *In v6 §5, replace paragraphs 3 and 4 with:*
> "List marker typography derives deterministically from the list item's block-level paragraph typography attributes (or the item's empty-block typing seed when empty), ignoring decorative inline spans and links. It MUST NOT recompute via a dynamic majority vote across UTF-16 text units, which causes mid-typing baseline jumps and surrogate-count skew.
> Numeric markers share the first line's alphabetic baseline; bullets use a separate optical-center rule. Root list direction anchors nesting indentation (`paddingStart` in parent list direction); each item's resolved direction controls marker side, shaping, and Start/End text alignment across the full remaining item column width. All sibling items at the same nesting level in a list share a measured marker gutter; do not shrink opposite-direction children to per-item intrinsic text width."




6. **Edit 6 — Specify Positioned-GID + `/ToUnicode` + `/ActualText` PDF Emission Contract (`v6 §6.5`, `V6-I09`):**
* *In v6 §6.5, replace paragraph 2 with:*
> "A PDF backend must not pass shaped Urdu/mixed strings to character-level `showText(String)` APIs that re-map code points through nominal `cmap` tables without HarfBuzz `GSUB`/`GPOS` 2D glyph offsets. `GP0` evaluates (1) Skia's native `SkPDF` backend with `SkTextBlob` runs populated with exact `PagePlan` glyph IDs, `(x, y)` positions, cluster indices, and UTF-8 source text, and (2) `PDFBox` (JVM) / `KitePDF` (KMP) raw positioned-GID stream emission with `/ToUnicode` CMaps and ISO 32000-1 §14.9.4 `/Span << /ActualText (FEFF...) >> BDC ... EMC` spans for complex/multi-glyph Nastaliq clusters."




7. **Edit 7 — Bound Recovery/Quarantine Memory Pool (`Annex B §4.10`, `§7.1`) and Reconcile Toolchain/Annex U Metadata (`v6 §6.1`, `Annex U`):**
* *In Annex B §4.10 table, add row:*
> "`Recovery drafts and quarantined proposals` | Store in-memory headers/deltas only, capped at **4 MiB** (at most 16 unresolved drafts / 64 MiB on disk per document, stored as segment deltas against their base checkpoint sequence rather than duplicated full `Dc`/`Dv` trees)."


* *In Annex U and v6 §6.1:* Populate `Automerge v3.5.0` (MIT), `KitePDF v0.13.0` (Apache-2.0 + `NOTICE`), and `prosemirror-view` (`code.haverbeke.berlin` upstream mirror note), and add a `Kotlin ABI Compatibility Rule` noting that binary dependencies built with Kotlin `2.3.x`/`2.4.x` cannot be linked into a `Kotlin 2.2.20` build without compiler alignment or source builds.



### E. Bounded Experiments and Stop Conditions

Each experiment is capped at **at most two candidate paths**, uses one shared fixture corpus, and has an explicit hard stop condition:

| Experiment ID | Hypothesis Tested | Candidates (Max 2) | Exact Fixture & Oracles | Hard Stop Condition |
| --- | --- | --- | --- | --- |
| **EXP-Q0** (`V6-1`) | The current prototype segmenter, bidi caret resolver, and Nastaliq layout satisfy `V6-I03`–`V6-I05` on real complex clusters (not just `stops[1]..stops[2]` space characters). | **Path 1:** Existing desktop layout + edited `EditorCore` cluster/marker rules (`Edit 5`). **Path 2:** `ICU4J Bidi` + `hb-shape` diagnostic adapter if Skiko `Paragraph` misreports RTL/LTR seam carets. | 12-script corpus targeting actual cluster interiors (Urdu `مُحَمَّد`, ZWNJ, ZWJ emoji `👩‍💻`, Indic conjuncts, RTL/LTR seams) with `Backspace`, `Delete`, `Format`, `Undo`, `Save/Open`. Oracle: Unicode 16.0.0 `GraphemeBreakTest.txt`, `BidiCharacterTest.txt`, and `hb-shape` JSON glyph positions. | Stop as soon as all 12 target substrings and RTL/LTR seam hit-tests pass against the independent oracles, OR after 1 repair cycle by recording any Skiko native bug as a tracked `G4` blocker. |
| **EXP-RU0-Input** (`V6-2`) | Adapting [ComposeTextEditor](https://github.com/Darkrock-Studios/ComposeTextEditor) (`6f0a433e`) `SkikoTextEditorInputMethodRequest.kt` + `PointerAffinityTest.kt` into our `InputProposal` contract requires less code and passes `R12` AWT boundary rules. | **Path 1:** Adapted `SkikoTextEditorInputMethodRequest` slice emitting immutable `InputProposal`. **Path 2:** Existing prototype AWT `InputMethodRequests` adapter. | Run the 9 normative AWT rules from Annex B §6.3 (`R12`: empty slice `[a,a)`, cross-composition slice, `trailing(-1)` hit info, `getInsertPositionOffset`, zero-thickness caret rect) + live Windows Urdu/IME input. | Stop after running the `R12` + live IME suite once on both paths (<3 days). Reject Path 1 immediately if decoupling `TextEditorState` requires $>300$ LOC of glue. |
| **EXP-RU0-Web** (`V6-2`) | [ProseMirror](https://prosemirror.net/docs/ref/) (`prosemirror-view` `ca4c78e`) can project $D_v$ with synchronous owner admission and the `F-04` staging queue without losing active browser composition. | **Path 1:** `prosemirror-view` controlled projection with `composingNodeSet` protection + delayed mark ledger (`§6.4`). **Path 2:** Existing bespoke semantic-DOM adapter. | Single flow with two paragraphs, Urdu + Pinyin/Japanese IME composition across a bold/link boundary, plus a simulated concurrent split/wrap in the same paragraph (`F-03`). | Stop and fail Path 1 immediately if `prosemirror-view` replaces the active DOM `Text` node (`node1 !== node2`) or drops an IME candidate during Chrome/Firefox/Safari composition tests. |
| **EXP-S0-C0** (`V6-3a`) | A single per-flow `Y.Text` with embedded `BoundaryUnit` items and embedded raw-surrogate (`0xD800..0xDFFF`) atoms in [Yjs](https://github.com/yjs/yjs) (`v13.6.33`) / [Yrs](https://github.com/y-crdt/y-crdt) (`ac33960`) satisfies `I05`, `V6-I02`, and `V6-I08`, while ported `TableMap` (`eb522f2`) satisfies `F15`. | **Path 1:** `Yjs`/`Yrs` (`OffsetKind::Utf16`) with flow-level `Y.Text` + `BoundaryUnit` & raw-surrogate embeds + `Y.UndoManager`. **Path 2:** [Automerge](https://github.com/automerge/automerge) (`v3.5.0`, `TextEncoding::Utf16CodeUnit`) with owner-compiled semantic Undo. | Run the 12 named `C0` traces (including `F-02` merged-cell composition/Undo, `F-03` remote edit during composition, `F-04` lone surrogate sync, and `F-05` Peer A `Enter` split + Peer B text insert + Peer A `Undo`) + 20 deterministic fuzz seeds. | Stop once one candidate passes all 12 named traces and 20 seeds with zero divergent states or lost peer text. Do not evaluate a third CRDT. |
| **EXP-GP0** (`V6-3b`) | A single immutable `PagePlan` can drive both Compose paged preview and searchable/selectable Nastaliq PDF export with $\le 0.25\text{ pt}$ glyph agreement and exact logical text extraction (`V6-I09`). | **Path 1:** Skia `SkPDF` (`Document.makePDF`) fed by `SkTextBlob` with explicit GIDs, `(x,y)` positions, `clusters`, and UTF-8 text (`/ActualText`). **Path 2:** [Apache PDFBox](https://pdfbox.apache.org/) (`v3.0.8`) / [KitePDF](https://github.com/yuroyami/KitePDF) (`v0.13.0`) raw GID stream + `/ToUnicode` + `/ActualText` `BDC`/`EMC` writer. | 3-page fixture: continuing Nastaliq/English/Arabic paragraph with stacked diacritics (`مُحَمَّد`), embedded image, 2-page spanning rich table with repeated header row, and header/page-number field. | Stop as soon as one candidate passes `PDFBox` `PDFTextStripper` exact text extraction, $\le 0.25\text{ pt}$ glyph placement vs preview, and selectable non-zero character rects in Chrome/Acrobat. |

### F. Pre-Freeze Resolutions vs. Explicit Implementation Gates

| Item | Must Be Resolved BEFORE Plan Freeze (`v6.0.0`) | May Remain an Explicit Post-Freeze Implementation Gate |
| --- | --- | --- |
| **Normative Specification Text & Contradictions** | Apply **Edits 1–7** (`D` above) to eliminate internal contradictions in `editor-v6-standalone.md`: LIFO Undo ledger ordering (`F-01`), `TableMap` merged-cell deduplication & anchor survival (`F-02`), frozen partial-commit retarget rule (`F-03`), CRDT staging queue + raw-surrogate embed + `BoundaryUnit` Undo rule (`F-04`–`F-06`), deterministic block-level list marker & shared gutter policy (`F-07`), GID + `/ActualText` PDF contract (`F-08`), and the 4 MiB in-memory recovery pool cap (`F-09`). | Executing the 100,000-operation / 20-seed Kotlin fuzz suites (`G1`–`G3`, `C0`) and physical power-loss/crash-injection tests (`D0` / `G7`). |
| **Upstream Provenance, Toolchain & Licensing Ledger** | Complete Annex U metadata (`Automerge v3.5.0` MIT, `KitePDF v0.13.0` Apache-2.0 + `NOTICE`, `prosemirror-view` `code.haverbeke.berlin` upstream URL) and document the Kotlin `2.2.20` vs `2.3.x`/`2.4.x` klib ABI rule (`F-10`). | Final owner selection of the open-source library license (`MIT` vs `Apache-2.0`), commercial application license, and redistributable Nastaliq font license (`U0` / `A0`). |
| **Multilingual & Visual Oracle Definitions** | Fix the specification of `Q0` in v6 §3.1–§3.2 so it explicitly requires interior complex-cluster edits (not `stops[1]..stops[2]` spaces) and non-circular `ICU4J Bidi` + `hb-shape` oracles (`F-07`). | Completing the live execution of `Q0` (`V6-1`), human native Urdu calligrapher/reader sign-off (`U2`), and full DPI/zoom matrix runs (`G4`). |
| **Platform & Hardware Qualification** | Keep Android (`U2`), iOS (`U3`), Web (`U4`), Japanese IME activation, and 100k-unit single-paragraph latency (`U5`) explicitly marked `NOT RUN` / `OPEN` / `BLOCKED` in the capability manifest (`V6-I06`). | Executing physical device/OS qualification (`N0` / `G5` / `G6` for Android `InputConnection`/TalkBack, iOS `UITextInput`/VoiceOver, Web browser IMEs) and `G8` latency/memory benchmarks. |

### G. Earliest Useful Next Implementation Assignment

Do **not** interrupt the already-running `Q0` complex-cluster/geometry qualification task (`V6-1`), and do **not** start extracting the production persistent B+ tree (`V6-4 / K0`) yet.

The earliest high-leverage, zero-regret implementation assignment immediately following disposition of Edits 1–7 is a bounded **5-day `V6-3a` Schema + TableMap + CRDT Proof-of-Concept (`S0 + C0-Model` Vertical Slice)** in an isolated validation module (`validation/v6-s0-c0/`):

1. **Scope & Deliverable:**
* Port ProseMirror's pure grid-mapping logic from [ProseMirror/prosemirror-tables](https://github.com/ProseMirror/prosemirror-tables) (`src/tablemap.ts`, commit `eb522f25959a1e4515a4ff5ce7e3a939f19c55e9`, MIT) into a standalone dependency-free Kotlin class `TableGridMap` (~250 LOC) indexed by `(RowId, CellId, FlowId)` with `cellsInRect` deduplication (`F-02`).
* Implement `mergeCells` and `splitCell` in the reference document owner using `MoveNodes` so absorbed cell blocks and their `PositionRef` / `Comment` anchors (`F31`) survive in the top-left `FlowId` with `deleted == false`, plus the LIFO ledger fix (`F-01`) and frozen-prefix retarget guard (`F-03`).
* Wire a two-replica [Yjs](https://github.com/yjs/yjs) (`v13.6.33`) harness mapping a flow's tagged unit tape (`TextUnit`, `InlineAtomUnit`, `BoundaryUnit`, plus the `F-05` 1-unit `{ rawUtf16Unit }` embed for lone surrogates) to a single per-flow `Y.Text` with `Y.UndoManager` and the `F-04` `RemoteStagingQueue` during active composition.


2. **Must-Pass Acceptance Witnesses:**
* **Witness 1 (`F-01` & `F-03`):** `preedit("ab") -> partialCommit(1) -> queue(Undo_1) -> queue(Format(Bold)) -> queue(Undo_2) -> abortProvisional()` and same-session retarget over frozen prefix pass without orphan ephemeral slots or frozen-prefix erasure.
* **Witness 2 (`F-02`):** Merge $(0,0)$ and $(0,1)$ in a 2×2 Urdu table with an active comment anchor in $(0,1)$, select the 2×2 rectangle, run `preedit -> partialCommit -> cancel -> Undo`, and verify anchor preservation and zero duplicate-flow errors.
* **Witness 3 (`F-04`, `F-05`, `F-06`):** Two-peer trace where Peer A splits an Urdu paragraph (`Enter`), Peer B appends Urdu text to the new second paragraph and deletes half of a surrogate pair `😀` leaving `0xD83D`, while Peer A is mid-composition and then invokes `Undo`. Both peers converge with exact raw code unit `0xD83D` preserved (`I05`) and Peer B's appended text intact (`V6-I08`).


3. **Stop Condition:** Complete within 5 engineering days (or $\le 800$ LOC of test/adapter code). If `Y.Text` + `BoundaryUnit` embeds pass Witnesses 1–3, lock the `S0/C0` schema representation and proceed directly to `EXP-GP0` (`SkPDF` vs `PDFBox`/`KitePDF` GID + `/ActualText`) and `K0` kernel extraction.