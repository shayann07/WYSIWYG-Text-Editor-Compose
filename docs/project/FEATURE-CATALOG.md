# Urdu document application feature catalogue v1.0

17 October 2026. Companion to `docs/project/PRODUCT-ROADMAP.md`. This is the initial traceable inventory for the owner's Google Docs–level Urdu application, not a claim of exhaustive competitor parity or implementation. Every row remains part of the product roadmap unless the owner explicitly changes it.

All rows start **PLANNED at full-product scope**. Some desktop basics exist, but that does not qualify the complete feature across platforms. Record implementation/qualification per target in later reports; do not infer PASS from this table. Milestones M0–M8 are defined in the roadmap. “Design gate” means details must be decided, not that the feature is silently excluded.

## Editing and Urdu authoring

| ID | Capability | Primary owner / milestone | Minimum acceptance evidence |
|---|---|---|---|
| F01 | Typing, caret, keyboard/touch/mouse selection, selection across blocks | Library / M2–M3 | Exact native input, bidirectional selections, grapheme navigation/deletion and history on each target |
| F02 | Fonts/sizes, bold/italic/underline/strike, color/highlight, superscript/subscript, clear/paint formatting | Library + UI / M3 | Boundary typing, mixed styles, grapheme attribution, import and Undo fixtures |
| F03 | Paragraph styles, headings, alignment, spacing, indents, tabs/tab stops and RTL controls | Library / M3–M4 | Persisted semantic properties, style inheritance, ruler/keyboard commands, Nastaliq geometry |
| F04 | Bullets, numbering/restarts, checklists, nested lists and quotes, code blocks | Library / M3 | Multi-paragraph/nested ownership, split/join/indent/outdent, exact Undo |
| F05 | Cut/copy/paste, rich clipboard, plain paste, drag/drop | Library adapters / M3 | Format/resource preservation; async paste targets original selection or cancels explicitly |
| F06 | Per-user Undo/Redo and grouped edits | Library / M2; collaborative extension M6 | Local exact restoration; selective collaborative Undo preserves peers |
| F07 | Find/replace, selection-limited search, word/character counts | Library services + UI / M3 | Canonical-offset maps for optional Urdu-equivalence search; replacements are explicit undoable edits |
| F08 | Nastaliq presets, fallback fonts, Urdu/Arabic joining and diacritics | Typography + Urdu services / M1–M4 | U0–U4, licensed/pinned fonts and human-reviewed Urdu fixtures |
| F09 | Mixed Urdu/English/numbers/URLs, bidi isolates and invisible-character inspection | Library + UI / M2–M3 | No automatic text normalization; reversible visual movement and predictable logical selection |
| F10 | Urdu spelling, grammar/style diagnostics, custom dictionary, ignore/replace/autocorrect preferences | Urdu services / M7 | U5 benchmark corpus, false-positive reporting, explicit opt-in correction policy |
| F11 | Urdu keyboard aids, character palette, explicit normalization/transliteration and punctuation tools | Urdu services + UI / M7 | Previewed conversion report and single undoable command; no silent letter substitution |
| F12 | Voice typing/editing and optional read-aloud | Application providers / M7 | Urdu provider quality/latency evaluation, explicit microphone action and permission, correct text/command routing |

## Structure and professional documents

