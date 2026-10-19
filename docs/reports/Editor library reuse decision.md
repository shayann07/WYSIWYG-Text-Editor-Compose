# Editor library reuse decision

17 October 2026. Decision for the owner's requested open-source Compose Multiplatform WYSIWYG library, including hierarchical documents, native composition, integrated tables, paged editing and preview/print fidelity.

## Decision

**Keep an independent document/transaction core for the full requested product. Do not replace the project wholesale with ComposeTextEditor, Compose Rich Editor, Cascade or TextKit, and do not fork an entire editor as the default strategy. Reuse existing infrastructure and selected components; implement only the missing integration and semantics.**

For a conventional rich-text field or a Markdown-oriented editor, the recommendation would be different: adopt an existing library, with Darkrock's ComposeTextEditor the strongest candidate for a custom-input foundation. The expanded document-editor scope materially changes the decision.

This is an architectural fit decision, with moderate confidence about relative engineering cost. It is not a runtime certification or a demonstrated cost estimate. Existing libraries have substantially more implemented platform behavior than our desktop prototype. Our planned features are not equivalent to their implemented features, and their untested cases are not evidence that our implementation is better.

## Evidence collected and limits

Read current upstream source, design documents, public APIs, tests, release metadata and explicit exclusions. Local isolated source copies were inspected under `desktop-prototype/local-evidence/reuse-decision/`. GitHub API reads resolved current branch commits because cached search pages disagreed with live documentation. Darkrock's downloaded README and table-design document were checked against GitHub tree blob hashes after normalizing checkout line endings.

No upstream build, behavioral test suite, real IME session or print job was executed for this comparison. Test and performance descriptions below are upstream evidence, not results reproduced here. No claim is made that a package fails merely because it uses BasicTextField or a different internal representation. No benchmark numbers establish comparative superiority. The original subagent research was interrupted by usage limits; the final assessment was completed directly from the available source copies and live primary sources.

## Why the strongest candidate is not a drop-in foundation

