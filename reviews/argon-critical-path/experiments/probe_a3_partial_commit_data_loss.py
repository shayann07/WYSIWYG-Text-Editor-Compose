"""Probe A-3: Partial IME Commit Retained-Segment Deletion and Baseline Clobbering.

Demonstrates:
- Case 1: Selecting "abcd" (0..4), receiving partial commit inputMethod("Xcd", committedCount=1, caret=2),
  and calling abortComposition().
  Because EditorEngine splices dc with replacement.take(committedCount) over [session.start, session.end),
  calling abortComposition() leaves "X" instead of "Xcd" (silently deleting the retained suffix "cd").
- Case 2: Selecting "abcd" (0..4), receiving partial commit inputMethod("abX", committedCount=1, caret=2),
  and calling abortComposition().
  Leaves "a" instead of "abcd".
- Case 3: Paragraph break style contamination on Enter while bold is active.
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
CLASSPATH_TXT = ROOT / "desktop-prototype" / "target" / "classpath.txt"

JAVA_SOURCE = """
import editor.*;
import java.util.*;

public class ProbeA3Runner {
    public static void main(String[] args) {
        // Case 1: Retained suffix "cd" deletion on partial commit + abort
        EditorEngine eng1 = new EditorEngine(new Document(new ArrayList<>(), new TextStyleFlags(false, false)), () -> System.currentTimeMillis());
        eng1.replace("abcd", "Fixture", false, eng1.snapshot().getSelection(), null);
        eng1.move(new Selection(0, 4, true), true, TypingSide.LEFT);
        // IME converts "ab" -> "X" (committed), while keeping "cd" as uncommitted candidate
        eng1.inputMethod("Xcd", 1, 2);
        System.out.println("CASE1_PRE_ABORT_VISIBLE=" + eng1.snapshot().getVisible().getText());
        System.out.println("CASE1_PRE_ABORT_COMMITTED=" + eng1.snapshot().getCommitted().getText());
        System.out.println("CASE1_PRE_ABORT_MARKED=" + eng1.snapshot().getMarked());

        // Now abort composition (e.g. user presses Escape or focus lost)
        eng1.abortComposition();
        String afterAbort1 = eng1.snapshot().getVisible().getText();
        System.out.println("CASE1_POST_ABORT_VISIBLE=" + afterAbort1);
        System.out.println("CASE1_BUG_DATA_LOSS=" + ("X".equals(afterAbort1)));

        // Case 2: Retained prefix "ab" deletion on partial commit + abort
        EditorEngine eng2 = new EditorEngine(new Document(new ArrayList<>(), new TextStyleFlags(false, false)), () -> System.currentTimeMillis());
        eng2.replace("abcd", "Fixture", false, eng2.snapshot().getSelection(), null);
        eng2.move(new Selection(0, 4, true), true, TypingSide.LEFT);
        eng2.inputMethod("abX", 1, 2);
        eng2.abortComposition();
        String afterAbort2 = eng2.snapshot().getVisible().getText();
        System.out.println("CASE2_POST_ABORT_VISIBLE=" + afterAbort2);
        System.out.println("CASE2_BUG_DATA_LOSS=" + ("a".equals(afterAbort2)));

        // Case 3: PARAGRAPH_BREAK TextUnit style contamination
        EditorEngine eng3 = new EditorEngine(new Document(new ArrayList<>(), new TextStyleFlags(false, false)), () -> System.currentTimeMillis());
        eng3.replace("ab", "Fixture", false, eng3.snapshot().getSelection(), null);
        eng3.move(new Selection(1, 1, true), true, TypingSide.LEFT);
        eng3.toggleFormat(FormatKey.BOLD);
        eng3.replace("\\n", "Enter", true, eng3.snapshot().getSelection(), null);
        List<TextUnit> units = eng3.snapshot().getVisible().getUnits();
        System.out.println("CASE3_UNIT_COUNT=" + units.size());
        for (int i = 0; i < units.size(); i++) {
            TextUnit u = units.get(i);
            System.out.println("CASE3_UNIT_" + i + "_CHAR=" + ((int)u.getChar()) + "_KIND=" + u.getKind() + "_BOLD=" + u.getStyle().getBold());
        }
        boolean breakStyled = units.get(1).getStyle().getBold();
        System.out.println("CASE3_BREAK_CORRUPTED_WITH_BOLD=" + breakStyled);
    }
}
"""

def main():
    if not CLASSPATH_TXT.exists():
        print("ERROR: classpath.txt missing; run desktop-prototype build first", file=sys.stderr)
        return 1
    cp = str(ROOT / "desktop-prototype" / "target" / "classes") + ";" + CLASSPATH_TXT.read_text().strip()
    work_dir = pathlib.Path(__file__).resolve().parent
    runner_java = work_dir / "ProbeA3Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".;{cp}", "ProbeA3Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout)
        if run_res.returncode != 0:
            print("Run failed:", run_res.stderr)
            return run_res.returncode
        return 0
    finally:
        if runner_java.exists():
            runner_java.unlink()
        runner_class = work_dir / "ProbeA3Runner.class"
        if runner_class.exists():
            runner_class.unlink()

if __name__ == "__main__":
    sys.exit(main())
