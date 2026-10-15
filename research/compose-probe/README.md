# Published Compose Desktop probe

This runs `org.jetbrains.compose.foundation:foundation-desktop:1.12.1`, not a reimplementation. It is a headless JVM state/buffer test. It does **not** launch a Compose UI or exercise an Android, iOS, Desktop OS, or browser IME.

Executed with Eclipse Temurin JDK 23.0.2, Kotlin Maven plugin 2.2.20, and Apache Maven 3.9.9. The Kotlin version matches the artifact's declared standard-library dependency; this is not a claim about the latest Kotlin release.

From this directory:

```powershell
& '..\tools\apache-maven-3.9.9\bin\mvn.cmd' -q -B -ntp compile exec:java
```

The Maven archive downloaded from Maven Central was checked against its published SHA-512:

```text
8beac8d11ef208f1e2a8df0682b9448a9a363d2ad13ca74af43705549e72e74c9378823bf689287801cbbfc2f6ea9596201d19ccacfdfb682ee8a2ff4c4418ba
```

## Executed results

Start with `a` bold and `b` italic. Replace the entire `ab` with `x`, then replace `x` with `ab`. All styles disappear under all four `ExpandPolicy` values. Undo twice restores the original text, but restores neither original style. Full replacement losing styles is a range-lifetime policy; failure to recover styles on undo is the relevant rich-document fidelity failure.

After clearing history, adding a bold style across the existing text produces `canUndo=true`. Undoing it leaves the text `ab` with **no** styles, rather than its preceding bold/italic split. Removing a style, changing its value, and changing its range produce `canUndo=false` in the tested isolated transactions.

For a composition check, a small reflection seam calls the library's internal `setComposition` and `commitEditAsUser` methods to seed accepted state. The probe verifies that the public composition is `[0,2)` before each operation. Adding, removing, changing, or moving a style clears that composition. This tests state behavior after a synthetic internal command, not a real keyboard trace or the effects on a candidate window.

Deleting the italic `b` invalidates its tracked range. Reinserting `b` with `AtBoth` on the remaining bold interval extends bold over both characters. This agrees with documented tracked-range lifetime and expansion behavior; it is not presented as an unexpected implementation defect.

Assertions intentionally pin the observed limitations. Passing them means the failure is reproduced, **not** that a corrective mechanism passed.

Additional transaction fixtures start with the same seeded composition. Empty edits, changing a style and changing it back, moving a range and moving it back, restoring an expansion policy, and exceptions thrown after style or text/style mutations all preserve text, styles, selection, composition, and empty undo history. In contrast, adding and then removing a style in one successful edit preserves text/styles/selection but clears composition and creates an undo entry. Editing and calling `revertAllChanges()` restores text/styles/selection and leaves no undo entry, but still clears the seeded composition. The latter results show that equality of the final visible document is insufficient to predict protocol side effects in this implementation. They do not show that exception rollback is broken.

## Source corroboration

The published source archive and the `v1.12.1` GitHub tag have identical SHA-256 hashes for the two primary implementation files:

- `TextFieldState.kt`: `c332092f797a67ccd3479da2e483b610cb2e4448e60141e7458a69c982becb7e`
- `TextFieldBuffer.kt`: `ad59db5175a2f8a892446f2bf0369fe9a1e44d58496c2c053f9ebd2daa0bf775`

`addAnnotationToBuffer` records a range change as though text were replaced. `recordChanges` builds undo operations from plain strings and selections. `removeStyle` and tracked style/range setters do not call that change tracker. `syncMainBufferToTemporaryBuffer` reconstructs the buffer when its styles change, and the reconstructed value omits composition. These mechanisms explain the observed fixtures.

