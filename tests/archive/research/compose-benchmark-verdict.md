# Compose benchmark verdict — 2026-10-13

**Drop the bracket-suffix / bidi-block index as the editor research direction. No replacement breakthrough has been established.**

## Completed measurements

The clean JVM run used the published Compose Desktop 1.12.1 and Skiko 0.150.1 Windows x64 artifacts, warmed fonts, and a disabled native paragraph cache. It exited successfully. Each row has six measured trials after three warmups. Text is `a` followed by repetitions of `(א)`, a single paragraph, 16sp monospace, width 800.

| UTF-16 length | Native bidi construction median | Compose intrinsics median | Subsequent wrapping median |
|---:|---:|---:|---:|
| 3,073 | 0.0706 ms | 18.0198 ms | 0.1326 ms |
| 12,289 | 0.2205 ms | 218.4691 ms | 0.7751 ms |
| 24,577 | 1.5915 ms | 2,558.4240 ms | 4.7409 ms |

`compose-probe/layout-probe-confirmed.json` contains all 16 rows, including right-to-left-prefix and neutral-character controls. `LayoutProbe.kt` is the executable source. These are adversarial fixtures, not typical-document estimates. Short sample sets and substantial timing variation do not support precise scaling exponents.

The earlier run completed measurements at 196,609 characters: 2.9503 ms native bidi construction, 84,955.7257 ms Compose intrinsics, and 16.835 ms wrapping. Its output subsequently contains a Maven shutdown `InterruptedException`; it is retained as earlier evidence, not represented as a clean successful process. The smaller fresh run independently reproduced the severe penalty.

Native bidi construction is a **separate API measurement**, not instrumentation of the bidi stage inside Compose. Its UTF-8 string preparation is excluded. Only the first 32 native runs are enumerated separately. Therefore these numbers do not establish an exact bidi percentage or a guaranteed speedup from replacing bidi. Intrinsics includes native shaping and initial layout. No Android, iOS, Web, actual input-session, or editor frame-latency claim follows.

## Decision

The candidate index still traverses every block, consumes approximately 104 bytes per token in the recorded large fixture, implements only a classified subset of Unicode bidi, and has no demonstrated integration that reduces the much larger paragraph-construction cost. This is sufficient to reject it as the current research investment. It does not prove that every conceivable incremental bidi algorithm is useless.

## Alternatives investigated

1. **Avoid the initial infinite-width layout.** `FiniteWidthProbe.kt` compares the same published Compose `ParagraphLayouter` with infinite-then-finite layout versus finite-only layout. Both use the same native backend and disabled cache. The run exited successfully; 1,184 checks matched line metrics, intrinsic widths, hit tests, selection rectangles and three raster slices per case. At 12,289 characters the medians were 210.4519 versus 224.0058 ms; at 24,577 they were 2,805.6577 versus 2,444.0495 ms. Three measured trials after one warmup, interleaved ordering. It did not remove the severe cost. Rejected as the proposed remedy.
2. **Completed shaping records retained by native Skia.** The Skiko tag pins `dependencies.skia=m150-1f14f1166a`. In that source, `OneLineShaper::finish` sorts and visits the accumulated `fResolvedBlocks`, skipping already emitted earlier ranges without clearing the vector. This supplies a concrete quadratic bookkeeping mechanism for many shaping blocks. It is a source-level defect candidate consistent with the measurements, not a measured attribution of all runtime to that loop. Clearing completed records is an ordinary optimization, not a new editor architecture.

The standalone native validation attempt is **incomplete**: the release-library baseline executable exited with -1073741795; the patched translation unit did not compile with the selected C++17 settings. There is no passing native patch test or measured fixed-build speedup. Sources and failed build logs are retained under `native-layout-source`. Stop this expansion at the owner's request; do not present the patch as validated.

Pinned sources: [Skiko dependency declaration](https://raw.githubusercontent.com/JetBrains/skiko/v0.150.1/skiko/gradle.properties), [Skia OneLineShaper](https://raw.githubusercontent.com/JetBrains/skia/m150-1f14f1166a/modules/skparagraph/src/OneLineShaper.cpp).

## Reproduce the completed JVM probes

From `research/compose-probe`, build with the existing Maven distribution:

```powershell
& '..\tools\apache-maven-3.9.9\bin\mvn.cmd' -q -B -ntp -Playout compile dependency:build-classpath '-Dmdep.outputFile=target/layout-classpath.txt'
$probeCp = 'target/classes;' + (Get-Content -Raw target/layout-classpath.txt).Trim()
& 'C:/Program Files/Eclipse Adoptium/jdk-23.0.2.7-hotspot/bin/java.exe' -cp $probeCp LayoutProbeKt '32,1024,4096,8192' 9
& 'C:/Program Files/Eclipse Adoptium/jdk-23.0.2.7-hotspot/bin/java.exe' -cp $probeCp FiniteWidthProbeKt
```

The probes intentionally exit their dedicated JVM after printing results. They should not be embedded in another application process.
