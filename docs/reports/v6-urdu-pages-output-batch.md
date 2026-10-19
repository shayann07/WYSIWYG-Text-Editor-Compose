> PAUSED at owner request, 19 October 2026 21:22 PKT. Current implementation, failures, build and restart: [safe pause report](v6-pages-output-pause-20261019.md). Latest focused result FAIL after 14 passing groups (50-page layout performance). Semantic PDF BLOCKED; final integration/native NOT RUN. Earlier scaffold below is historical, superseded by that report.

# Urdu typography, editable pages and output - current implementation

19 October 2026. Assignment: `docs/prompts/v6-urdu-pages-pdf-print-big-batch.md`. Work is in progress; a stage is not PASS until its named evidence exists. Historical workspace/frozen files and old rejected experiments are preserved. No subagents, OS/font/driver installations, publishing, tags or license decision.

| Stage | Current state |
|---|---|
| 1 Editor-shaped output route | IN PROGRESS: at most two routes; actual installed API and mixed-script witness first |
| 2 Typography/specimen | Pending independent engineering and human-review sheet |
| 3 Immutable editable page plan | Pending |
| 4 Semantic PDF | Gated by stage1; no hidden-text/raster substitute |
| 5 Preview/printing | Pending; physical printing requires separate job authorization |
| Integrated and same-build native evidence | Pending; previous dialog tool limitation is not a PASS |

Policies settled before implementation: document units are96 units/inch; PDF points72/inch; screen zoom/density must be presentation transforms, not changes to page wrapping. Explicit page-break separators are semantic hard-paragraph boundaries with a distinct persisted break kind; authored soft breaks remain text LF. Paragraph keep/widow settings target existing half-open paragraph sets. Running header/footer flows are separate document-owned identities with raw text, a uniform supported text/paragraph style and derived page/total fields; they are never appended to the body. Different-first-page hides running content but keeps its reserved regions stable. No tables/sections/footnotes/justification are introduced. Unsupported output Unicode/font/bridge cases must fail explicitly without altering the source.

Exact restart: finish the two bounded capability checks, then independent stages2/3 even if semantic PDF remains blocked. Keep computer control off outside bounded native sessions.
