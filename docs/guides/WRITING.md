# Writing with the local Urdu workspace

Current implementation: 19 October 2026. Qualification, exact build and open checks are recorded in [the workspace batch report](../reports/v6-local-writing-workspace-batch.md). This is the Windows desktop consumer of the reusable editor foundation, not whole-product or cross-platform qualification.

Current verification: build202031 passes20 integrated stages and fixes two reproduced races where asynchronous Discard/Remove Recent completion removed a newly selected UI row. Both captured-target regressions pass. The native core writing → conflict → owned process kill → recovery → Save/fresh Open sequence remains separately dated192824 evidence. A two-approach retry confirmed tool cache/targeting errors before the dialog received selection input; final native discard/new-arrival, Locate/cleanup, Reload/ordinary close and full window/image-transform coverage remain BLOCKED / NOT RUN. Computer control is stopped. See [the continuation report and manual checklist](../reports/v6-workspace-native-finish-20261019.md); automated success does not close native qualification.

From `D:\Work\WYSIWYG-Text-Editor-Compose`:

```powershell
.\desktop-prototype\write-urdu.cmd --workspace-sample
```

The new disposable sample is `desktop-prototype/samples/disposable-workspace-v8.astra`; it opens as an independent unsaved copy. Historical v3/v5/v6 samples are preserved. `--sample` still opens the preceding structured exercise. Generate a new sample into an unused path with `desktop-prototype\run.cmd --create-workspace-sample=D:\YourTestFolder\sample.astra`.

## Start and resume work

Use the **Workspace** tab for New / Template (also Ctrl+N), Recent / Workspace, Save as Template, Page settings and Unfinished recovery. File / edit keeps Open (Ctrl+O), Save (Ctrl+S), Save As (Ctrl+Shift+S), Undo/Redo and rich/plain clipboard actions.

New / Template offers Blank Urdu, Urdu Letter and Urdu Article / Report. The original Urdu text is self-authored and not expert linguistically reviewed. Each instance is unsaved, owns its resources and gets fresh structure/link identities. User templates are private copies, not references to arbitrary source documents. Removing a user template removes only that private template. It does not delete a source or created document. A failed Save as Template keeps the entered name available for retry.

Recent / Workspace shows the name, full path, last-opened timestamp, window identity, current/missing/changed basis and recovery availability. Equal filenames are disambiguated by path and window identity. Open saved file opens accepted disk content. Locate lets you choose a moved file. A saved-file fingerprint is distinct from the last editing-content basis. Positions restore only when the editing-content basis matches too (including bidi/wrap affinity). Opening an older saved file when newer edits exist requires an explicit saved-content/recovery choice. Scroll additionally requires the same viewport dimensions; otherwise the restored caret is revealed. Mismatches require an explicit choice and start at offset zero. Fresh owners start with empty Undo/Redo. Removing/Clearing recent references does not delete documents or recovery records. Active windows continue publishing their own metadata.

Restore matching workspace reopens inactive, unchanged named references. Running owners are not silently duplicated. Missing, changed and unsaved references remain for explicit Locate/Open/recovery choices. There is no automatic crash/reopen loop. View, zoom, preview font, selection and scroll are presentation metadata, not document history or dirty changes. Unsaved owners have recovery identities even without a path. Workspace storage stays local.

## Saving and external conflicts

Automatic recovery never overwrites a named file. Save writes only accepted content on explicit request. Finish composition before Save. A slow Save acknowledges its captured root; subsequent edits stay dirty and keep their recovery work.

The editor remembers the exact bytes read or successfully written, including an explicit absent-file state. Save validates that fingerprint under a path lock shared by cooperating instances, then revalidates immediately before atomic replacement. It preserves the original on write/validation failures. Save As captures and confirms its destination's current state, then revalidates it too.

A conflict offers Keep editing, Reload external version and Save a Copy. Reload preserves dirty local content to a completed recovery copy before opening the external version in a fresh owner; the local window stays available. A failed preservation blocks reload. Save a Copy uses another path and preserves both the external version and local work.

This is not a universal filesystem compare-and-swap. An arbitrary noncooperating writer can race the final validation/rename, and alternate hard-link/path aliases are not a universal shared lock identity. Use a new copy when overwrite safety cannot be established. Lock/write-denied destinations report failure; they are not silently truncated.

## Text, structure and navigation

- Text: stored qualified-candidate font, size 8–96, bold, italic, underline, strike and color/default. Mixed states remain visible. The View preview font is a separate fallback/profile, never a rewrite of stored font choices.
- Paragraph: Auto/RTL/LTR, Start/Center/End, before/after spacing, natural or explicit line spacing. Half-open targeting excludes the following paragraph when selection ends exactly at its start. Backward selections target the same paragraphs.
- Structure: Normal/H1–H6, bullets/numbers, nesting, item continuation, restart, Marker style, links and atomic images. Semantic markers stay out of stored text. List style is seeded once; ordinary body edits do not vote on it. Siblings share a parent-derived lane; each item retains its own text/marker direction. RTL ancestry does not turn English text RTL.
- Links: at a strict interior caret, edit/remove the contiguous link identity. Explicit selections retain their own scope. Ordinary clicks edit; Open Link is explicit and permits only http/https/mailto.
- Find: Ctrl+F; Replace: Ctrl+H. Literal, case-sensitive, exact Unicode, no normalization/variant equivalence. Enter/Shift+Enter navigate, Escape returns to the document. Search does not enter Undo. Hard paragraphs and image blocks are boundaries; soft breaks remain searchable. Matches cutting graphemes are indicated but cannot be replaced. Replace All is one captured-basis transaction and preserves unaffected styles/paragraph properties.
- Ctrl+Home/End, Page Up/Down and Shift variants navigate/select. Zoom 50/75/100% changes presentation only. Counts label UTF-16 units and whitespace-delimited word-like runs, not linguistic tokenization.
- Outline uses heading identity and actual cell hits; blank pane clicks do not navigate. Current section and keyboard focus are distinct. Long script-capable labels wrap.

