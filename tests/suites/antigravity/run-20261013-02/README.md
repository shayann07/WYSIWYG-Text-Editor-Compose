# Antigravity Independent Validation Harness: Run 20261013-02

**Target Specification**: [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md) (Revision 4.4)  
**Run ID**: `run-20261013-02`  
**Execution Date**: 13 October 2026  
**Host Environment**: Windows 11 x64, Node.js v24.20.0, OpenJDK 23.0.2 (Temurin-23.0.2+7)

---

## 1. Directory Inventory

- **`preserved/`**: Archived copies of pre-correction plan and run-01 validation artifacts (`plan-v4-pre-correction.md`, `antigravity-validation-report-run01.md`, `antigravity-validation-matrix-run01.csv`, `antigravity-plan-diff-run01.md`).
- **`reproduce-original-defects.mjs`**: Executable regression script reproducing Coordinator Defects A, B, and C against the original `run-20261013-01` harness.
- **`reproduce-coordinator-defects.log`**: Raw captured terminal output of coordinator defect reproduction.
- **`DesktopAwtSpikeOriginalFailures.java`**: Executable Java test reproducing AWT InputMethodRequests Defects 1, 2, and 3 against the original `run-20261013-01` spike.
- **`reproduce-awt-defects.log`**: Raw captured terminal output of AWT defect reproduction (including `StringIndexOutOfBoundsException`).
- **`protocol-coordinator.mjs`**: Repaired protocol coordinator implementing FIFO command queueing across `Composing` and `AwaitingSettlement`, precise `CompositionTarget` extent resolution across 6 edge cases, unmark contract, commit without finish flag, selection-based implicit start, and candidate reconciliation integration.
- **`DesktopAwtInputMethodRequestsSpike.java`**: Corrected Desktop AWT `InputMethodRequests` spike implementing exact empty slice handling, cross-composition slice concatenation, composed-text-relative geometry, and null hit testing outside composition.
- **`test-coordinator-repairs.mjs`**: Comprehensive test suite verifying all repaired coordinator mechanisms and edge cases.
- **`test-mutations.mjs`**: Mutation verification suite injecting 8 deliberate representative bugs and proving that the test suite catches all 8.
- **`test-r14-and-properties.mjs`**: Stateful candidate path-independence property test driving two independent coordinator instances across 10,000 iterations against an independent oracle, plus epoch-reset divergence tests and raw UTF-16 persistence.
- **`runner.mjs`**: Deterministic test orchestrator running all 12 specification test suites.
- **`generate-matrix.mjs`**: Script generating and machine-validating `antigravity-validation-matrix.csv`.
- **`results.json`**: Deterministic test execution output.

---

## 2. Exact Execution Commands

### 2.1 Reproducing Original Defects (Against run-01)
```powershell
# Reproduce Coordinator Defects A, B, C
node validation/antigravity/run-20261013-02/reproduce-original-defects.mjs

# Reproduce AWT Spike Defects 1, 2, 3
javac -cp "validation/antigravity/run-20261013-01/classes" -d "validation/antigravity/run-20261013-02/classes" validation/antigravity/run-20261013-02/DesktopAwtSpikeOriginalFailures.java
java -cp "validation/antigravity/run-20261013-02/classes;validation/antigravity/run-20261013-01/classes" validation.DesktopAwtSpikeOriginalFailures
```

### 2.2 Running Repaired Test Suites (run-02)
```powershell
# 1. Full 12-suite specification test runner (including 10,000 stateful property fuzzing iterations)
node validation/antigravity/run-20261013-02/runner.mjs

# 2. Coordinator repairs and edge case verification
node validation/antigravity/run-20261013-02/test-coordinator-repairs.mjs

# 3. Mutation check suite (8 deliberate bugs caught)
node validation/antigravity/run-20261013-02/test-mutations.mjs

# 4. Corrected AWT InputMethodRequests Spike (Java)
javac -d "validation/antigravity/run-20261013-02/classes" validation/antigravity/run-20261013-02/DesktopAwtInputMethodRequestsSpike.java
java -cp "validation/antigravity/run-20261013-02/classes" validation.DesktopAwtInputMethodRequestsSpike

# 5. Generate and machine-validate matrix CSV
node validation/antigravity/run-20261013-02/generate-matrix.mjs
```

---

## 3. Before vs After Summary

| Component / Test | Status in run-01 | Status in run-02 | Proof / Evidence |
|---|---|---|---|
| Settlement Queueing | Failed (bypassed in AwaitingSettlement) | **PASS** | `test-coordinator-repairs.mjs` (Case 1) |
| CompositionTarget Extent | Failed (entire document became bold) | **PASS** | `test-coordinator-repairs.mjs` (Cases 2.1–2.6) |
| Unmark Event | Failed (ignored, no state transition) | **PASS** | `test-coordinator-repairs.mjs` (Cases 3.1–3.2) |
| AWT Empty Slice `[5, 5)` | Failed (`StringIndexOutOfBoundsException`) | **PASS** | `DesktopAwtInputMethodRequestsSpike.java` |
| AWT Geometry Offset | Failed (committed-relative) | **PASS** | `DesktopAwtInputMethodRequestsSpike.java` |
| AWT Outside Hits | Failed (returned non-null hit) | **PASS** | `DesktopAwtInputMethodRequestsSpike.java` |
| Path-Independence Test | Vacuous (identical pure arguments) | **PASS** | `test-r14-and-properties.mjs` (10k stateful runs) |
| Mutation Robustness | Untested | **PASS** (8/8 caught) | `test-mutations.mjs` |
| Gate G0 Desktop | Falsely claimed PASS | **NOT RUN / INCOMPLETE** | Headless unit separated from native host |
