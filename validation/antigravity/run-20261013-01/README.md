# Antigravity Independent Validation Harness: Run 20261013-01

**Target Specification**: [`plan-v4.md`](file:///d:/Work/WYSIWYG-Text-Editor-Compose/plan-v4.md)  
**Run ID**: `run-20261013-01`  
**Execution Date**: 13 October 2026  
**Host Environment**: Windows 11 x64, Node.js v24.20.0, OpenJDK 23.0.2 (Eclipse Temurin)

---

## 1. Directory Inventory

- **`reference-model.mjs`**: Standalone JavaScript reference implementation of §4.3 immutable baseline reconciliation, partial commit freezing, grapheme cluster effective views (§5.3), Android deletion contracts (§6.3), position mapping (§2.5), and raw UTF-16 canonical persistence (§2.3, §7).
- **`protocol-coordinator.mjs`**: Standalone lifecycle coordinator testing monotonic sequencing, receipt caching, duplicate expiration, settlement watchdog, queued application commands, implicit preedit session initialization with snapshot pinning, and symmetrical undo/redo history.
- **`runner.mjs`**: Deterministic test orchestrator running all 12 test suites and writing `results.json`.
- **`test-r01-to-r05.mjs`**: Test suites for R01 (grapheme formatting), R02 (detour/tie-breaking/surrogate candidate reconciliation), R03 (partial commit freezing), R04 (target expansion & boundary rejection), and R05 (queued format, implicit preedit snapshot undo, and redo).
- **`test-r06-to-r10.mjs`**: Test suites for R06 (epoch lifecycle & sequence caching), R07 (UTF-16 vs code point deletion), R08 (no-op styles & transaction rollback), R09 (block splits/joins/anchor mapping), and R10 (links & heading bold suppression).
- **`test-r14-and-properties.mjs`**: Test suites for R14 (isolated surrogate persistence & valid export conversion) and Property-I07-I08 (10,000 randomized property fuzzing iterations).
- **`DesktopAwtInputMethodRequestsSpike.java`**: Native Java Desktop AWT `InputMethodRequests` integration spike validating committed-text coordinate system translation and endpoint boundary affinity (`Association.BEFORE` / `Association.AFTER`).
- **`results.json`**: Archived deterministic test execution output.

---

## 2. Reproduction Instructions

### 2.1. Independent Specification Test Suite (Node.js)
```powershell
node validation/antigravity/run-20261013-01/runner.mjs
```
Expected output: 12 passing suites (R01–R10, R14, and Property-I07-I08) in < 150 ms with exit code 0.

### 2.2. Desktop AWT Integration Spike (Java)
```powershell
javac -d validation/antigravity/run-20261013-01/classes validation/antigravity/run-20261013-01/DesktopAwtInputMethodRequestsSpike.java
java -cp validation/antigravity/run-20261013-01/classes validation.DesktopAwtInputMethodRequestsSpike
```
Expected output: `{"test":"R12","status":"PASS",...}` with exit code 0.

### 2.3. Historical Research Probes & Baselines

#### Existing Node Baselines
```powershell
node research/grapheme-style-roundtrip.mjs
node research/existing-attribution-control.mjs
node research/composition-path-dependence.mjs
node research/composition-history-probe.mjs
```

#### Unicode Reference Oracles (Requires JDK 23)
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-23.0.2.7-hotspot"
node research/bracket-unicode-oracle.test.mjs
node research/bidi-block-context-oracle.test.mjs
```

#### Desktop Compose 1.12.1 Probes (Using Bundled Maven)
```powershell
& "research/tools/apache-maven-3.9.9/bin/mvn.cmd" -q -B -ntp compile exec:java
& "research/tools/apache-maven-3.9.9/bin/mvn.cmd" -q -B -ntp "-Dprobe.mainClass=UnicodeProbeKt" compile exec:java
& "research/tools/apache-maven-3.9.9/bin/mvn.cmd" -q -B -ntp "-Dprobe.mainClass=ChannelProbeKt" compile exec:java
& "research/tools/apache-maven-3.9.9/bin/mvn.cmd" -q -B -ntp "-Dprobe.mainClass=HiddenStateProbeKt" compile exec:java
```

#### Bounded Compose Layout Replication
```powershell
$probeCp = 'target/classes;' + (Get-Content -Raw target/layout-classpath.txt).Trim()
java -cp $probeCp LayoutProbeKt '32,128' 4
```
