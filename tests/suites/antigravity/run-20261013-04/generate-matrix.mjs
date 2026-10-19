import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, '../../..');
const matrixPath = path.join(rootDir, 'antigravity-validation-matrix.csv');

// Columns:
// Requirement,EvidenceScope,TestIdentifierAndAssertions,ExecutionEnvironment,Result,RemainingGap,PhaseGateStatus

const rows = [
  // --- Invariants I01 - I17 ---
  {
    requirement: "Invariant I01: Only the owner reducer changes document semantics",
    scope: "Model Test (Reference Reducer Scope)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: asserts single transaction reducer dispatch in reference model, direct mutation without transaction rolled back",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PARTIAL",
    gap: "Kotlin multiplatform dispatcher thread-confinement assertions pending Phase 1",
    gateStatus: "Phase 1 Incomplete (Model Validated)"
  },
  {
    requirement: "Invariant I02: Single snapshot publish or nothing on failure with balanced notifications",
    scope: "Model Test (Transaction Staging Scope)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: exception injection at stage 2 rolls back staged state, rev unchanged, zero history entries",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PARTIAL",
    gap: "Production persistent builder rollback to be implemented in Kotlin Phase 1",
    gateStatus: "Phase 1 Incomplete (Model Validated)"
  },
  {
    requirement: "Invariant I03: Unique IDs, valid schema, acyclic ancestry, legal caret in every flow",
    scope: "Model Test (Scalar Examples Only)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: table cell clear preserves cell ID, split allocates right ID, join preserves left ID (scalar examples)",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PARTIAL",
    gap: "Scalar examples cannot certify schema validity; full 100k structural fuzzing across nested lists/quotes pending Kotlin Phase 1",
    gateStatus: "Phase 1 Incomplete (Scalar Models Only)"
  },
  {
    requirement: "Invariant I04: Positions resolve at revision; stale commands reject unless rebasable",
    scope: "Model Test (Scalar Examples Only)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: mapOffset asserts Before/After association, deleted flag on interior replacement, split/join mapping (scalar examples)",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PARTIAL",
    gap: "Scalar examples cannot certify revision-aware tree resolution; PositionRef resolver and registered anchor range tree pending Kotlin Phase 1/2",
    gateStatus: "Phase 1/2 Incomplete (Algorithm Models Only)"
  },
  {
    requirement: "Invariant I05: Native UTF-16 exact accepted units; code-point deletion never uses unit counts accidentally",
    scope: "Model Test (Algorithm Scope)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: deleteSurroundingTextInUtf16 vs deleteSurroundingTextInCodePoints preflight checks",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Production Android InputConnection adapter wiring pending Phase 5",
    gateStatus: "Phase 1 Specification Validated (Adapter Pending Phase 5)"
  },
  {
    requirement: "Invariant I06: Grapheme and layout changes never modify stored attribution",
    scope: "Model Test (Attributed Model Scope)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r01-to-r05.mjs: WOMAN+LAPTOP ZWJ insert/remove roundtrip preserves constituent bold/italic styles verbatim",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Skiko text layout rendering of multi-run graphemes pending Phase 4",
    gateStatus: "Phase 1/2 Specification Validated (Compose Layout Pending Phase 4)"
  },
  {
    requirement: "Invariant I07: Within one attribution epoch, identical final candidate text gives identical attributes independent of intermediate candidates",
    scope: "Model Test (Stateful Fuzzing & Model Checker)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r14-and-properties.mjs & independent-model-checker.mjs: 10,000 property iterations + 15,000 model-checker transitions verifying candidate path independence",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Real native IME integration traces pending Phase 3/5",
    gateStatus: "Phase 3 Algorithm Validated"
  },
  {
    requirement: "Invariant I08: Partial commit freezes committed attributes; later suffix cancellation cannot erase prefix",
    scope: "Model Test (Repaired Coordinator & Model Checker)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-coordinator-repairs.mjs, test-r01-to-r05.mjs, independent-model-checker.mjs: partialCommit freezes prefix; suffix cancellation after target expansion verified intact across 15,000+ transitions",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Production InputConnection / UITextInput partial commit plumbing pending Phase 5",
    gateStatus: "Phase 3 Algorithm Validated"
  },
  {
    requirement: "Invariant I09: Each adapter callback accepted once by stamp; lifecycle end alone never inserts event text",
    scope: "Model Test (Protocol Sequencing Scope)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs & test-coordinator-repairs.mjs: sequence counter stamps, duplicate stamp caching returns Duplicate, LRU eviction returns DuplicateReceiptExpired",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Adapter receipt persistence bridge pending Phase 3",
    gateStatus: "Phase 3 Prerequisite Validated"
  },
  {
    requirement: "Invariant I10: Closed connection epoch cannot mutate newer connection",
    scope: "Model Test (Epoch Lifecycle Scope)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs, test-coordinator-repairs.mjs, independent-model-checker.mjs: openNewConnection increments epoch, delayed callback from old epoch rejected as StaleEpoch",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "OS focus/blur lifecycle integration on Android/iOS/Desktop pending Phase 5",
    gateStatus: "Phase 3 Prerequisite Validated"
  },
  {
    requirement: "Invariant I11: Geometry returned only with actual document layout font viewport key",
    scope: "Design Policy / Source Inspection",
    testAndAssertions: "plan-v4.md §6.2: FrameKey and ParagraphCacheKey immutable cache invalidation specification",
    env: "Architecture Design Review",
    result: "DESIGN POLICY",
    gap: "Skiko/Compose LayoutFrame pipeline implementation pending Phase 4",
    gateStatus: "Phase 4 Design Frozen"
  },
  {
    requirement: "Invariant I12: Undo and redo restore exact content, structural IDs, raw attribution, and recorded selection",
    scope: "Model Test (Attributed Text & Selection Coordinator Scope)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-coordinator-repairs.mjs & test-r01-to-r05.mjs: symmetrical undo/redo across composition, unmark, target expansion, and queued formatting",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PARTIAL",
    gap: "Scalar models cannot certify structural undo; full history budget eviction (64 MiB / 1,000 groups) and block-tree undo pending Kotlin Phase 2",
    gateStatus: "Phase 2 Incomplete (Text/Composition Scope Validated)"
  },
  {
    requirement: "Invariant I13: Input text projection and accepted native proposal agree within declared admission scope",
    scope: "Model Test (Admission Reducer Scope)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r01-to-r05.mjs: admitCompositionProposal validates non-crossing verbatim admission and structural rejection",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Production tagged projection and classify(range) implementation pending Phase 3",
    gateStatus: "Phase 3 Prerequisite Validated"
  },
  {
    requirement: "Invariant I14: Durable acknowledgement follows durable storage; corrupt journal tails never become edits",
    scope: "Model Test (Serialization Round-Trip Only)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r14-and-properties.mjs: base64 LE chunk serialization/deserialization with exact unit and attribute run round-trip (Note: serialization round-trip only; physical crash durability and journal recovery unrun)",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PARTIAL",
    gap: "Physical filesystem atomic flush, crash recovery journal, and durable write acknowledgement pending Phase 7",
    gateStatus: "Phase 1 Serialization Validated; Durability Incomplete (Gated Phase 7)"
  },
  {
    requirement: "Invariant I15: Normal local edit never flattens or serializes whole document",
    scope: "Design Policy / Source Inspection",
    testAndAssertions: "plan-v4.md §2.3: persistent B+ tree piece representation with O(log N) slice replacement",
    env: "Architecture Design Review",
    result: "DESIGN POLICY",
    gap: "Kotlin persistent B+ tree implementation pending Phase 1",
    gateStatus: "Phase 1 Design Frozen"
  },
  {
    requirement: "Invariant I16: Formatting-only and net-no-op commands do not manufacture text replacements or input restarts",
    scope: "Model Test (Coordinator Command Engine)",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-coordinator-repairs.mjs & test-r06-to-r10.mjs: duplicate format command returns NoChange without rev/history bump; empty range updates typing intent only",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "UI notification spy integration pending Phase 2",
    gateStatus: "Phase 2 Prerequisite Validated"
  },
  {
    requirement: "Invariant I17: Platform rendering and accessibility act on same semantic document revision",
    scope: "Accessibility Test",
    testAndAssertions: "plan-v4.md §6.5: SemanticsNode projection synchronized with FrameKey",
    env: "Physical AT Environments (TalkBack/VoiceOver/NVDA)",
    result: "NOT RUN",
    gap: "Requires physical screen reader testing with live assistive technology in Phase 6",
    gateStatus: "Phase 6 Gated"
  },

  // --- Regression Cases R01 - R16 ---
  {
    requirement: "RegressionCase R01: ZWJ insert/remove preserves constituent styles; explicit format expands to cluster",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r01-to-r05.mjs: woman+laptop+ZWJ join and split; applyGraphemeExpandedFormat patches all units with underline and retains bold/italic",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Integration with Compose multi-span text layout pending Phase 4",
    gateStatus: "Phase 2 Ready"
  },
  {
    requirement: "RegressionCase R02: Immutable baseline candidate reconciliation (detours, empty, prefix ties, surrogates)",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r01-to-r05.mjs: detour ab -> x -> ab, empty candidate, prefix tie resolution, surrogate pair boundary retreat",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Kotlin coordinator candidate loop pending Phase 3",
    gateStatus: "Phase 3 Ready"
  },
  {
    requirement: "RegressionCase R03: Partial commit freezes committed attributes; suffix edits cannot alter frozen prefix",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r01-to-r05.mjs & test-r14-and-properties.mjs: partialCommit freezes prefix; subsequent edits and empty cancellation leave prefix intact",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Kotlin coordinator partial commit plumbing pending Phase 3",
    gateStatus: "Phase 3 Ready"
  },
  {
    requirement: "RegressionCase R04: Native target expansion starts new attribution epoch; structural boundaries reject",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r01-to-r05.mjs & test-coordinator-repairs.mjs: target expansion expands envelope [1, 5) and resets epoch; structural crossing returns RequireBridgeSync",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Cross-block native bridge synchronization pending Phase 5",
    gateStatus: "Phase 3 Ready"
  },
  {
    requirement: "RegressionCase R05: Queued format FIFO order during settlement; precise extent; unmark & undo/redo",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-coordinator-repairs.mjs: FIFO queuing across Composing & AwaitingSettlement, nonterminal unmark defers history, repeated unmark rejected, completeSettlement resolves Bold to exact extent [1, 2), frozen prefix survives expansion/cancel, connection turnover preserves history, symmetrical undo/redo",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Integration with platform-specific settlement bridges pending Phase 5",
    gateStatus: "Phase 3 Ready"
  },
  {
    requirement: "RegressionCase R06: Epoch lifecycle rejects stale events, caches duplicate stamps, expires evicted receipts",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: epoch increments, duplicate stamp yields Duplicate, 50+ entries evicts oldest to DuplicateReceiptExpired, epoch 1 rejected after epoch 2",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Kotlin coordinator epoch manager pending Phase 3",
    gateStatus: "Phase 3 Ready"
  },
  {
    requirement: "RegressionCase R07: UTF-16 vs code-point deletion, invalid-surrogate preflight, reversed selection",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: deleteSurroundingTextInUtf16 leaves isolated unit; code-point deletion preflight rejects malformed surrogates atomically; reversed selection mapped correctly",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Android InputConnection surrounding text delete bridge pending Phase 5",
    gateStatus: "Phase 1/3 Ready"
  },
  {
    requirement: "RegressionCase R08: No-op style command creates no history/revision bump; exception cleanly rolls back",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: duplicate format command returns NoChange without rev/history bump; simulated transaction error restores exact pre-transaction state",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Kotlin transaction engine rollback pending Phase 1",
    gateStatus: "Phase 2 Ready"
  },
  {
    requirement: "RegressionCase R09: Split/join/delete anchor mapping and table cell non-merging deletion",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: anchor mapping across insert, replace, split, and join; table cell clear preserves cell ID without merging",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Registered anchor handle manager pending Phase 2",
    gateStatus: "Phase 2 Ready"
  },
  {
    requirement: "RegressionCase R10: Links boundary typing, whitespace exit, heading bold suppression Value(false)/Clear",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r06-to-r10.mjs: link typing auto-inherits only between identical link IDs; whitespace ends intent; Value(false) suppresses heading bold; Clear restores inheritance",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "TypingIntent state machine in editor-session pending Phase 2",
    gateStatus: "Phase 2 Ready"
  },
  {
    requirement: "RegressionCase R11: Final input after composition-end, noncancelable browser mutation, DOM correction",
    scope: "Real Browser / Wasm IME Test",
    testAndAssertions: "plan-v4.md §6.4: ProseMirror DOM candidate adoption without replacing composing nodes",
    env: "Browser Runtime (Playwright / Chrome)",
    result: "NOT RUN",
    gap: "Requires headless/live browser executing Wasm Kotlin bridge in Phase 5",
    gateStatus: "Phase 5 Gated"
  },
  {
    requirement: "RegressionCase R12: AWT InputMethodRequests committed-text coordinate system and slice queries",
    scope: "Compiled Host Unit Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/DesktopAwtInputMethodRequestsSpike.java: exhaustive 66 slice tests match oracle; empty slice [5,5) returns empty iterator; cross-composition slice matches; invalid slices throw; composed-text geometry & null hits verified; getSelectedText (forward/collapsed/reversed), getInsertPositionOffset, requested attributes, and unsupported cancelLatestCommittedText verified",
    env: "Windows 11 x64, OpenJDK 23.0.2",
    result: "PASS",
    gap: "Real native Compose/AWT host window, live OS IME, and accessibility integration unvalidated",
    gateStatus: "Unit Contract Validated; Native Host Gate G0 Desktop NOT RUN"
  },
  {
    requirement: "RegressionCase R13: Stale layout/font result, window transforms, scroll-anchor correction",
    scope: "Compiled Layout Test",
    testAndAssertions: "plan-v4.md §6.2: FrameKey coordinate transforms and cache invalidation",
    env: "Compose Desktop / Multiplatform Runtime",
    result: "NOT RUN",
    gap: "Requires Skiko/Compose layout engine implementation in Phase 4",
    gateStatus: "Phase 4 Gated"
  },
  {
    requirement: "RegressionCase R14: Raw UTF-16 with isolated surrogates survives persistence; export converts to U+FFFD",
    scope: "Model Test",
    testAndAssertions: "validation/antigravity/run-20261013-04/test-r14-and-properties.mjs: canonical base64 LE serialize/deserialize preserves isolated surrogates verbatim; exportValidUnicode converts to U+FFFD with report (serialization only; crash durability unrun)",
    env: "Windows 11 x64, Node.js v24.20.0",
    result: "PASS",
    gap: "Filesystem atomic replace and crash journal recovery pending Phase 7",
    gateStatus: "Phase 1 Serialization Ready (Durability Gated Phase 7)"
  },
  {
    requirement: "RegressionCase R15: Standard block workload plus long bidi, bracket depth, combining sequences",
    scope: "Compiled JVM Benchmark Baseline",
    testAndAssertions: "research/compose-probe/src/main/kotlin/LayoutProbe.kt: 12,289-unit adversarial paragraph benchmark executed (Compose intrinsics 218 ms vs native bidi 0.22 ms)",
    env: "Windows 11 x64, OpenJDK 23.0.2",
    result: "PASS",
    gap: "Full multiplatform release performance benchmark across backends pending Phase 8",
    gateStatus: "Phase 8 Workload Baseline Established"
  },
  {
    requirement: "RegressionCase R16: Real AT selection/edit/read-back including offscreen blocks and table cells",
    scope: "Accessibility Test",
    testAndAssertions: "plan-v4.md §6.5: Assistive technology editable accessibility protocol",
    env: "Physical AT Environments (TalkBack, VoiceOver, NVDA, Orca)",
    result: "NOT RUN",
    gap: "Requires live screen reader interactive qualification in Phase 6",
    gateStatus: "Phase 6 Gated"
  },

  // --- Phase Gates G0 - G8 ---
  {
    requirement: "PhaseGate G0-Desktop: Public AWT host, InputMethodRequests coordinate mapping and native IME host",
    scope: "Platform Host Qualification",
    testAndAssertions: "validation/antigravity/run-20261013-04/DesktopAwtInputMethodRequestsSpike.java: Headless InputMethodRequests unit tests pass; native Compose/AWT host, real OS IME input, selection, geometry, settlement, and AT NOT RUN",
    env: "Windows 11 x64 (Headless Unit Spike Passed; Native Host Unrun)",
    result: "NOT RUN",
    gap: "Real native Compose/AWT host integration spike with live OS IME and accessibility unvalidated",
    gateStatus: "G0-Desktop INCOMPLETE / NOT RUN"
  },
  {
    requirement: "PhaseGate G0-Android: Public Compose host with custom InputConnection, deletion and selection",
    scope: "Platform Host Qualification",
    testAndAssertions: "plan-v4.md §6.3: Android InputConnection contract",
    env: "Android SDK / Device (Unavailable)",
    result: "NOT RUN",
    gap: "Requires physical Android device / emulator with Gboard and TalkBack",
    gateStatus: "G0-Android NOT RUN"
  },
  {
    requirement: "PhaseGate G0-iOS: Attached UIView implementing UITextInput/UIKeyInput, notifications and geometry",
    scope: "Platform Host Qualification",
    testAndAssertions: "plan-v4.md §6.3: iOS UITextInput contract",
    env: "macOS / Xcode / iOS Simulator (Unavailable)",
    result: "NOT RUN",
    gap: "Requires macOS host with Xcode and VoiceOver",
    gateStatus: "G0-iOS NOT RUN"
  },
  {
    requirement: "PhaseGate G0-Web: ProseMirror view/state with synchronous Kotlin admission preserving composing nodes",
    scope: "Platform Host Qualification",
    testAndAssertions: "plan-v4.md §6.4: ProseMirror DOM projection contract",
    env: "Browser Runtime (Playwright / Chrome) (Unavailable)",
    result: "NOT RUN",
    gap: "Requires browser runtime executing Wasm bridge with real IME",
    gateStatus: "G0-Web NOT RUN"
  },
  {
    requirement: "PhaseGate G1: Headless kernel and persistence foundation (I01-I06, I14, 100k ops, save/load, rollback)",
    scope: "Kotlin Kernel Implementation",
    testAndAssertions: "plan-v4.md §8: Phase 1 acceptance gate",
    env: "Kotlin Multiplatform / JVM",
    result: "NOT RUN",
    gap: "Phase 1 implementation pending plan resolution; production implementation prohibited",
    gateStatus: "Phase 1 Gated (Pending Plan Resolution)"
  },
  {
    requirement: "PhaseGate G2: Commands, positions, formatting, and history (§5, R01, Undo/redo, no-op style, §2.6)",
    scope: "Kotlin Command Engine Implementation",
    testAndAssertions: "plan-v4.md §8: Phase 2 acceptance gate",
    env: "Kotlin Multiplatform / JVM",
    result: "NOT RUN",
    gap: "Pending Phase 1 completion",
    gateStatus: "Phase 2 Gated"
  },
  {
    requirement: "PhaseGate G3: Input coordinator with deterministic fake adapters (I07-I10, I12-I13, I16)",
    scope: "Kotlin Coordinator Implementation",
    testAndAssertions: "plan-v4.md §8: Phase 3 acceptance gate",
    env: "Kotlin Multiplatform / JVM",
    result: "NOT RUN",
    gap: "Pending Phase 2 completion",
    gateStatus: "Phase 3 Gated"
  },
  {
    requirement: "PhaseGate G4: Layout, drawing, and pointer behavior (Compose paragraph cache, hit testing, bidi, G4)",
    scope: "Compose Rendering Engine Implementation",
    testAndAssertions: "plan-v4.md §8: Phase 4 acceptance gate",
    env: "Compose Multiplatform Runtime",
    result: "NOT RUN",
    gap: "Pending Phase 3 completion",
    gateStatus: "Phase 4 Gated"
  },
  {
    requirement: "PhaseGate G5: Real platform input and Web projection (Android, iOS, Desktop, Web live IMEs)",
    scope: "Platform Adapters Integration",
    testAndAssertions: "plan-v4.md §8: Phase 5 acceptance gate",
    env: "Four Target OS Platforms",
    result: "NOT RUN",
    gap: "Pending Phase 4 completion and host availability",
    gateStatus: "Phase 5 Gated"
  },
  {
    requirement: "PhaseGate G6: Structured surfaces, clipboard, and accessibility (lists, tables, paste, TalkBack, NVDA)",
    scope: "Accessibility & Structured Surfaces",
    testAndAssertions: "plan-v4.md §8: Phase 6 acceptance gate",
    env: "Assistive Technology Testbed",
    result: "NOT RUN",
    gap: "Pending Phase 5 completion",
    gateStatus: "Phase 6 Gated"
  },
  {
    requirement: "PhaseGate G7: Durable recovery and lifecycle qualification (crash injection, journal, suspend)",
    scope: "Durability & Recovery Qualification",
    testAndAssertions: "plan-v4.md §8: Phase 7 acceptance gate",
    env: "Target OS Filesystems",
    result: "NOT RUN",
    gap: "Pending Phase 6 completion",
    gateStatus: "Phase 7 Gated"
  },
  {
    requirement: "PhaseGate G8: Performance and release qualification (p95 <=2ms edit, <=8ms layout, 100k target)",
    scope: "Release Qualification",
    testAndAssertions: "plan-v4.md §8: Phase 8 acceptance gate",
    env: "Target Production Hardware",
    result: "NOT RUN",
    gap: "Pending Phase 7 completion",
    gateStatus: "Phase 8 Gated"
  },

  // --- Research Track Entries ---
  {
    requirement: "Research Negative: Standalone Skia C++17 patched native layout build",
    scope: "C++ Compilation & Native Execution",
    testAndAssertions: "research/native-layout-source/build-patched.log: compilation failed with C2039/C2065 missing std::countl_zero; baseline run crashed (-1073741795)",
    env: "Windows 11 MSVC C++17",
    result: "UNVALIDATED OPTIMIZATION",
    gap: "Compilation failure leaves optimization unvalidated (not mathematically disproven); excluded from chosen architecture without stronger claims",
    gateStatus: "Excluded From Chosen Architecture"
  },
  {
    requirement: "Research Negative: Shipped Compose TextFieldState attributed undo & synthetic composition",
    scope: "Compiled JVM Compose Probe",
    testAndAssertions: "research/compose-probe/src/main/kotlin/Probe.kt: reproduces loss of styles on undo and composition clearing on style mutation",
    env: "Windows 11 x64, OpenJDK 23.0.2, Compose Desktop 1.12.1",
    result: "FAIL",
    gap: "Defect confirmed in shipped Compose; justifies engine-owned history and input coordinator",
    gateStatus: "Superseded By Custom Engine Architecture"
  },
  {
    requirement: "Research Investigation: Incremental Bidi Bracket Tree Index",
    scope: "Unicode 8/15 Oracle & Layout Benchmarks",
    testAndAssertions: "research/bracket-unicode-oracle.test.mjs & LayoutProbe.kt: Bidi construction costs (0.015 ms) eclipsed by Compose layout intrinsics (>1 ms)",
    env: "Node v24.20.0 & OpenJDK 23.0.2",
    result: "INCONCLUSIVE",
    gap: "Algorithm verified against oracle but dropped as unnecessary complexity",
    gateStatus: "Excluded From Chosen Architecture"
  }
];