Enter, empty-item Enter, Item paragraph and Backspace retain the established structural rules. Shift+Enter is a soft break. Heading-end Enter becomes Normal; mid-heading split retains the role. Tab remains ordinary focus navigation. Clipboard carries fresh pasted identities and complete-item metadata; partial paragraph destination inheritance remains in force. Page settings belong to the destination document, so rich paste does not replace its page setup. Ctrl+Shift+V is explicit plain paste. Plain representation uses derived list markers, displayed link text with URI, and `[Image: alt]`; this explicit conversion is not rich structure preservation.

## Pages and images

Page settings offers A4/Letter, portrait/landscape and uniform 5–40 mm margins, as one undoable document command. It reflows the existing page planner. There are no section-specific margins or PDF/print qualification in this batch. The page planner retains whole-paragraph shaping/bidi across fragments. Oversized images fit proportionally to content/page bounds.

In Structure, Insert image embeds a local PNG/JPEG after bounded worker signature/dimension/decoder validation. Select the image to use Replace image, Delete image and Image properties. Text landing paragraphs remain before/after the block. The original source can be moved/deleted after insertion without breaking the document.

Image properties shows requested versus fitted width. Width is 10–600 document dp; height follows the embedded aspect ratio. Original size interprets pixel width as dp and clamps to that supported range. Fit to content chooses the current text lane width (also clamped). Alt text permits at most 2048 UTF-16 units. Empty alt means no description/decorative intent; it does not claim screen-reader qualification. Invalid inputs remain editable with an actionable error.

Selected images show left/right middle handles. Drag previews a proportional outline and requested width. Release submits one guarded Undo transaction; Escape cancels with no history/checkpoint entries. Stale content/composition/view transforms refuse the completion. Automatic fitting may keep the displayed image bounded while requested width changes. Replace preserves placement, requested width and existing alt text; it changes the resource/aspect. Undo restores the exact previous resource/properties.

Images: maximum 4 MB encoded each, 8192 per dimension and 16 million decoded pixels; aggregate resources remain conservatively bounded (32 resources, 9 MB encoded and 16 million pixels). Current content, history, clipboard and recovery retain their required owned bytes. Resources are conservatively retained rather than aggressively collected; repeated replacements can reach the limit. Import has one worker and four queued tasks; canceled/disposed/stale completions cannot mutate a different target. Transparent PNG and ordinary RGB JPEG are supported. Nontrivial JPEG EXIF orientation and PNG EXIF metadata are refused with instructions to export pixel-oriented input. No SVG, remote downloads, crop or floating wrap.

## Automatic local crash recovery

Accepted recovery copies and unfinished input are separate. The default directory is `%LOCALAPPDATA%\AstraEditorPrototype\recovery`; `--recovery-dir=D:\YourTestFolder\recovery` isolates disposable tests. Accepted roots are scheduled every two seconds plus actual disk time. Unfinished capture is scheduled every 700 ms plus queue/encoding/disk time. Only a completed publication receipt protects that generation. Pending/spinners are not durable success.

On restart, Recovery Copies restores accepted content as a separate unsaved document. Unfinished recovery separately offers Inspect, Restore unsaved draft, lossless Export, Later and selected-generation Discard. Quarantine remains inspect/export-only evidence and is never replayed. No active composition or native callback resumes. See [RECOVERY.md](RECOVERY.md) for budgets, lifecycle/failure semantics and exact loss windows.

## Ten-minute walkthrough

1. Open Workspace, Ctrl+N, choose Urdu Article / Report, Create unsaved copy. Type your own mixed Urdu/English/numbers in the new document.
2. Use Text and Paragraph controls; create/nest an item in Structure. Use Marker style independently of body formatting. Inspect the English child's own direction.
3. Insert the disposable PNG, enter alt text, change width, try invalid width then correct it. Drag a handle, Undo/Redo. Try Original size/Fit and Replace image; alt text stays intentional.
4. In Workspace, choose Letter/landscape/margins. Switch Pages and zoom. Outline + Find remain reachable; short windows scroll the header rather than hiding actions.
5. Save to a new disposable `.astra`. Create a second template document, change view/selection, and inspect Recent / Workspace.
6. If another application changes the saved file, attempt Save: inspect the conflict and Save a Copy. Both versions remain available.
7. Reopen the copy. Confirm stored text, formatting, markers, page settings and embedded image, not just its appearance.
8. For a controlled crash exercise use an isolated directory, wait for accepted/unfinished publication receipts, terminate only the owned disposable process, relaunch and use the separate recovery choosers. Synthetic composition injection is test evidence, not native IME qualification.
9. Inspect/export unfinished evidence, open an attributed unsaved draft, Save As, and Later for records you still need. Discard only selected test records after reviewing them.
10. Close/reopen the saved result; fresh Undo is empty. Do not infer exact text from a screenshot alone.

Previous guide snapshots are preserved in [the pre-workspace writing guide](../archive/writing-through-v6-desktop-20261019.md). Their old codec/memory-only statements are historical, superseded by this guide. Candidate IME, full AT, expert Urdu typography, other DPI/platforms and print/PDF remain separate qualification gates.
