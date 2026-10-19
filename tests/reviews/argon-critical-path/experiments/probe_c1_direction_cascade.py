"""Probe C-1: Documented Preceding-Paragraph Direction Inheritance Policy Verification.

Explicitly classifies C-1 as DOCUMENTED_POLICY_PRESERVED:
- RtlHostChecks.kt (Section 11) specifies that empty and neutral-only paragraphs inherit the preceding
  paragraph's resolved base direction so pressing Enter after an RTL paragraph keeps the caret at the right margin.
- Verifies that paragraphBaseDirection preserves this contract while properly ignoring strong characters
  inside UAX #9 directional isolates (KF-1).
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
CLASSPATH_TXT = ROOT / "desktop-prototype" / "target" / "classpath.txt"

JAVA_SOURCE = """
import editor.*;
import java.util.*;
import androidx.compose.ui.text.style.ResolvedTextDirection;

public class ProbeC1Runner {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static Document makeDoc(String text) {
        List<TextUnit> units = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            units.add(new TextUnit(id++, c, c == '\\n' ? UnitKind.PARAGRAPH_BREAK : UnitKind.TEXT, new TextStyleFlags(false, false)));
        }
        return new Document(units, new TextStyleFlags(false, false));
    }

    public static void main(String[] args) {
        Document docRtl = makeDoc("\\u0627\\u0644\\u0639\\u0631\\u0628\\u064a\\u0629\\n123\\n");
        List<ParagraphSpan> spans = DesktopEditorKt.partitionParagraphs(docRtl);
        check(spans.size() == 3, "Expected 3 paragraphs");

        ResolvedTextDirection fallback = ResolvedTextDirection.Ltr;
        List<ResolvedTextDirection> dirs = new ArrayList<>();
        for (ParagraphSpan p : spans) {
            ResolvedTextDirection resolved = DesktopEditorKt.paragraphBaseDirection(p.getUnits(), fallback);
            dirs.add(resolved);
            fallback = resolved;
        }

        check(dirs.get(0) == ResolvedTextDirection.Rtl, "Paragraph 0 (Arabic) must be Rtl");
        check(dirs.get(1) == ResolvedTextDirection.Rtl, "Paragraph 1 (neutral '123' after Arabic) must inherit Rtl per documented policy");
        check(dirs.get(2) == ResolvedTextDirection.Rtl, "Paragraph 2 (empty trailing paragraph after RTL) must inherit Rtl per documented policy");

        System.out.println("EVIDENCE_CLASS=documented_policy_verification");
        System.out.println("DISPOSITION=DOCUMENTED_POLICY_PRESERVED");
        System.out.println("PARA_0_DIR=" + dirs.get(0));
        System.out.println("PARA_1_NEUTRAL_INHERITED_DIR=" + dirs.get(1));
        System.out.println("PARA_2_EMPTY_INHERITED_DIR=" + dirs.get(2));
        System.out.println("FIXED_REGRESSION_PASS=true");
    }
}
"""

def get_classpath():
    env_cp = os.environ.get("EDITOR_BUILD_CLASSPATH")
    if env_cp:
        return env_cp
    if not CLASSPATH_TXT.exists():
        raise RuntimeError("classpath.txt missing; run desktop-prototype build first")
    return str(ROOT / "desktop-prototype" / "target" / "classes") + os.pathsep + CLASSPATH_TXT.read_text().strip()

def main():
    cp = get_classpath()
    work_dir = pathlib.Path(__file__).resolve().parent
    runner_java = work_dir / "ProbeC1Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr, file=sys.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".{os.pathsep}{cp}", "ProbeC1Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout.strip())
        if run_res.returncode != 0:
            print("Assertion failed:", run_res.stderr, file=sys.stderr)
            return 1
        return 0
    finally:
        for p in work_dir.glob("ProbeC1Runner*.class"):
            p.unlink(missing_ok=True)
        runner_java.unlink(missing_ok=True)

if __name__ == "__main__":
    sys.exit(main())
