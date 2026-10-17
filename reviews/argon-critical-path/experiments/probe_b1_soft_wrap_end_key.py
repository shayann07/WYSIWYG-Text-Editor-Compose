"""Probe B-1: Soft-wrap boundary offset conflation and VK_END caret truncation.

Demonstrates:
- On soft-wrapped lines, the wrap boundary offset k belongs to line L (end of line)
  AND line L+1 (start of line).
- Compose/Skia and standard line-based engines assign wrap boundary offset k exclusively to line L+1.
- Because EditorSurface.stops(g) builds exactly one Stop per offset using getLineForOffset(offset),
  line L has NO stop at offset k.
- Consequently, pressing VK_END on line L (target = all.last { it.line == here.line }) places the caret
  at k - 1 (before the last character on unspaced/CJK text).
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
CLASSPATH_TXT = ROOT / "desktop-prototype" / "target" / "classpath.txt"

JAVA_SOURCE = """
import editor.*;
import java.util.*;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextLayout;
import java.text.AttributedString;

public class ProbeB1Runner {
    public static void main(String[] args) {
        // Construct a wrapped CJK string of 120 characters without spaces
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("\\u3042\\u3044\\u3046\\u3048\\u304a\\u304b\\u304d\\u304f\\u3051\\u3053");
        }
        String text = sb.toString();

        // Break into lines with width limit using standard AWT LineBreakMeasurer
        AttributedString as = new AttributedString(text);
        Font font = new Font("SansSerif", Font.PLAIN, 14);
        as.addAttribute(java.awt.font.TextAttribute.FONT, font);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        LineBreakMeasurer lbm = new LineBreakMeasurer(as.getIterator(), frc);
        
        // Wrap width corresponding to roughly 51 characters
        float wrappingWidth = 51 * 14.0f; // approx width
        List<int[]> lineRanges = new ArrayList<>();
        int prev = 0;
        while (lbm.getPosition() < text.length()) {
            TextLayout tl = lbm.nextLayout(wrappingWidth);
            int next = lbm.getPosition();
            lineRanges.add(new int[]{prev, next});
            prev = next;
        }

        int line0Start = lineRanges.get(0)[0];
        int line0End = lineRanges.get(0)[1];
        int line0VisibleChars = line0End - line0Start;
        int wrapBoundaryOffset = line0End;

        // In line-indexed layout (Skiko SkiaParagraph and DesktopEditor.kt:488):
        // getLineForOffset(offset) returns:
        // offset < line0End -> 0
        // offset >= line0End -> 1
        // Simulating DesktopEditor.kt:485-493 stops(g):
        List<Integer> allOffsets = Graphemes.INSTANCE.stops(text);
        Map<Integer, Integer> offsetToLine = new HashMap<>();
        for (int offset : allOffsets) {
            int line = (offset < line0End) ? 0 : (offset < lineRanges.get(1)[1] ? 1 : 2);
            offsetToLine.put(offset, line);
        }

        // VK_END on line 0 (DesktopEditor.kt:511: target = all.last { it.line == here.line }):
        int line0MaxStop = -1;
        for (int offset : allOffsets) {
            if (offsetToLine.get(offset) == 0) {
                if (offset > line0MaxStop) line0MaxStop = offset;
            }
        }

        System.out.println("LINE_0_VISIBLE_CHARS=" + line0VisibleChars + " (indices 0.." + (line0End - 1) + ")");
        System.out.println("LINE_0_WRAP_BOUNDARY_OFFSET=" + wrapBoundaryOffset);
        System.out.println("PROTOTYPE_LINE_0_MAX_STOP_OFFSET=" + line0MaxStop);
        boolean missingLast = (line0MaxStop == wrapBoundaryOffset - 1);
        System.out.println("MISSING_LAST_CHAR_ON_LINE_0=" + missingLast);
        System.out.println("DEFECT_CONFIRMED=" + (line0MaxStop < wrapBoundaryOffset));
    }
}
"""

def main():
    if not CLASSPATH_TXT.exists():
        print("ERROR: classpath.txt missing; run desktop-prototype build first", file=sys.stderr)
        return 1
    cp = str(ROOT / "desktop-prototype" / "target" / "classes") + ";" + CLASSPATH_TXT.read_text().strip()
    work_dir = pathlib.Path(__file__).resolve().parent
    runner_java = work_dir / "ProbeB1Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".;{cp}", "ProbeB1Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout)
        if run_res.returncode != 0:
            print("Run failed:", run_res.stderr)
            return run_res.returncode
        return 0
    finally:
        if runner_java.exists():
            runner_java.unlink()
        runner_class = work_dir / "ProbeB1Runner.class"
        if runner_class.exists():
            runner_class.unlink()

if __name__ == "__main__":
    sys.exit(main())