| ID | Capability | Primary owner / milestone | Minimum acceptance evidence |
|---|---|---|---|
| F13 | Images, positioning, wrap modes, cropping, captions and alt text | Library + UI / M3–M4 | Stable resources/anchors, no text loss on layout changes, print/AT fixtures |
| F14 | Tables: rich cells, rectangular selection, row/column operations, widths, headers, cell styles, TSV paste | Library / M4 | Cell-flow isolation, row continuation, RTL order, history and accessible navigation |
| F15 | Merge/split cells and table formatting/sorting | Library extension / M4 after S0 | Explicit span/grid model, reversible content placement, no duplicate cells or data loss; supersedes early-library deferral at application scope |
| F16 | Links, bookmarks, outline/navigation and generated table of contents | Library + UI / M3–M4 | Identity-safe targets, updateable derived fields, accessible activation, export |
| F17 | Document tabs/subtabs and cross-tab navigation | Application package + library flows / M3–M4 after S0 | Stable section identity, tab reorder/delete/restore, current/all-tab export and permissions |
| F18 | Continuous and paged mode, zoom, rulers and layout guides | Library surface / M4 | Switching presentation preserves document/history; page gaps are not text |
| F19 | Page size/orientation/margins, section/page breaks and columns | Library pagination / M4 | Persisted section properties, deterministic reflow, break mapping and Undo; multi-column behavior requires GP0 extension fixtures |
| F20 | Headers/footers, first-page/odd-even variants, page numbers, watermarks | Library pagination + UI / M4 | Canonical editable flows versus repeated visual instances; page-count convergence and output fidelity |
| F21 | Keep/widow/orphan rules, table/image continuation, page overflow diagnostics | Library pagination / M4 | Terminating constraints, exact content coverage, no hidden shrink/truncation |
| F22 | Footnotes, endnotes, citations and bibliography | Library references + application tools / M4–M7 | Stable references, pagination cycles bounded, Urdu/bilingual labels, editable sources and export |
| F23 | Equations, special characters and mathematical objects | Library extension + UI / M7 | Structured payload/accessible description, edit/render/export identity, mixed RTL/math fixtures |
| F24 | Drawings, diagrams, charts and linked data objects | Library embeds + application integrations / M7 | Defined update/version rules, offline fallback, accessible text, immutable export resource snapshot |
| F25 | Templates, reusable content blocks and formatting presets | Application / M3 basic; M7 advanced | Clone with fresh document identities; retain intended styles/resources; Urdu templates |

## Workspace, review and collaboration

| ID | Capability | Primary owner / milestone | Minimum acceptance evidence |
|---|---|---|---|
| F26 | Create/open/rename/duplicate/organize/search/star/trash/restore documents | Application workspace / M3 | Durable metadata/content association, atomic deletion/restore, recovery and migration |
| F27 | Local autosave, crash recovery and offline editing | Library persistence + application storage / M3 | Crash at write boundaries, recover committed state plus unfinished draft, honest save indicators |
| F28 | Accounts and cross-device cloud synchronization | Application / M6 | Durable idempotent operations, offline reconnect, error/retry and account separation |
| F29 | Sharing roles, invitations, link access, ownership and access requests | Application service / M6 | Server-enforced permissions and revocation; a mention alone never grants access |
| F30 | Simultaneous edits, presence/cursors and collaborator navigation | Replication integration + application / M6 | C0/C1 convergence and native composition tests, presence outside document history |
| F31 | Comment threads, replies, mentions, resolve/reopen and notifications | Review module + application / M5–M6 | Anchors survive moves or explicitly orphan; no silent retarget to unrelated text |
| F32 | Suggested insert/delete/replace/format/structural edits; accept/reject, bulk review | Review module / M5–M6 | Authored retained content, deterministic overlap/conflict outcomes, correct views and Undo |
| F33 | Persistent version history, named versions, attribution, compare and restore | Application history + engine restore / M5–M6 | Immutable revision/resource manifests; restore creates new revision, not destructive history rewind |
| F34 | Collaboration chat, reactions, assigned tasks and review notifications | Application / M6–M7 | Permission-aware delivery, retained versus ephemeral data policy, offline handling |
| F35 | Share/export snapshots, publish/read-only views, download/print controls | Application + output / M6–M7 | Explicit snapshot/access policy; documented limits of controls once a user already has data |

## Interchange, accessibility and ecosystem

