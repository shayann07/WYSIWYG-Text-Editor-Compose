# Run 20: independently reviewed v5 reference

Specification/validation only, not production. The original reviewer archive and run-19 remain unchanged. The new DocumentModel adapts reviewed repairs, then corrects handle lifetimes/provisional origins and integrates recovery, Unicode and current-version measure contracts. document-model-base.mjs is the explicit copied prior layer, not another state owner.

Run from repository root:

```powershell
node validation/codex/run-20261016-20/verify.mjs
javac -d validation/codex/run-20261016-20/classes validation/codex/run-20261016-20/AwtBoundaryProbe.java
java '-Djava.awt.headless=true' -cp validation/codex/run-20261016-20/classes AwtBoundaryProbe
```

Node 24; Java probe used JDK 23.0.2. No npm dependencies for the current suite. It writes results.json; copy the frozen result before rerunning if comparing to v5-freeze-manifest.json. Do not regenerate the frozen documents casually. publish-v5.py validates sources/results and creates the standalone/spec/report/manifest; source changes require a reviewed version increment, not silent republishing.

Important files: test-v5.mjs (accepted reviewer and independent tests); unicode-grapheme.mjs with hashed Unicode data; test-measure.mjs (equations, not optimized index); recovery-model.mjs and test-recovery.mjs (framing, draft retention and simulated durable boundaries); gemini-reproduction.json, gemini-repair-counterexamples.json and gemini-storage-audit.json (independent assessment); AwtBoundaryProbe.java/output (JDK hit aliases, not native IME). failure-*.json are archived intermediate failures.

Original Gemini F07B is invalid baseline usage; F10A_1 is an invalid proposed restriction; F10A_2 is a compatibility choice. 22 actual reviewer regressions enter the current suite. Source/data hashes and scope labels are authoritative. The C++/Chromium reviewer results remain historical, not locally rerun full-pipeline or live IME evidence. No G0-G8 production gate advances.
