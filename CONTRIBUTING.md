# Contributing to ComposeDoc

ComposeDoc is multilingual document-editing infrastructure for Compose
Multiplatform. Urdu/Nastaliq is an initial demanding qualification workload,
not a restriction on supported languages. Read AGENTS.md and the project
journal before changing code.

## Human accountability

AI-assisted code and tests are permitted. The human contributor must personally
read, understand and test every contributed line and explain its interaction
with Unicode, IME, transactions, history and architecture without deferring to
an AI tool. Tool output and green tests do not transfer responsibility.

PR titles/descriptions, issue bodies and human review replies must be written
directly by the human contributor in their own words. Do not submit agent
summaries, generated prose or boilerplate in place of your explanation.
Disclose AI tools and what you personally verified/refactored. Never ask an
agent to check a human attestation on your behalf.

Automated dependency PRs from dependabot[bot] are exempt from human-description
format checks, but still require the owner's review and passing checks. Clearly
identified bot review comments are permitted; bots must not replace human PR
descriptions or constitute the owner's approval.

CI checks section presence, attestation and known summary markers. It cannot
prove who authored prose or detect all AI use. Human review enforces that part
of the policy; do not represent heuristic checks as authorship certification.

## Engineering requirements

Use the module boundaries and verification commands in AGENTS.md. Include
feature IDs and affected V6 invariants. Preserve raw UTF-16, distinguish native
ranges from grapheme commands, and keep one authoritative transaction owner.
Never claim native IME, accessibility or PDF qualification from synthetic tests.

Historical frozen files are immutable. Use relocation records to move bytes
without changing them; propose a versioned amendment for new semantics. Discuss
new runtime dependencies and public API changes with the maintainer first.

Submit focused changes with failing-before/passing-after regression evidence
where applicable. Include exact commands and results. Only @shayann07 may
approve repository-wide architectural decisions and merge protected changes.

## Licensing

Contributions to the reusable library, validation suites and sample apps are
under Apache License 2.0 unless explicitly identified as third-party material.
Preserve upstream notices. The owner's future commercial/cloud application
retains a separate licensing boundary; see LICENSE and NOTICE.