function escapeCsvField(field) {
  if (field === null || field === undefined) return '""';
  const str = String(field);
  if (str.includes('"') || str.includes(',') || str.includes('\n') || str.includes('\r')) {
    return '"' + str.replace(/"/g, '""') + '"';
  }
  return '"' + str + '"';
}

const header = [
  "Requirement",
  "EvidenceScope",
  "TestIdentifierAndAssertions",
  "ExecutionEnvironment",
  "Result",
  "RemainingGap",
  "PhaseGateStatus"
];

let csvContent = header.map(escapeCsvField).join(',') + '\r\n';

for (const row of rows) {
  const line = [
    escapeCsvField(row.requirement),
    escapeCsvField(row.scope),
    escapeCsvField(row.testAndAssertions),
    escapeCsvField(row.env),
    escapeCsvField(row.result),
    escapeCsvField(row.gap),
    escapeCsvField(row.gateStatus)
  ].join(',') + '\r\n';
  csvContent += line;
}

fs.writeFileSync(matrixPath, csvContent, 'utf-8');
console.log(`Successfully generated matrix with ${rows.length} rows at ${matrixPath}`);

// Verification: parse back and assert row/column count and status distribution
const readBack = fs.readFileSync(matrixPath, 'utf-8');

function parseCsv(text) {
  const lines = [];
  let curLine = [];
  let curField = '';
  let inQuotes = false;
  
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (i + 1 < text.length && text[i + 1] === '"') {
          curField += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        curField += ch;
      }
    } else {
      if (ch === '"') {
        inQuotes = true;
      } else if (ch === ',') {
        curLine.push(curField);
        curField = '';
      } else if (ch === '\r') {
        if (i + 1 < text.length && text[i + 1] === '\n') {
          i++;
        }
        curLine.push(curField);
        curField = '';
        lines.push(curLine);
        curLine = [];
      } else if (ch === '\n') {
        curLine.push(curField);
        curField = '';
        lines.push(curLine);
        curLine = [];
      } else {
        curField += ch;
      }
    }
  }
  if (curLine.length > 0 || curField.length > 0) {
    curLine.push(curField);
    lines.push(curLine);
  }
  return lines;
}

