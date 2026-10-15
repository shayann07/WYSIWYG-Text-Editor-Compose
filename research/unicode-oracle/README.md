# External paired-bracket oracle

The `upstream` files were downloaded from Unicode's public Java reference directory on 2026-10-09 and have not been edited. Their ReadMe declares Unicode 8.0. The repository's current candidate follows the current BD16 overflow rule, so expected version differences are tested explicitly.

| File and source | SHA-256 |
| --- | --- |
| [BidiPBAReference.java](https://www.unicode.org/Public/PROGRAMS/BidiReferenceJava/BidiPBAReference.java) | `a899dde8c0cfc841135cc91e48a3c8050598a4b0130fa7c6d5e2524984bbc832` |
| [BidiReference.java](https://www.unicode.org/Public/PROGRAMS/BidiReferenceJava/BidiReference.java) | `5e64b9c3e13e68e7712e33e222456e6e02f1e6b585123cb5707f6cfd0926973c` |
| [ReadMe.txt](https://www.unicode.org/Public/PROGRAMS/BidiReferenceJava/ReadMe.txt) | `022245d8906d4795859b7c106aceb6aaeb009a70438d9b42a5e9ea9badb04047` |

`PairOracle.java` is the experiment's wrapper, not an upstream source file. It supplies ASCII bracket classifications, calls the public reference API, and reads the resulting private pair collection using reflection solely to expose it to the test. It does not replace or modify the upstream matching implementation. All supplied bidi classes are ON because only pair locations are under test; no directional-level conformance follows.

Run from the project root using JDK 23 and Node:

```powershell
New-Item -ItemType Directory -Path research/unicode-oracle/classes -Force | Out-Null
& 'C:/Program Files/Eclipse Adoptium/jdk-23.0.2.7-hotspot/bin/javac.exe' -d research/unicode-oracle/classes research/unicode-oracle/upstream/BidiReference.java research/unicode-oracle/upstream/BidiPBAReference.java research/unicode-oracle/PairOracle.java
node research/bracket-unicode-oracle.test.mjs
```

The Node driver uses JAVA_HOME when set; otherwise it uses the JDK path above. The recorded run is `../bracket-unicode-oracle-output.json`.

Results: 1,967 non-overflow fixtures agree with the external reference. There are 72,490 checked positions in total, including two overflow fixtures whose candidate result is checked against the current rule. On `()` followed by 64 opening parentheses, overflow occurs at position 65. The old reference retains `[0,1]`; the current [BD16 specification](https://www.unicode.org/reports/tr9/#BD16) requires an empty pair list, which the candidate returns. The test expects and reports that difference.

The upstream source's version, classifications supplied by the wrapper, ASCII-only candidate, and lack of directional-level/shaping integration prevent treating this as a complete Unicode conformance result. No real keyboard, Compose layout, or operating-system bidi implementation was exercised by this test.

## Directional wrapper

`DirectionOracle.java` supplies L/R/ON classifications and ASCII bracket properties to the unmodified `BidiReference`, requests levels for one line, and returns them. It accepts paragraph parity 0 or 1. The Node driver compares level parity with N0-N2 directional queries; it does not claim complete levels, weak-type behavior, arbitrary Unicode data or modern overflow agreement.

```powershell
& 'C:/Program Files/Eclipse Adoptium/jdk-23.0.2.7-hotspot/bin/javac.exe' -d research/unicode-oracle/classes research/unicode-oracle/upstream/BidiReference.java research/unicode-oracle/upstream/BidiPBAReference.java research/unicode-oracle/DirectionOracle.java
node research/bidi-block-context-oracle.test.mjs
```

Recorded result: 12,418 checked requests, 111,690 window comparisons and 1,749,721 checked directions. Two overflow requests were excluded because the old reference differs from the current BD16 rule. Randomly mutated fixture strings are passed to the oracle; persistence across edits is separately exercised by the direct-model suite. The driver reconstructs an index per externally checked fixture.
