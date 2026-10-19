# ComposeDoc desktop workspace

Example consumer of `core:model`, `core:domain`, `core:data`, `core:layout` and
`core:ui`. Application toolbars/dialogs stay here; qualification code belongs in
`tests/desktop-harness`.

From the repository root with JDK 21 and Python:

```powershell
.\apps\desktop-workspace\write-urdu.cmd --workspace-sample
python tooling/verification/verify.py --workspace-milestone
```

The launcher builds current Gradle sources before starting. `run.cmd --check`
launches the separate harness through the compatibility `editor.MainKt` entry.
`write-urdu.cmd` is retained as a familiar launch name; ComposeDoc itself is
multilingual. Samples open as unsaved copies.

See [writing instructions](../../docs/guides/WRITING.md) and
[migration status](../../docs/reports/composedoc-migration-progress.md).
The former Maven build and original sources are preserved under `tests/archive`.
