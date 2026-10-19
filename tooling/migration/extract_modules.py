"""Extract the existing owner, values, platform adapters and shell without a rewrite.

Run once after relocation. All pre-extraction source bytes are archived first.
This is a migration utility, not a build-time source transformer.
"""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "apps/desktop-workspace/src/main/kotlin/editor"
BASE = "dev.shayxo.composedoc"
DEPS = {
    "model": [], "domain": ["model"], "data": ["model", "domain"],
    "layout": ["model", "domain"], "ui": ["model", "domain", "layout"],
    "workspace": ["model", "domain", "data", "layout", "ui"],
    "harness": ["model", "domain", "data", "layout", "ui", "workspace"],
}


def write(module, name, body, common=False):
    project = "apps/desktop-workspace" if module == "workspace" else "tests/desktop-harness" if module == "harness" else "core/" + module
    source_set = "commonMain" if common else "jvmMain"
    path = ROOT / project / "src" / source_set / "kotlin" / BASE.replace(".", "/") / module / name
    path.parent.mkdir(parents=True, exist_ok=True)
    assert not path.exists(), path
    body = re.sub(r"^package editor\s*", "", body, count=1)
    imports = "\n".join("import " + BASE + "." + dep + ".*" for dep in DEPS[module])
    # Public experimental cross-module surfaces are checked into .api files.
    # Private implementation details remain private. No compiler friend-path bypass.
    body = re.sub(r"\binternal\s+", "", body)
    path.write_text("package " + BASE + "." + module + "\n\n" + imports + "\n" + body, encoding="utf-8")


