"""Probe A-3 & A-4: Segmented Overlay Partial-Commit Preservation and Empty-Paragraph Style Separation.

Verifies with executable assertions (exit code 1 on failure):
- Case 1 (A-3): "abcd", select [0,4), inputMethod("Xcd", 1, 2), abortComposition() -> "Xcd" (baseline defect produced "X").
- Case 2 (A-3): "abcd", select [0,4), inputMethod("abX", 1, 2), abortComposition() -> "abcd" (baseline defect produced "a").
- Case 3 (A-3): Repeated partial commits "abcd", select [0,4), inputMethod("aXcd", 1, 1) -> inputMethod("Ycd", 1, 1) -> abortComposition() -> "aYcd", Undo -> "abcd", Redo -> "aYcd".
- Case 4 (A-4): PARAGRAPH_BREAK unit.style is strictly unstyled TextStyleFlags(false, false), while unit.paragraphStyle preserves empty-paragraph typing metadata.
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

public class ProbeA3Runner {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        // Case 1: "abcd", select [0,4), inputMethod("Xcd", 1, 2), abortComposition() -> "Xcd"
        EditorEngine eng1 = new EditorEngine();
        eng1.replace("abcd", "Fixture", false, eng1.snapshot().getSelection(), null);
        eng1.move(new Selection(0, 4, true), true, TypingSide.LEFT, WrapAffinity.DOWNSTREAM);
        eng1.inputMethod("Xcd", 1, 2);
        eng1.abortComposition();
        String case1AfterAbort = eng1.snapshot().getVisible().getText();
        check("Xcd".equals(case1AfterAbort), "Case 1 expected 'Xcd', got '" + case1AfterAbort + "'");
        eng1.perform(EditAction.Undo.INSTANCE);
        check("abcd".equals(eng1.snapshot().getVisible().getText()), "Case 1 Undo must restore 'abcd'");
        eng1.perform(EditAction.Redo.INSTANCE);
        check("Xcd".equals(eng1.snapshot().getVisible().getText()), "Case 1 Redo must restore 'Xcd'");

        // Case 2: "abcd", select [0,4), inputMethod("abX", 1, 2), abortComposition() -> "abcd"
        EditorEngine eng2 = new EditorEngine();
        eng2.replace("abcd", "Fixture", false, eng2.snapshot().getSelection(), null);
        eng2.move(new Selection(0, 4, true), true, TypingSide.LEFT, WrapAffinity.DOWNSTREAM);
        eng2.inputMethod("abX", 1, 2);
        eng2.abortComposition();
        String case2AfterAbort = eng2.snapshot().getVisible().getText();
        check("abcd".equals(case2AfterAbort), "Case 2 expected 'abcd', got '" + case2AfterAbort + "'");

        // Case 3: Repeated partial commits across retained and replacement segments
        EditorEngine eng3 = new EditorEngine();
        eng3.replace("abcd", "Fixture", false, eng3.snapshot().getSelection(), null);
        eng3.move(new Selection(0, 4, true), true, TypingSide.LEFT, WrapAffinity.DOWNSTREAM);
        eng3.inputMethod("aXcd", 1, 1);
        eng3.inputMethod("Ycd", 1, 1);
        eng3.abortComposition();
        String case3AfterAbort = eng3.snapshot().getVisible().getText();
        check("aYcd".equals(case3AfterAbort), "Case 3 expected 'aYcd', got '" + case3AfterAbort + "'");
        eng3.perform(EditAction.Undo.INSTANCE);
        check("abcd".equals(eng3.snapshot().getVisible().getText()), "Case 3 Undo must restore 'abcd'");
        eng3.perform(EditAction.Redo.INSTANCE);
        check("aYcd".equals(eng3.snapshot().getVisible().getText()), "Case 3 Redo must restore 'aYcd'");

        // Case 4 (A-4): Empty-paragraph style separation from PARAGRAPH_BREAK inline style
        EditorEngine eng4 = new EditorEngine();
        eng4.toggleFormat(FormatKey.BOLD);
        eng4.replace("A", "Typing", false, eng4.snapshot().getSelection(), null);
        eng4.replace("\\n", "Enter", true, eng4.snapshot().getSelection(), null);
        TextUnit breakUnit = eng4.snapshot().getVisible().getUnits().get(1);
        check(breakUnit.getKind() == UnitKind.PARAGRAPH_BREAK, "Unit 1 must be PARAGRAPH_BREAK");
        check(!breakUnit.getStyle().getBold() && !breakUnit.getStyle().getItalic(),
            "PARAGRAPH_BREAK inline style must be unstyled");
        check(breakUnit.getParagraphStyle().getBold(),
            "PARAGRAPH_BREAK paragraphStyle must preserve empty-paragraph bold metadata");
        check(eng4.snapshot().getTypingStyle().getBold(),
            "Empty paragraph following bold Enter must inherit bold typingStyle");

        System.out.println("EVIDENCE_CLASS=compiled_regression_assertion");
        System.out.println("DISPOSITION=CONFIRMED_AND_FIXED");
        System.out.println("BASELINE_DEFECT_CASE1=X_instead_of_Xcd");
        System.out.println("BASELINE_DEFECT_CASE2=a_instead_of_abcd");
        System.out.println("CASE1_AFTER_ABORT=" + case1AfterAbort);
        System.out.println("CASE2_AFTER_ABORT=" + case2AfterAbort);
        System.out.println("CASE3_REPEATED_PARTIAL_AFTER_ABORT=" + case3AfterAbort);
        System.out.println("CASE4_BREAK_INLINE_BOLD=" + breakUnit.getStyle().getBold() + " PARAGRAPH_STYLE_BOLD=" + breakUnit.getParagraphStyle().getBold());
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
    runner_java = work_dir / "ProbeA3Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr, file=sys.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".{os.pathsep}{cp}", "ProbeA3Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout.strip())
        if run_res.returncode != 0:
            print("Assertion failed:", run_res.stderr, file=sys.stderr)
            return 1
        return 0
    finally:
        for p in work_dir.glob("ProbeA3Runner*.class"):
            p.unlink(missing_ok=True)
        runner_java.unlink(missing_ok=True)

if __name__ == "__main__":
    sys.exit(main())