Sources: [artifact POM](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.12.1/foundation-desktop-1.12.1.pom), [published source archive](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.12.1/foundation-desktop-1.12.1-sources.jar), [TextFieldBuffer](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/TextFieldBuffer.kt), [TextFieldState](https://github.com/JetBrains/compose-multiplatform-core/blob/v1.12.1/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/text/input/TextFieldState.kt).

These observations establish missing behavior in this published Desktop state implementation. Conventional attributed undo operations and preservation of composition during text-equivalent style changes are plausible fixes; neither is claimed as an invention. No result here establishes parity across platform adapters.

## Expansion-channel experiment

```powershell
& '..\tools\apache-maven-3.9.9\bin\mvn.cmd' -q -B -ntp '-Dprobe.mainClass=ChannelProbeKt' compile exec:java
```

`ChannelProbe.kt` investigates a restricted representation: maintain the newest style stamp on each character separately for `AtStart`, `AtEnd`, and `AtBoth`. For insertion, their new stamps come respectively from the right neighbor, left neighbor, and maximum of both neighbors. Delete removes cells; replacement is delete followed by insert. Overlay writes overwrite the selected policy channel; visible formatting takes the newest stamp across channels.

This matched 60,000 randomized comparisons with the published Compose artifact: 30,000 with add/insert/delete and 30,000 including direct replacement. It tests one scalar attribute, represented by a distinct font weight for each stamp. It does not establish minimality, arbitrary attribute composition, span-handle removal, selective undo, or native behavior. The prototype uses arrays and is not a performance implementation.

`InsideOnly` supplies a concrete obstruction to simply adding one more character channel. On `abc`, add stamp 1 on `[0,3)`, then stamp 2 on `[0,2)`, then stamp 3 on `[1,3)`, all `InsideOnly`. Visible stamps are `[2,3,3]`; deleting the first, now-hidden span produces the same visible stamps. Delete `[1,2)` and insert `x` there. Compose yields `[2,1,3]` with the hidden span and `[2,0,3]` without it. A crossing span can become relevant despite being invisible at every original character. This is not a Compose defect; it defeats the proposed visible-value compression for that policy.

The experiment therefore does not establish a general canonical representation or a breakthrough. The conceptual distinction between interval identity and character properties also has explicit [Emacs prior art](https://www.gnu.org/software/emacs/manual/html_node/elisp/Not-Intervals.html).

## UTF-16 and code-point contract experiment

```powershell
& '..\tools\apache-maven-3.9.9\bin\mvn.cmd' -q -B -ntp '-Dprobe.mainClass=UnicodeProbeKt' compile exec:java
```

`UnicodeProbe.kt` uses the published 1.12.1 Foundation and UI Text Desktop artifacts. For U+1F600 (`D83D DE00` in UTF-16), the public buffer's `placeCursorBeforeCharAt(1)`, `placeCursorAfterCharAt(0)`, and collapsed selection at 1 all retain offset 1, inside the pair. Forward `[0,1)` and reversed `[1,0)` selections also retain their exact offsets. This contradicts the adjustment promised in the source documentation of those helpers; it does not establish that the rendered caret or user navigation lands there.

Deleting one UTF-16 unit can leave an isolated surrogate, as expected for an operation explicitly defined in those units. A legacy `DeleteSurroundingTextInCodePointsCommand(1,0)` deletes an entire valid pair, but also deletes an isolated high surrogate from `a D83D`. Its own published documentation says malformed requested ranges should cause no operation.

Reflection invokes the private `TextEditingScope` factories in the two actual published Skiko adapter implementations. The legacy factory queues `DeleteSurroundingTextCommand`, measured in UTF-16 units, for its method named `deleteSurroundingTextInCodePoints`. Executing the queued command on U+1F600 leaves an isolated high surrogate. The state adapter deletes the entire valid pair, but also deletes the isolated high surrogate in the malformed fixture. These are adapter-level unit tests; no OS keyboard, full input session, Android input connection, or rendered UI was tested.

Sources: [Android InputConnection contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection#deleteSurroundingTextInCodePoints(int,int)), [Compose code-point command contract](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/input/DeleteSurroundingTextInCodePointsCommand), and the published `ui-text-desktop:1.12.1` source archive retained under `upstream/`. The source implementations explain all observed results. Correct unit dispatch and validate-before-delete are ordinary fixes, not a claimed invention. The final assertions pin the observed behavior, including the deviations.