def main():
    assert SOURCE.is_dir()
    archive = ROOT / "tests/archive/pre-gradle-source-20261019"
    assert not archive.exists(), "Extraction already started"
    archive.mkdir(parents=True)
    files = {}
    for path in SOURCE.glob("*.kt"):
        files[path.name] = path.read_text(encoding="utf-8")
        (archive / path.name).write_bytes(path.read_bytes())
    (archive / "hashes.json").write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCE.glob("*.kt")}, indent=2) + "\n")

    def take(name):
        return files.pop(name)

    engine = take("EditorEngine.kt")
    values, engine = engine.split("data class EditorFrame", 1)
    values = values.replace("import java.util.ArrayDeque", "")
    write("model", "Document.kt", values, True)
    engine = "data class EditorFrame" + engine
    engine = engine.replace("DocumentFiles.MAX_UNITS", "DocumentLimits.MAX_UNITS")
    engine = engine.replace("Thread.currentThread()", "ownerThreadToken()")
    engine = engine.replace("System.nanoTime() / 1_000_000", "monotonicMillis()")
    write("domain", "EditorEngine.kt", engine, True)

    structural = take("StructuredDocument.kt").replace("import java.util.UUID", "")
    structural = structural.replace("UUID.randomUUID().toString()", "newStructureIdentity()")
    structural = structural.replace("java.util.Base64.getDecoder().decode(data)", "kotlin.io.encoding.Base64.decode(data)")
    structural = structural.replace("runCatching { java.net.URI(uri).isAbsolute }.getOrDefault(false)", "isAbsoluteDocumentUri(uri)")
    structural = structural.replace('runCatching { java.net.URI(uri).scheme.lowercase() in setOf("http","https","mailto") }.getOrDefault(false)', 'uri.substringBefore(\':\').lowercase() in setOf("http","https","mailto")')
    structural = structural.replace("DocumentFiles.MAX_UNITS", "DocumentLimits.MAX_UNITS")
    model, commands = structural.split("sealed interface StructureCommand", 1)
    commands, model_tail = commands.split("fun Document.plainText()", 1)
    # Domain reducer remains separate from canonical value/tree integrity helpers.
    write("model", "Structure.kt", model + "\nfun Document.plainText()" + model_tail, True)
    write("domain", "StructureCommands.kt", "sealed interface StructureCommand" + commands, True)
    write("model", "Formatting.kt", take("Formatting.kt"), True)
    write("model", "ParagraphProperties.kt", take("ParagraphProperties.kt"), True)
    policies = take("PagePolicies.kt").replace("java.util.UUID.randomUUID().toString()", "newStructureIdentity()")
    policies = policies.replace("java.util.UUID.fromString(id).toString()==id", "isStructureIdentity(id)")
    write("model", "PagePolicies.kt", policies, True)
    workspace = take("DocumentWorkspace.kt")
    start = workspace.index("enum class PaperSize")
    end = workspace.index("/** Independent unsaved")
    write("model", "PageSettings.kt", workspace[start:end], True)
    write("data", "DocumentWorkspace.kt", workspace[:start] + workspace[end:])

    find = take("FindReplace.kt")
    algorithm, session = find.split("internal class FindSession", 1)
    write("domain", "FindReplace.kt", algorithm, True)
    write("ui", "FindSession.kt", "internal class FindSession" + session)
    clipboard = take("RichClipboard.kt")
    pure, codec = clipboard.split("/** Bounded, versioned fragment", 1)
    pure = pure.replace("import java.awt.datatransfer.*", "").replace("import java.io.*", "")
    write("domain", "RichFragment.kt", pure, True)
    codec, adapter = codec.split("/** AWT adapter", 1)
    write("data", "FragmentCodec.kt", "import java.io.*\n/** Bounded, versioned fragment" + codec)
    write("ui", "RichClipboard.kt", "import java.awt.datatransfer.*\nimport java.io.*\n/** AWT adapter" + adapter)

    desktop = take("DesktopEditor.kt")
    imports = desktop[:desktop.index("private fun displayText")]
    rendering, host = desktop.split("enum class RecoveryStatus", 1)
    shell = "fun windowFiles" + host.split("fun windowFiles", 1)[1]
    host = "enum class RecoveryStatus" + host.split("fun windowFiles", 1)[0]
    # Geometry and text projections have no dependency on the surface/window.
    projection, rest = rendering.split("class ViewState", 1)
    view, render = rest.split("fun referenceMeasure", 1)
    reference, render = render.split("@Composable", 1)
    projection = projection.replace("private fun displayText", "fun displayText").replace("private fun paragraphAlignment", "fun paragraphAlignment")
    write("layout", "Geometry.kt", projection + "\nfun referenceMeasure" + reference)
    write("ui", "EditorViewState.kt", imports + "class ViewState" + view)
    write("ui", "EditorCanvas.kt", imports + "@Composable" + render.replace("private fun DocumentCanvas", "fun DocumentCanvas"))
    write("ui", "EditorSurface.kt", imports + host)
    write("workspace", "DesktopWorkspace.kt", imports + shell)

    recovery = take("LocalRecovery.kt")
    storage, controller = recovery.split("internal class LocalRecoveryController", 1)
    write("data", "LocalRecovery.kt", storage)
    write("workspace", "LocalRecoveryController.kt", "import java.io.*\nimport java.nio.file.*\nimport java.util.*\nimport javax.swing.*\ninternal class LocalRecoveryController" + controller)
    unfinished = take("UnfinishedRecovery.kt")
    storage, controller = unfinished.split("internal class UnfinishedController", 1)
    write("data", "UnfinishedRecovery.kt", storage)
    write("workspace", "UnfinishedController.kt", "import java.io.*\nimport java.nio.file.*\nimport java.util.*\nimport javax.swing.*\ninternal class UnfinishedController" + controller)

    # Subsequent portable-model transformation generates pinned Unicode tables;
    # do not accidentally include the original JVM resource loader in commonMain.
    write("model", "Graphemes.kt", take("Graphemes.kt"))
    layout = {"Typography.kt", "ListTypography.kt", "PageFragments.kt", "OutputPagePlan.kt", "PrintSnapshot.kt", "FontEvidence.kt"}
    data = {"DocumentFiles.kt", "WorkspaceStorage.kt"}
    ui = {"ImeTrace.kt", "ImageWorkflow.kt"}
    shell_files = {"WindowFiles.kt", "LocalRecoveryUi.kt", "UnfinishedRecoveryUi.kt", "WorkspaceUi.kt", "ResponsiveControls.kt", "OutlineLabels.kt", "FindReplaceUi.kt", "ParagraphControls.kt", "StructureControls.kt", "WritingControls.kt", "ValidatedControls.kt", "PageOutputUi.kt", "UrduWriting.kt", "StructuredSample.kt", "WorkspaceSample.kt", "PagesSample.kt"}
    # Image validation/cache are split explicitly in the subsequent port pass.
    data.add("ImageResources.kt")
    for name, body in files.items():
        module = "layout" if name in layout else "data" if name in data else "ui" if name in ui else "workspace" if name in shell_files else "harness"
        write(module, name, body)
    # Delete only files individually archived and hash-checked, not an unchecked tree.
    for path in SOURCE.glob("*.kt"):
        assert path.read_bytes() == (archive / path.name).read_bytes()
        path.unlink()
    print("Extracted", len(list(archive.glob("*.kt"))), "sources; compile and port review required")


if __name__ == "__main__":
    main()
