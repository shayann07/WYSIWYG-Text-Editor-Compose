# Antigravity Validation Suite

Independent executable validation test suite and capability spikes for the WYSIWYG Compose Multiplatform document engine (`plan-v4.md`).

## Environment and Pinned Toolchains

- **Node.js**: v24.20.0 (Windows x64)
- **Java JDK**: Eclipse Temurin OpenJDK 23.0.2+7 (Windows x64)
- **Compose Desktop Baseline**: `org.jetbrains.compose.foundation:foundation-desktop:1.12.1`
- **Skiko Baseline**: `org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.150.1`
- **Maven**: Apache Maven 3.9.9 (local distribution at `research/tools/apache-maven-3.9.9`)
- **ProseMirror Runtime**: `prosemirror-model@1.25.12`, `prosemirror-state@1.4.4`, `prosemirror-history@1.5.1`

## Directory Structure

```text
validation/
├── README.md
└── antigravity/
    └── run-20261013-01/
        ├── reference-model.mjs                 # Pure specification reference model (§2, §4, §5, §6, §7)
        ├── protocol-coordinator.mjs            # Lifecycle, epochs, sequence stamps, settlement & history (§4, §7)
        ├── test-r01-to-r05.mjs                 # Executable tests for R01, R02, R03, R04, R05
        ├── test-r06-to-r10.mjs                 # Executable tests for R06, R07, R08, R09, R10
        ├── test-r14-and-properties.mjs         # Executable tests for R14 & 10,000-iteration deterministic fuzzing
        ├── DesktopAwtInputMethodRequestsSpike.java # Desktop AWT InputMethodRequests integration spike (R12, G0)
        ├── runner.mjs                          # Test runner orchestrating all JavaScript test suites
        ├── results.json                        # Archived JSON test results
        └── classes/                            # Compiled Java spike classes
```

## How to Reproduce

### 1. Run All Independent JavaScript Specification Tests

From the repository root:

```powershell
node validation/antigravity/run-20261013-01/runner.mjs
```

Expected output:
- 12 test suites executed (covering R01–R10, R14, and property fuzzing).
- All 12 PASS with exit code 0.
- Execution time: ~100–150 ms.

### 2. Run Desktop AWT Integration Spike (R12, G0 Desktop)

From the repository root:

```powershell
New-Item -ItemType Directory -Path validation/antigravity/run-20261013-01/classes -Force | Out-Null
javac -d validation/antigravity/run-20261013-01/classes validation/antigravity/run-20261013-01/DesktopAwtInputMethodRequestsSpike.java
java -cp validation/antigravity/run-20261013-01/classes validation.DesktopAwtInputMethodRequestsSpike
```

Expected output:
```json
{"test":"R12","status":"PASS","description":"AWT InputMethodRequests committed-text coordinate system excludes composed text, maps coordinates safely and stitches slices."}
```

### 3. Run Bounded Compose Desktop JVM Layout Probe Replication

From `research/compose-probe`:

```powershell
$probeCp = 'target/classes;' + (Get-Content -Raw target/layout-classpath.txt).Trim()
java -cp $probeCp LayoutProbeKt '32,128' 4
```

Expected output:
Exits with code 0, reporting Compose intrinsics and native bidi construction measurements across lengths 97 and 385.

### 4. Run External Unicode Java Reference Cross-Checks

From the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-23.0.2.7-hotspot'
node research/bracket-unicode-oracle.test.mjs
node research/bidi-block-context-oracle.test.mjs
```

Expected output:
- `bracket-unicode-oracle`: 1,967 non-overflow fixtures agree, 72,490 position checks pass.
- `bidi-block-context-oracle`: 12,418 requests checked, 1,749,721 direction comparisons pass.
