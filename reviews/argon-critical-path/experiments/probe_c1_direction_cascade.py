"""Probe C-1: Preceding-Paragraph Direction Inheritance Cascade.

Demonstrates:
- DesktopEditor.kt:199-202 propagates fallback = resolvedDir to subsequent paragraphs.
- Editing paragraph 0 from LTR to RTL flips the base direction of all subsequent neutral paragraphs,
  violating UAX #9 rule P3 and breaking O(1) incremental layout cache locality (ParagraphCacheKey).
"""
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
    public static void main(String[] args) {
        // Document: Para 0 is Latin ("Hello"), Para 1..5 are neutral numbers ("123")
        StringBuilder sbLtr = new StringBuilder("Hello\\n");
        for (int i = 1; i <= 5; i++) {
            sbLtr.append("123" + (i < 5 ? "\\n" : ""));
        }
        Document docLtr = makeDoc(sbLtr.toString());
        List<ParagraphSpan> spansLtr = DesktopEditorKt.partitionParagraphs(docLtr);

        // Simulate prototype measureParagraphs direction chaining
        ResolvedTextDirection fallbackLtr = ResolvedTextDirection.Ltr;
        List<ResolvedTextDirection> dirsBefore = new ArrayList<>();
        for (ParagraphSpan p : spansLtr) {
            ResolvedTextDirection dir = DesktopEditorKt.paragraphBaseDirection(p.getUnits(), fallbackLtr);
            fallbackLtr = dir;
            dirsBefore.add(dir);
        }
        System.out.println("BEFORE_EDIT_PARA_0_DIR=" + dirsBefore.get(0));
        System.out.println("BEFORE_EDIT_PARA_1_DIR=" + dirsBefore.get(1));
        System.out.println("BEFORE_EDIT_PARA_5_DIR=" + dirsBefore.get(5));

        // Now edit Para 0 to Arabic ("\\u0645\\u0631\\u062d\\u0628\\u0627")
        StringBuilder sbRtl = new StringBuilder("\\u0645\\u0631\\u062d\\u0628\\u0627\\n");
        for (int i = 1; i <= 5; i++) {
            sbRtl.append("123" + (i < 5 ? "\\n" : ""));
        }
        Document docRtl = makeDoc(sbRtl.toString());
        List<ParagraphSpan> spansRtl = DesktopEditorKt.partitionParagraphs(docRtl);

        ResolvedTextDirection fallbackRtl = ResolvedTextDirection.Ltr;
        List<ResolvedTextDirection> dirsAfter = new ArrayList<>();
        for (ParagraphSpan p : spansRtl) {
            ResolvedTextDirection dir = DesktopEditorKt.paragraphBaseDirection(p.getUnits(), fallbackRtl);
            fallbackRtl = dir;
            dirsAfter.add(dir);
        }
        System.out.println("AFTER_EDIT_PARA_0_DIR=" + dirsAfter.get(0));
        System.out.println("AFTER_EDIT_PARA_1_DIR=" + dirsAfter.get(1));
        System.out.println("AFTER_EDIT_PARA_5_DIR=" + dirsAfter.get(5));

        boolean para1Flipped = (dirsBefore.get(1) == ResolvedTextDirection.Ltr && dirsAfter.get(1) == ResolvedTextDirection.Rtl);
        boolean para5Flipped = (dirsBefore.get(5) == ResolvedTextDirection.Ltr && dirsAfter.get(5) == ResolvedTextDirection.Rtl);
        System.out.println("CASCADE_INVALIDATED_DOWNSTREAM_PARAS=" + (para1Flipped && para5Flipped));
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
}
"""

def main():
    if not CLASSPATH_TXT.exists():
        print("ERROR: classpath.txt missing; run desktop-prototype build first", file=sys.stderr)
        return 1
    cp = str(ROOT / "desktop-prototype" / "target" / "classes") + ";" + CLASSPATH_TXT.read_text().strip()
    work_dir = pathlib.Path(__file__).resolve().parent
    runner_java = work_dir / "ProbeC1Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".;{cp}", "ProbeC1Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout)
        if run_res.returncode != 0:
            print("Run failed:", run_res.stderr)
            return run_res.returncode
        return 0
    finally:
        if runner_java.exists():
            runner_java.unlink()
        runner_class = work_dir / "ProbeC1Runner.class"
        if runner_class.exists():
            runner_class.unlink()

if __name__ == "__main__":
    sys.exit(main())
