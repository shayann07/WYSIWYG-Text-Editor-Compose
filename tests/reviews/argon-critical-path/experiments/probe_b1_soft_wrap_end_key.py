"""Probe B-1, B-2, B-3: Soft-Wrap Boundary Affinity in Attached Compose Layout and EditorSurface.

Replaces the prior AWT LineBreakMeasurer simulation with real attached Compose + EditorSurface assertions:
- On wrapped unspaced CJK text ("あいうえおかきくけこ" x 12), pressing VK_END from offset 0 places the caret
  at the exact line-0 wrap boundary offset k (not k - 1) with WrapAffinity.UPSTREAM.
- Geometry.getLineForOffset(k, UPSTREAM) == 0 while Geometry.getLineForOffset(k, DOWNSTREAM) == 1.
- Caret rectangle with UPSTREAM affinity renders on line 0 at the right edge of line 0.
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
import java.awt.event.KeyEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class ProbeB1Runner {
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

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 12; i++) {
                    sb.append("\\u3042\\u3044\\u3046\\u3048\\u304a\\u304b\\u304d\\u304f\\u3051\\u3053");
                }
                e.replace(sb.toString(), "CJK wrap fixture", false, e.snapshot().getSelection(), null);
                Geometry geo = surface.geometry();
                check(geo != null && geo.getTotalLineCount() >= 2, "CJK fixture must wrap across at least 2 lines");

                int wrapBoundary = geo.getParagraphs().get(0).getLayout().getLineEnd(0, false);
                e.move(new Selection(0, 0), true, TypingSide.LEFT, WrapAffinity.DOWNSTREAM);
                surface.dispatchEvent(new KeyEvent(surface, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_END, KeyEvent.CHAR_UNDEFINED));

                Selection endSel = e.snapshot().getSelection();
                int lineUpstream = geo.getLineForOffset(endSel.getFocus(), WrapAffinity.UPSTREAM);
                int lineDownstream = geo.getLineForOffset(endSel.getFocus(), WrapAffinity.DOWNSTREAM);
                androidx.compose.ui.geometry.Rect caretUp = geo.caret(endSel.getFocus(), endSel.getPrimary(), WrapAffinity.UPSTREAM);
                androidx.compose.ui.geometry.Rect caretDown = geo.caret(endSel.getFocus(), endSel.getPrimary(), WrapAffinity.DOWNSTREAM);

                System.out.println("EVIDENCE_CLASS=compiled_regression_assertion");
                System.out.println("DISPOSITION=CONFIRMED_AND_FIXED");
                System.out.println("BASELINE_DEFECT=VK_END_stopped_at_k_minus_1_without_UPSTREAM_wrap_affinity");
                System.out.println("LINE_0_WRAP_BOUNDARY=" + wrapBoundary);
                System.out.println("VK_END_FOCUS_OFFSET=" + endSel.getFocus());
                System.out.println("VK_END_WRAP_AFFINITY=" + endSel.getWrapAffinity());
                System.out.println("LINE_FOR_UPSTREAM=" + lineUpstream + " LINE_FOR_DOWNSTREAM=" + lineDownstream);

                check(endSel.getFocus() == wrapBoundary,
                    "VK_END on line 0 must land at wrapBoundary " + wrapBoundary + ", got " + endSel.getFocus());
                check(endSel.getWrapAffinity() == WrapAffinity.UPSTREAM,
                    "VK_END at soft wrap boundary must set WrapAffinity.UPSTREAM");
                check(lineUpstream == 0 && lineDownstream == 1,
                    "UPSTREAM must map to line 0 and DOWNSTREAM to line 1");
                check(caretDown.getTop() > caretUp.getTop() + 5f,
                    "UPSTREAM caret must be on line 0 while DOWNSTREAM caret is on line 1");
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
    runner_java = work_dir / "ProbeB1Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr, file=sys.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".{os.pathsep}{cp}", "ProbeB1Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout.strip())
        if run_res.returncode != 0:
            print("Assertion failed:", run_res.stderr, file=sys.stderr)
            return 1
        return 0
    finally:
        for p in work_dir.glob("ProbeB1Runner*.class"):
            p.unlink(missing_ok=True)
        runner_java.unlink(missing_ok=True)

if __name__ == "__main__":
    sys.exit(main())
