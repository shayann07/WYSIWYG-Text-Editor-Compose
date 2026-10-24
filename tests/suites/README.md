# Verification suites

Executable suites and capability experiments. Suite documentation and diagnostic entry points are linked below. Run commands from the repository root; `python tooling/verification/verify.py` orchestrates the current checks (see [AGENTS.md](../../AGENTS.md)). Passing synthetic suites does not qualify native IME, accessibility or print fidelity.

## Current suites

| Directory | Scope |
|---|---|
| [`accessibility/jab/`](accessibility/jab/README.md) | Native Windows Java Access Bridge spike (F39 / M1) |
| [`input/windows/`](input/windows/README.md) | Windows native keyboard / IME qualification fixtures |
| [`integration/c0/`](integration/c0/README.md) | C0 candidate collaboration compatibility experiment |
| [`integration/s0/`](integration/s0/README.md) | S0 executable schema compatibility experiment |
| [`pagination/glyph_clusters/`](pagination/glyph_clusters/README.md) | SkShaper glyph/cluster capability and layout parity |
| [`pagination/gp0/`](pagination/gp0/README.md) | GP0 bounded page/output experiment |
| [`pagination/output_bridge/independent_rejection.py`](pagination/output_bridge/independent_rejection.py) | Diagnostic script for a rejected output candidate; use `--help`, then supply `--candidate`, `--plan` and a new `--output-dir` |
| [`pagination/pdf_outline/`](pagination/pdf_outline/README.md) | Rejected vector-outline PDF experiment |
| [`urdu/u0/`](urdu/u0/README.md) | Urdu U0 profile, corpus and pinned fonts |
| [`web/input/`](web/input/README.md) | M1 Web input/AT compatibility spike |

## Historical reference-model runs

- `antigravity/run-*`: earlier independent validation attempts against `plan-v4.md`; start with [run-20261013-01](antigravity/run-20261013-01/README.md). Their claims must be checked against later findings.
- `codex/run-*`: successive v5 reference-model repair runs. The latest is [run-20261016-20](codex/run-20261016-20/README.md); its 25 groups are re-run by `tests/reviews/v6-finalization-20261019/run-inherited.mjs`.

Files in these runs may be pinned by the v5 manifests or `gemini-input-hashes.json`; do not edit them. Paths and toolchains inside them describe the layout and machines at the time they were recorded; resolve old paths through [relocations.json](../../docs/relocations.json).

## Portable paths and historical evidence

Current commands use paths relative to the repository root. Disposable output and
recovery directories must be new and unused; an example path is not permission to
overwrite earlier writing or evidence. Windows environment locations such as
`%LOCALAPPDATA%` are platform defaults, not a required checkout drive.

Absolute drive paths and `file:///` links in dated reports, recorded assignments,
hash ledgers and archived reviews identify the original machine or execution.
They are historical evidence addresses, not portable launch instructions or proof
that an ignored receipt exists on a fresh clone. Preserve their bytes and identity.
Use [the relocation record](../../docs/relocations.json) and
[the path resolver](../../tooling/docs/doc_paths.py) for historical repository paths;
use current guides for launch commands. An intentional rejected URI in a negative
test is test input, not an executable path to replace.
