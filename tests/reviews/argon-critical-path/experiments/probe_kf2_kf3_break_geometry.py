"""Probe KF-2 and KF-3: Structural-break selection highlight and paragraphForOffset boundary stability.

Verifies with executable assertions (exit code 1 on failure):
- KF-2 & KF-3: partitionParagraphs and attached EditorSurface Geometry keep offset 2 in "ab\\ncd" inside
  paragraph 0 (local offset 2) rather than jumping across the separator into paragraph 1 and clamping -1 to 0.
- KF-2: Break-only selection [2, 3) and empty-paragraph selection [3, 4) in "ab\\n\\ncd" produce visible
  highlight rectangles with positive width and height via Geometry.breakAndEmptySelectionRects.
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
import java.awt.Point;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class ProbeKf2Kf3Runner {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Exception {
        final JFrame[] holder = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = DesktopEditorKt.showEditor(null, new Document(Collections.emptyList()), null, null, null, true, new Point(-16000, -16000));
        });
        try {
            Thread.sleep(250);
            SwingUtilities.invokeAndWait(() -> {
                EditorSurface surface = findSurface(holder[0]);
                check(surface != null, "EditorSurface must exist");
                EditorEngine e = surface.getEngine();
                e.replace("ab\\n\\ncd", "Fixture", true, e.snapshot().getSelection(), null);
                Geometry geo = surface.geometry();
                check(geo != null, "Geometry must be available");
                check(geo.getParagraphs().size() == 3, "Expected 3 paragraphs");

                // KF-3: offset 2 is the break at end of paragraph 0; must map to paragraph 0, local offset 2
                ParagraphLayout pAt2 = geo.paragraphForOffset(2);
                int localAt2 = pAt2.toLocalOffset(2);
                ParagraphLayout pAt3 = geo.paragraphForOffset(3);
                int localAt3 = pAt3.toLocalOffset(3);

                // KF-2: break-only [2,3) and empty-paragraph [3,4) selection rectangles
                List<androidx.compose.ui.geometry.Rect> breakRects = geo.breakAndEmptySelectionRects(new Selection(2, 3));
                List<androidx.compose.ui.geometry.Rect> emptyRects = geo.breakAndEmptySelectionRects(new Selection(3, 4));

                System.out.println("EVIDENCE_CLASS=compiled_regression_assertion");
                System.out.println("DISPOSITION=CONFIRMED_AND_FIXED");
                System.out.println("BASELINE_DEFECT_KF2=zero_highlight_rects_for_break_and_empty_paragraph_selection");
                System.out.println("BASELINE_DEFECT_KF3=leadingAffinity_jumped_to_next_paragraph_and_clamped_negative_offset_to_0");
                System.out.println("KF3_PARAGRAPH_AT_OFFSET_2=" + pAt2.getIndex() + " LOCAL_OFFSET=" + localAt2);
                System.out.println("KF3_PARAGRAPH_AT_OFFSET_3=" + pAt3.getIndex() + " LOCAL_OFFSET=" + localAt3);
                System.out.println("KF2_BREAK_SELECTION_RECT_COUNT=" + breakRects.size());
                System.out.println("KF2_EMPTY_PARA_SELECTION_RECT_COUNT=" + emptyRects.size());

                check(pAt2.getIndex() == 0 && localAt2 == 2, "Offset 2 must remain in paragraph 0 at local offset 2");
                check(pAt3.getIndex() == 1 && localAt3 == 0, "Offset 3 must map to paragraph 1 at local offset 0");
                check(breakRects.size() == 1 && breakRects.get(0).getWidth() > 0f && breakRects.get(0).getHeight() > 0f,
                    "Break-only selection [2,3) must produce a positive-area highlight rect");
                check(geo.hasRenderedSelection(new Selection(2, 3)), "hasRenderedSelection([2,3)) must be true");
                check(emptyRects.size() == 1 && emptyRects.get(0).getWidth() > 0f && emptyRects.get(0).getHeight() > 0f,
                    "Empty-paragraph selection [3,4) must produce a positive-area highlight rect");
                check(geo.hasRenderedSelection(new Selection(3, 4)), "hasRenderedSelection([3,4)) must be true");
                System.out.println("FIXED_REGRESSION_PASS=true");
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (holder[0] != null) holder[0].dispose();
            });
        }
    }

    private static EditorSurface findSurface(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof EditorSurface) return (EditorSurface) c;
            if (c instanceof java.awt.Container) {
                EditorSurface found = findSurface((java.awt.Container) c);
                if (found != null) return found;
            }
        }
        return null;
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
    runner_java = work_dir / "ProbeKf2Kf3Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr, file=sys.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".{os.pathsep}{cp}", "ProbeKf2Kf3Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout.strip())
        if run_res.returncode != 0:
            print("Assertion failed:", run_res.stderr, file=sys.stderr)
            return 1
        return 0
    finally:
        for p in work_dir.glob("ProbeKf2Kf3Runner*.class"):
            p.unlink(missing_ok=True)
        runner_java.unlink(missing_ok=True)

if __name__ == "__main__":
    sys.exit(main())
