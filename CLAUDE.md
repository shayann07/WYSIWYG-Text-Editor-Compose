# ComposeDoc

Read [AGENTS.md](AGENTS.md) first. It is the shared instruction source for every
agent. The library is multilingual; Urdu/Nastaliq is an early qualification
workload. The owner's future commercial application is a separate deliverable.

From the repository root (JDK 21, Python 3.12, Node 24.20.0 per `.nvmrc`):

```sh
python tooling/docs/validate_docs.py
node tooling/verification/v6.mjs
./gradlew check apiCheck
python tooling/verification/verify.py --workspace-milestone
```

On Windows, launch with `apps\desktop-workspace\write-urdu.cmd --workspace-sample`.
Use `gradlew.bat` for Gradle in PowerShell.

Keep model/domain free of UI, Compose and filesystem dependencies. Data implements
domain ports; UI/layout cannot import workspace code. UI state flows out of the
single-owner engine and intents flow in. Do not introduce a second document owner.

Never change the bytes of the 48 historical or 33 sealed V6 files. Relocations
are recorded in `docs/amendments/composedoc-relocation-v1.json`. Run the integrity
verifier after edits. Never write PR titles/descriptions, issue bodies or review
replies for contributors: those are human-authored under CONTRIBUTING.md.

Passing synthetic tests does not qualify native IME, accessibility or printing.
No package publication or release tags are currently authorized.
