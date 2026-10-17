"""Probe KF-2 and KF-3: Structural-break selection highlight and paragraphForOffset boundary crossing.

Demonstrates:
- KF-2: Selection across structural paragraph break produces 0 selection highlight and hides caret.
- KF-3: paragraphForOffset with leadingAffinity=true jumps across \\n into next paragraph,
        causing toLocalOffset to clamp negative offset (-1 -> 0) and place caret at start of next paragraph.
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
CLASSPATH_TXT = ROOT / "desktop-prototype" / "target" / "classpath.txt"

JAVA_SOURCE = """
import editor.*;
import java.util.*;

public class ProbeKf2Kf3Runner {
    public static void main(String[] args) {
        // Build document "ab\\ncd"
        List<TextUnit> units = new ArrayList<>();
        units.add(new TextUnit(1, 'a', UnitKind.TEXT, new TextStyleFlags(false, false)));
        units.add(new TextUnit(2, 'b', UnitKind.TEXT, new TextStyleFlags(false, false)));
        units.add(new TextUnit(3, '\\n', UnitKind.PARAGRAPH_BREAK, new TextStyleFlags(false, false)));
        units.add(new TextUnit(4, 'c', UnitKind.TEXT, new TextStyleFlags(false, false)));
        units.add(new TextUnit(5, 'd', UnitKind.TEXT, new TextStyleFlags(false, false)));
        Document doc = new Document(units, new TextStyleFlags(false, false));

        List<ParagraphSpan> spans = DesktopEditorKt.partitionParagraphs(doc);
        System.out.println("PARAGRAPH_COUNT=" + spans.size());
        for (int i = 0; i < spans.size(); i++) {
            ParagraphSpan p = spans.get(i);
            System.out.println("PARA_" + i + "_SPAN=" + p.getStartOffset() + ".." + p.getEndOffset() + " hasBreak=" + p.getHasBreak());
        }

        // KF-2: Selection over 2..3 (the newline character)
        Selection s = new Selection(2, 3, true);
        System.out.println("SELECTION=" + s.getStart() + ".." + s.getEnd() + " collapsed=" + s.getCollapsed());

        for (int i = 0; i < spans.size(); i++) {
            ParagraphSpan p = spans.get(i);
            int selStart = Math.max(s.getStart(), p.getStartOffset());
            int selEnd = Math.min(s.getEnd(), p.getEndOffset());
            boolean drawsHighlight = selStart < selEnd;
            System.out.println("PARA_" + i + "_SELECTION_CLIP=" + selStart + ".." + selEnd + " DRAWS_HIGHLIGHT=" + drawsHighlight);
        }
        System.out.println("CARET_VISIBLE=" + s.getCollapsed()); // in DocumentCanvas line 348: if (state.focused && state.blink && s.collapsed)

        // KF-3: paragraphForOffset on documentOffset = 2 (end of paragraph 0, before '\\n')
        // We simulate Geometry.paragraphForOffset logic directly from DesktopEditor.kt:145-160
        ParagraphSpan pTrail = spans.get(0); // trailing affinity (leadingAffinity = false)
        int localTrail = Math.max(0, Math.min(pTrail.getEndOffset() - pTrail.getStartOffset(), 2 - pTrail.getStartOffset()));

        // With leadingAffinity = true:
        int docOffset = 2;
        int chosenSpan = 0;
        if (docOffset == spans.get(0).getEndOffset() && spans.size() > 1) {
            chosenSpan = 1; // line 153: if (leadingAffinity && clamped == p.endOffset && idx + 1 < paragraphs.size) return paragraphs[idx + 1]
        }
        ParagraphSpan pLead = spans.get(chosenSpan);
        int rawDiff = docOffset - pLead.getStartOffset();
        int localLeadClamped = Math.max(0, Math.min(pLead.getEndOffset() - pLead.getStartOffset(), rawDiff));

        System.out.println("KF3_OFFSET=2_TRAILING_CHOSEN_PARA=0_LOCAL_OFFSET=" + localTrail);
        System.out.println("KF3_OFFSET=2_LEADING_CHOSEN_PARA=" + chosenSpan + "_RAW_DIFF=" + rawDiff + "_CLAMPED_LOCAL_OFFSET=" + localLeadClamped);
    }
}
"""

def main():
    if not CLASSPATH_TXT.exists():
        print("ERROR: classpath.txt missing; run desktop-prototype build first", file=sys.stderr)
        return 1
    cp = str(ROOT / "desktop-prototype" / "target" / "classes") + ";" + CLASSPATH_TXT.read_text().strip()
    work_dir = pathlib.Path(__file__).resolve().parent
    runner_java = work_dir / "ProbeKf2Kf3Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".;{cp}", "ProbeKf2Kf3Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout)
        if run_res.returncode != 0:
            print("Run failed:", run_res.stderr)
            return run_res.returncode
        return 0
    finally:
        if runner_java.exists():
            runner_java.unlink()
        runner_class = work_dir / "ProbeKf2Kf3Runner.class"
        if runner_class.exists():
            runner_class.unlink()

if __name__ == "__main__":
    sys.exit(main())
