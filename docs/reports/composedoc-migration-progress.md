# ComposeDoc migration checkpoint

Owner authorized the complete Gradle multi-module migration, hash-preserving
relocation, Apache-2.0 governance and GitHub hardening on 19 October 2026.
Confirmed name: **ComposeDoc**, group and package root `dev.shayxo.composedoc`.
GitHub commit/push only; no Maven/package/release publication.

The library is multilingual Compose document infrastructure. Urdu/Nastaliq is
a primary early qualification workload; the future commercial/cloud product
is separately licensed. The owner selected eight root directories: core,
apps, build-logic, gradle, tooling, tests, docs and .github.

## Governance cleanup checkpoint (20 October 2026)

This checkpoint supersedes stale present-tense descriptions below; earlier
receipts remain historical evidence. Starting revision for this repair batch:
`9874ed52e71f3db2a05436ef902be69680f787c5`.

- Removed the obsolete `baseline = file("detekt-baseline.xml")` convention. No
  active Detekt baseline files existed at this starting revision. Current policy
  is zero findings with no baseline; the 3,303-finding description below records
  the earlier migration state, not current debt.
- `architectureCheck` now enforces baseline-free active configuration/files and
  adjacent `// Reason:` explanations for Kotlin suppressions. Its self-tests
  cover missing/inline/separated reasons, multiline/qualified/file annotations,
  literal/nested-comment false positives, baseline assignment/set/convention,
  and exclusion of generated/archive/build evidence. Twelve combined architecture/policy tests pass (four boundary and eight policy tests).
  Baseline-file/Gradle-configuration discovery uses Git's tracked plus nonignored
  untracked inventory across the repository, including root and tooling paths;
  suppression checks remain limited to active code. Ignored build/runtime data
  and preserved archive/evidence trees are not traversed.
- Four unnecessary qualification-harness unchecked casts now use star
  projections instead of suppressions. Remaining suppressions were reviewed for
  preserved API signatures or transaction/platform/failure-boundary behavior;
  comments describe their actual reason rather than exempting a whole file.
- Read-only Gradle task inspection proves `jvmApiCheck` consumes each module's
  `api/jvm/<module>.api`. Removed five obsolete flat `api/<module>.api` files:
  four duplicates and the stale UI dump. The five consumed JVM API dumps remain
  byte-for-byte unchanged. Reproduce the input inspection with
  `./gradlew -I tooling/architecture/api_inputs.init.gradle reportApiCheckInputs`.
- `EditorViewModel.verificationEngine` remains a public compatibility escape
  hatch for qualification traces; it is not an application port. Existing
  `architectureCheck` rejects its use and direct `EditorEngine` access from UI
  or app code outside the ViewModel. There is no API removal in this cleanup.

The serialized full Gradle and integration checks for this repair batch are
recorded by its final handoff; focused Python checks are not a substitute for
those gates. No new native/platform, PDF, print, IME or AT claim is made here.

## Migration completion checkpoint (20 October 2026; historical receipt)

The Gradle multi-module migration builds and passes every required local gate
on the reorganized tree. Run `python tooling/verification/final_gates.py` on a
clean commit to reproduce them; it writes `tooling/local/final-verification.json`
naming the verified commit, which governance activation requires.

| Gate | Result |
|---|---|
| `tooling/docs/validate_docs.py` | PASS — 48 historical and 33 sealed V6 hashes, 0 broken active links |
| `tooling/verification/v6.mjs` | PASS — 16/16 witnesses |
| `./gradlew check apiCheck` | PASS — architecture graph and 73 source files, Spotless/Ktlint, Detekt (baseline), API dumps for all five core modules; shared tests on JVM, JS (Node) and Android host |
| `test_verification.py` and tooling self-tests | PASS |
| `verify.py --workspace-milestone` | PASS — 20/20 stages, including 13 workspace, local-recovery and structured process-kill cases and Urdu U0 |
| `mutation_checks.py` | PASS — all 4 implementation mutants detected |

Changes made to reach green after the interrupted session:

- Distributions omit JetBrains Compose class-free redirect jars whose names
  collide with the androidx jars they forward to (`desktop-application-conventions`).
- Root `check` depends on each core module's `apiCheck`; the architecture graph
  ignores a module's edge to itself (KMP test configurations).
- `android.useAndroidX=true`; Node.js/Yarn download repositories are declared in
  settings, keeping `FAIL_ON_PROJECT_REPOS`.
- API dumps generated for data/domain/layout/ui; Spotless formatting applied.
- `gradlew` normalized to LF for Unix CI (`.gitattributes` disables conversion).