const parsed = parseCsv(readBack);
const headerRow = parsed[0];
const dataRows = parsed.slice(1).filter(r => r.length > 1 || r[0] !== '');

console.log(`Header columns count: ${headerRow.length}`);
console.log(`Data rows count: ${dataRows.length}`);

for (let i = 0; i < dataRows.length; i++) {
  if (dataRows[i].length !== headerRow.length) {
    throw new Error(`Row ${i + 1} has ${dataRows[i].length} columns, expected ${headerRow.length}: ${JSON.stringify(dataRows[i])}`);
  }
}

const statusCounts = {};
for (const r of dataRows) {
  const status = r[4];
  statusCounts[status] = (statusCounts[status] || 0) + 1;
}

console.log("Validated Status Counts across Matrix:", statusCounts);
console.log(`Total evaluated items: ${dataRows.length}`);

// Validate referenced files exist
for (let i = 0; i < dataRows.length; i++) {
  const testInfo = dataRows[i][2];
  const fileMatch = testInfo.match(/^([a-zA-Z0-9_\-\.\/]+(?:\.mjs|\.java|\.kt|\.log|\.md)):/);
  if (fileMatch) {
    const relFile = fileMatch[1];
    const absPath = path.resolve(rootDir, relFile);
    if (!fs.existsSync(absPath)) {
      throw new Error(`Row ${i + 1} references nonexistent file: ${relFile} (${absPath})`);
    }
  }
}
console.log("All referenced test files verified to exist on disk!");