| ID | Capability | Primary owner / milestone | Minimum acceptance evidence |
|---|---|---|---|
| F36 | Canonical format and loss-aware HTML/Markdown/plain-text import/export | Library codecs / M3–M7 | Round-trip supported schema, raw UTF-16 preservation internally, explicit unsupported-feature report |
| F37 | DOCX and ODT import/export; office-document round-trip | Codec integration / M7 | Declared supported profile, tracked-change/comment/table/Urdu fixtures; never promise arbitrary losslessness |
| F38 | Searchable/selectable PDF and platform printing matching paged preview | Library output / M4 | GP2 and U4, text extraction, pinned fonts, page geometry and real print-path tests |
| F39 | Screen readers, keyboard-only workflows, braille/platform support, magnification and high contrast | Library adapters + application / M2–M8 | Real AT reading/editing, review changes, tables/pages/offscreen targets on declared combinations |
| F40 | RTL/localized app chrome, menus/shortcuts, responsive mobile/tablet/desktop UX | Application / M3–M8 | Urdu-language usability review, focus order, no shortcut/input-layout collisions |
| F41 | Smart chips, mentions, dropdowns, dates, linked documents and building blocks | Extension APIs + application / M7 | Typed payloads, identity, fallback rendering/export and permission-aware resolution |
| F42 | Calendar/email/meeting/task/storage integrations | Application providers / M7–M8 design gate | Provider feasibility, authentication/scopes and user-controlled actions; Google-specific services are not assumed available |
| F43 | AI-assisted writing, summarization, translation and assistance | Optional application providers / M7–M8 design gate | Urdu quality evaluation, explicit invocation/data policy, suggestions rather than hidden rewrites; provider choice OPEN |
| F44 | Extension/plugin APIs, automation and external app integration | Library public APIs + application / M7–M8 | Stable command/receipt boundaries, capability checks, migration and failure containment |
| F45 | Installable open-source library, docs, samples, contribution and release process | Library distribution / M2 alpha; M8 stable | GL, license selected, external sample builds on each claimed platform, package/API compatibility |
| F46 | Backups, storage quotas, resource deduplication and operational diagnostics | Application + storage / M3–M8 | Durability/retention tests and actionable failures; telemetry/content capture policy explicit |

## Scope resolution rules

Rows F15, F17, F19, F22 and F37 require semantics beyond the earlier library slice; S0 and the v5.2 amendment make these intentional planning additions. Nested tables and multiple simultaneous local carets are not silently approved by this inventory: determine whether they are needed for the owner's concrete workflows/reference parity and record the owner's disposition before claiming full scope closure. This does not remove rich multi-paragraph cells or collaborative remote cursors.

AI/provider-dependent features remain visible targets with unresolved feasibility, cost and privacy choices. Core document editing must not require an AI account or network. Service-specific equivalents need explicit product decisions; agents cannot claim “every feature” while leaving these rows unresolved.

Each implementation task must name feature IDs, target platforms, acceptance fixtures and scope boundaries. No row is marked QUALIFIED merely because one low-level unit test passes. New feature discoveries append IDs; old IDs are not reused for unrelated work.

## Reference workflows checked on 17 October 2026

These official pages inform the catalogue, not an exhaustive product audit. Account/region/platform availability can differ.

- Google documents both paged and pageless modes: [page setup](https://support.google.com/docs/answer/11528737?hl=en).
- Tabs/subtabs, organization and current/all-tab printing are documented: [document tabs](https://support.google.com/docs/answer/15499791?hl=en-GB).
- Offline creation/editing and availability controls: [offline work](https://support.google.com/docs/answer/6388102?hl=en).
- Proposed edits and accept/reject workflows: [suggestions](https://support.google.com/docs/answer/6033474?hl=en).
- Change/version inspection: [version history](https://support.google.com/docs/answer/190843?hl=en).
- Source-backed citations/bibliography: [citations](https://support.google.com/docs/answer/10090962?hl=en).
- Typed people/file/date/event/place references and related blocks: [smart chips](https://support.google.com/docs/answer/10710316?hl=en).
- Speech input/editing: [voice typing](https://support.google.com/docs/answer/4492226?hl=en).
- Structured reading/editing and table/review navigation: [screen-reader workflows](https://support.google.com/docs/answer/1632201?hl=en).
- Document comparison: [compare documents](https://support.google.com/docs/answer/18512262?hl=en-GB).

Our Urdu requirements are owner-driven additions to these reference workflows, not claims that the reference product already qualifies Urdu/Nastaliq quality.