**Historical Detekt baseline (superseded above).** 3,303 pre-migration prototype findings are recorded in each
module's `detekt-baseline.xml` (mostly MaxLineLength 1,554, MagicNumber 920 and
WildcardImport 482; also 83 CyclomaticComplexMethod and 62 LongMethod, e.g. the
489-line `showEditor`). New findings fail the build. This is recorded debt, not a
claim that the extracted code meets the static-analysis standard.

Still open and not claimed: the pages/output milestone (`--pages-output-milestone`)
was not rerun after migration; Android/JS/iOS compile or host-test success does not
qualify native editing there (iOS builds and simulator tests run in the macOS `ios-build` CI job); native IME, AT, PDF,
printing and expert Urdu typography gates are unchanged.

## Resumption checkpoint (19 October, after usage interruption)

The entries below this checkpoint describe earlier baseline work. The migration
has since advanced: all eight root directories are in place, the Gradle wrapper
is installed, and model/domain/data/layout/UI and desktop shell sources have
been extracted. The historical source snapshot remains under
`tests/archive/pre-gradle-source-20261019/`.

Before relocation, all 20 workspace stages passed on build
`batch-20261019-215426-26512`; the 15 focused page/output groups also passed.
The 50-page fixture measured 655 ms layout, 87 ms capture and 142 ms raster.
These receipts are baseline evidence, not evidence that the migration passes.

After relocation, the documentation verifier passed all 48 historical and
33 sealed V6 hashes with zero broken active links, and the unchanged V6
witness runner passed 16 checks through its relocation adapter. JVM compilation
passed the five core modules. Desktop shell/harness compilation and all
migrated verification runners are still being completed. Java 21 is installed
in a task-specific temporary directory; the system Java installation is intact.

No commit, push, package publication or GitHub settings mutation has occurred.
The governance files are drafts until final gates pass. API dumps, multiplatform
checks, mutation runner migration, final onboarding and full verification remain.

## Earlier baseline checkpoint

Baseline repair, before relocation/module extraction. Existing dirty/untracked
owner work is retained. The prior pages/output work was incomplete, despite the
new assignment superseding its pause. Its document v9 / clipboard v5 formats
must be preserved, not downgraded to the older formats mentioned in the request.

- Initial documentation integrity PASS: 48 historical hashes, sealed V6
  validation, 161 local links, no broken links.
- V6 reference witnesses: 16/16 PASS.
- Initial workspace run `batch-20261019-212532-27804` failed an obsolete
  resize-to-rewrap assumption. Fixed-paper layout should instead preserve line
  layout and recompute its host transform; that invariant now passes.
- Added per-measurement item/ancestry/group indexing to avoid repeated whole
  document scans in list layout. The 50-page performance threshold has not yet
  been rerun successfully; no claim of a performance gate pass.
- Updated four writer-version assertions from8 to9; round-trip assertions remain.
- Updated connected-writing expectations to fixed96 document units/inch rather
  than the old zoom-dependent layout. Connected and structure focused suites pass.
- Visual marker matrix exposes a pre-existing new-page-plan source coverage
  failure at large text sizes. Diagnostics are being narrowed; no assertion was
  removed or threshold increased.

Apache LICENSE/NOTICE, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, CODEOWNERS,
PR template and initial CodeRabbit configuration are drafted locally. No
GitHub settings have been activated and no commit/push performed.

Read-only GitHub checks confirm authenticated owner shayann07 (id169547165),
personal public repository, admin permission and origin/main. Named User
ruleset bypass is supported; use it rather than exempting all administrators.
Keep force-push/deletion guards in a separate non-bypass safety ruleset.

Gradle8.14.3 was downloaded to the system temp directory and verified against
the official SHA256 bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531.
Wrapper bootstrap succeeded in `%TEMP%/composedoc-wrapper-bootstrap`; it has
not yet been installed into the project. JDK23 is locally installed; final CI
and local compatibility must also be checked with the requested JDK21.

## Historical remaining work at the earlier checkpoint

Finish baseline repairs and verify performance; relocate with a new resolver
and explicit immutable-original ledger (docs/relocations.json and
planning/doc_paths.py are themselves sealed); extract genuine common model/
domain and platform layout/UI/data with no outward dependencies; add the
Gradle conventions, UDF/API ports, boundary/format/static/API gates; port every
verifier and mutation harness; finish governance, CI and onboarding; run all
final checks; review staging for generated files/private data; commit and push
main only when green; activate and verify live rulesets/settings.

No additional name or directory approval is needed. Do not treat these drafts
or this checkpoint as completed architecture, passing quality gates or release
qualification. Do not spawn agents unless explicitly authorized for this task.