Darkrock ComposeTextEditor directly tackles BasicTextField limitations and already implements substantial input, editing, history and layout functionality. Its repository documents Android/Desktop support and beta iOS/WASM support. It is a serious implementation, not a reason to duplicate ordinary rich-text widgets. However, its declared line-stack model does not preserve all block arrangements required here, including multi-paragraph list items and some blocks nested inside lists/quotes. Extending that model is a core/document-import change, not just adding a toolbar. [Pinned README](https://github.com/Darkrock-Studios/ComposeTextEditor/blob/3589818fc669008ea13e222ff45c641c305bfa6a/README.md)

Its table model is explicitly one document line per cell. The source changes cell newlines to spaces. The design document lists multi-line cells, rectangular column selection, grid-style multi-cell paste and table screen-reader semantics among missing work. Our required cells contain independent rich flows and require rectangular operations, so adopting its current table behavior would reduce scope. Repairing that mismatch would affect selection, layout, import/export and history together. [Table design](https://github.com/Darkrock-Studios/ComposeTextEditor/blob/3589818fc669008ea13e222ff45c641c305bfa6a/docs/design/tables.md), [input filter implementation](https://github.com/Darkrock-Studios/ComposeTextEditor/blob/3589818fc669008ea13e222ff45c641c305bfa6a/ComposeTextEditor/src/commonMain/kotlin/com/darkrockstudios/texteditor/state/EditorInputFilter.kt)

There is useful infrastructure to learn from or reuse with attribution: chunked line/span storage, incremental layout, transaction rollback and platform input adaptation. These are coupled to its state and line model, so they are not automatically plug-compatible with our core. The practical reuse boundary must be a small adapter or utility with a defined interface, not two independent editable owners. [Architecture](https://github.com/Darkrock-Studios/ComposeTextEditor/blob/3589818fc669008ea13e222ff45c641c305bfa6a/docs/ARCHITECTURE.md)

Its testing document describes platform and browser suites, and the browser source contains disabled tests for composition after caret relocation. Those are useful known cases to share, not grounds to dismiss the library: our own recovery has also needed repairs. [Testing](https://github.com/Darkrock-Studios/ComposeTextEditor/blob/3589818fc669008ea13e222ff45c641c305bfa6a/docs/TESTING.md), [browser composition tests](https://github.com/Darkrock-Studios/ComposeTextEditor/blob/3589818fc669008ea13e222ff45c641c305bfa6a/browserTests/tests/composition.spec.ts)

## Other editor candidates

| Candidate | Evidence and relevance | Decision for this product |
|---|---|---|
| Mohamed Rejeb Compose Rich Editor | Current source has state-based BasicTextField integration, its own rich-text history and a paragraph/span document model. It is a strong existing rich-field library; old claims that it has no attributed history are obsolete. | Do not use it as the complete paginated hierarchical editor. No inspected implementation established the required integrated rich cell flows and common editable page/print plan. BasicTextField use alone is not a failure verdict. |
| Cascade Editor | Each text block owns a long-lived TextFieldState. Document-level selection is a set of block IDs, with focus and block selection mutually exclusive. That is useful Notion-style block interaction, but is not evidence of one native replacement covering partial text endpoints across blocks. | Do not substitute its block-selection mode for our cross-paragraph native text-selection/composition contract. Adapting the input ownership would be substantive work. Reuse ideas and codecs only where they match our model. |
| TextKit | Rope/piece-table state with rich features and BasicTextField UI. Its documented tables are embedded placeholders opened in a separate editable popup, with their own history. | The table UI/history is a product-behavior mismatch for in-place paginated document tables. Do not adopt as the owner of our complete document; it remains a valid simpler-editor candidate. |

Primary evidence: [Compose Rich Editor UI](https://github.com/MohamedRejeb/Compose-Rich-Editor/blob/b972d50f046615fcc96bc9bbac44e6e15eabdea7/richeditor-compose/src/commonMain/kotlin/com/mohamedrejeb/richeditor/ui/BasicRichTextEditor.kt), [paragraph model](https://github.com/MohamedRejeb/Compose-Rich-Editor/blob/b972d50f046615fcc96bc9bbac44e6e15eabdea7/richeditor-compose/src/commonMain/kotlin/com/mohamedrejeb/richeditor/paragraph/RichParagraph.kt), [Cascade block selection](https://github.com/linreal/cascade-editor/blob/29c4d4835aca9b6391fecd28b58cb5213d7a5d1d/docs/BlockSelectionOverview.md), [Cascade text-block hosting](https://github.com/linreal/cascade-editor/blob/29c4d4835aca9b6391fecd28b58cb5213d7a5d1d/editor/src/commonMain/kotlin/io/github/linreal/cascade/editor/ui/renderers/TextBlockField.kt), [TextKit table contract](https://github.com/jjrodcast/TextKit/blob/8163e757a379add25c8d2f7f1f89b30673cef3aa/docs/table/README.md).

These are present fit differences, not claims that maintainers could never extend their products. A deep fork could work, but it would combine a model migration with maintenance of a divergent editor. There is insufficient evidence that this is a smaller route than our existing core design. This is an engineering judgment, not an experimentally proven effort comparison.

## Pagination and PDF reuse

**Do not write a PDF file-format engine or a new Unicode shaper.** Retain existing typography infrastructure and adapt a qualified PDF backend. The custom work is the editable page-fragment model, its mappings to canonical content, and agreement between preview, output and native input.

PdfKmp is the strongest inspected pagination/export reuse candidate, but not an approved dependency yet. Its drawing interface accepts prewrapped text and page coordinates, and its JVM backend uses PDFBox text operators. Its layout uses its own metrics and wrapping; the JVM renderer applies separate bidi/Arabic processing. Feeding it our document as a second independent layout pass does not guarantee agreement with Compose editing geometry. A small same-page-plan bridge is the appropriate integration test, including Urdu/Arabic selection/extraction. [Drawing interface](https://github.com/ConaMobileDev/PdfKmp/blob/9985a335c4db0c0ee93d1f1d73468ced4e41de08/pdfkmp/src/commonMain/kotlin/com/conamobile/pdfkmp/render/PdfCanvas.kt), [layout](https://github.com/ConaMobileDev/PdfKmp/blob/9985a335c4db0c0ee93d1f1d73468ced4e41de08/pdfkmp/src/commonMain/kotlin/com/conamobile/pdfkmp/layout/TextLayout.kt), [JVM renderer](https://github.com/ConaMobileDev/PdfKmp/blob/9985a335c4db0c0ee93d1f1d73468ced4e41de08/pdfkmp/src/jvmMain/kotlin/com/conamobile/pdfkmp/render/JvmPdfCanvas.kt)

KmPDF's inspected desktop implementation renders each page to a BufferedImage and inserts that image into a PDF. This is useful screenshot export, but that path does not preserve document text as PDF text and therefore does not meet our searchable/selectable-text output requirement. It also expects the caller to supply pages. Do not adopt it as the full pagination/fidelity solution. [Desktop generator](https://github.com/big-jared/kmpdf/blob/c78cc86b264b8cb5f9ed4e2630f43c68bf72b1b0/kmpdf/src/jvmMain/kotlin/io/github/bigboyapps/kmpdf/KmPdfGenerator.jvm.kt)

KitePDF is a broader PDF/EPUB engine and possible later reader/writer component. Its public API is not evidence of a live rich-document editor with our page/IME mapping. Its shaping and writer parity were not audited deeply enough to select it over the narrower export candidate. [Repository at inspected revision](https://github.com/yuroyami/KitePDF/tree/d177c784e50e3f58e0220b6476c55c566ba7f678)

Existing web pagination is further evidence that the concept is established, but adopting a browser editor on every target would change the owner's native Compose requirement. A WebView replacement is not selected. No search establishes that no matching library exists anywhere; this decision applies to the strongest candidates inspected.

## Execution decision and stop conditions

1. Let Antigravity finish the current input recovery/lifecycle qualification. Do not discard it or introduce a second editor owner while that work is underway.
2. Continue the reusable library direction, separating the prototype shell from the document/session interfaces. Package extraction is useful only after the covered interfaces behave correctly; the prototype is not already a production headless KMP core.
3. Keep the initial published milestone bounded: continuous rich text with qualified input, formatting/history and Urdu/Arabic behavior. Hierarchical cells and paged output remain required later milestones; do not claim the full v5.1 scope complete early.
4. Before major pagination construction, execute GP0 as a single discriminating vertical slice: mixed English/Urdu/Arabic, a paragraph continuing to another page, a spanning table, editable selection/composition across the boundary, and PDF from the same layout plan. Pin fonts/resources. Check exported text as well as appearance. Evaluate PdfKmp here; do not approve it merely from the README.
5. If that slice cannot preserve typography, input and output agreement, stop pagination expansion and reopen the backend. Do not solve it by raster-only export, arbitrary paragraph chunking or silent feature removal.
6. Reuse upstream fixtures, qualified adapter patterns and utilities with their licenses/notices. Contribute concrete cross-project bug fixes when separately authorized; do not contact maintainers or publish material as part of this research.

No architecture decision removes the need for actual Android/iOS/Web/Desktop input and accessibility qualification. Do not spend months assuming those gates are solved. Scope changes, a new qualified upstream implementation, or demonstrated lower migration cost can reopen this recommendation; another generic request for novelty is not needed.

## Revision ledger

| Project | Inspected branch revision | Release metadata read |
|---|---|---|
| ComposeTextEditor | `3589818fc669008ea13e222ff45c641c305bfa6a` | `v3.1.0`, 9 October 2026; main two commits ahead |
| Compose Rich Editor | `b972d50f046615fcc96bc9bbac44e6e15eabdea7` | `v1.2.0`, 5 September 2026; release tag 177 commits behind main; do not infer artifact parity from main README |
| Cascade Editor | `29c4d4835aca9b6391fecd28b58cb5213d7a5d1d` | `v1.9.3`, 17 September 2026 |
| TextKit | `8163e757a379add25c8d2f7f1f89b30673cef3aa` | `1.0.0-rc02`, 1 October 2026; name is release-candidate even though API prerelease flag is false |
| PdfKmp | `9985a335c4db0c0ee93d1f1d73468ced4e41de08` | Source inspection; no release artifact executed |
| KmPDF | `c78cc86b264b8cb5f9ed4e2630f43c68bf72b1b0` | Source inspection; no release artifact executed |
| KitePDF | `d177c784e50e3f58e0220b6476c55c566ba7f678` | Limited source/API discovery; no artifact executed |

No prototype, specification, tag or dependency was changed for this research. The recommendation does not establish that building is cheaper in absolute terms; it establishes why the inspected editors are not presently substitutes for the full requested semantics without substantial changes.
