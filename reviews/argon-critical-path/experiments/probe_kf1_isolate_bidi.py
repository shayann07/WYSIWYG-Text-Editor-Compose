"""Probe KF-1: UAX #9 Directional Isolate Handling in paragraphBaseDirection.

Compares prototype paragraphBaseDirection against JDK java.text.Bidi oracle
on strings with UAX #9 directional isolate characters (LRI, RLI, FSI, PDI).
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

public class ProbeKf1Runner {
    public static void main(String[] args) {
        // Case 1: RLI (\\u2067) + Arabic Alef (\\u0627) + PDI (\\u2069) + 'A'
        // The Arabic char is inside an isolate, so base direction must be determined by 'A' (LTR).
        String s1 = "\\u2067\\u0627\\u2069A";
        List<TextUnit> u1 = new ArrayList<>();
        for (int i = 0; i < s1.length(); i++) {
            u1.add(new TextUnit(i + 1, s1.charAt(i), UnitKind.TEXT, new TextStyleFlags(false, false)));
        }
        ResolvedTextDirection res1 = DesktopEditorKt.paragraphBaseDirection(u1, ResolvedTextDirection.Ltr);
        java.text.Bidi bidi1 = new java.text.Bidi(s1, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        boolean bidi1Ltr = bidi1.baseIsLeftToRight();

        // Case 2: LRI (\\u2066) + 'A' + PDI (\\u2069) + Arabic Alef (\\u0627)
        // 'A' is inside an isolate, so base direction must be determined by Alef (RTL).
        String s2 = "\\u2066A\\u2069\\u0627";
        List<TextUnit> u2 = new ArrayList<>();
        for (int i = 0; i < s2.length(); i++) {
            u2.add(new TextUnit(i + 1, s2.charAt(i), UnitKind.TEXT, new TextStyleFlags(false, false)));
        }
        ResolvedTextDirection res2 = DesktopEditorKt.paragraphBaseDirection(u2, ResolvedTextDirection.Ltr);
        java.text.Bidi bidi2 = new java.text.Bidi(s2, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        boolean bidi2Ltr = bidi2.baseIsLeftToRight();

        System.out.println("CASE1_INPUT=\\u2067\\u0627\\u2069A");
        System.out.println("CASE1_PROTO=" + res1);
        System.out.println("CASE1_ORACLE=" + (bidi1Ltr ? "Ltr" : "Rtl"));
        System.out.println("CASE1_MATCH=" + (res1 == (bidi1Ltr ? ResolvedTextDirection.Ltr : ResolvedTextDirection.Rtl)));

        System.out.println("CASE2_INPUT=\\u2066A\\u2069\\u0627");
        System.out.println("CASE2_PROTO=" + res2);
        System.out.println("CASE2_ORACLE=" + (bidi2Ltr ? "Ltr" : "Rtl"));
        System.out.println("CASE2_MATCH=" + (res2 == (bidi2Ltr ? ResolvedTextDirection.Ltr : ResolvedTextDirection.Rtl)));
    }
}
"""

def main():
    if not CLASSPATH_TXT.exists():
        print("ERROR: classpath.txt missing; run desktop-prototype build first", file=sys.stderr)
        return 1
    cp = str(ROOT / "desktop-prototype" / "target" / "classes") + ";" + CLASSPATH_TXT.read_text().strip()
    work_dir = pathlib.Path(__file__).resolve().parent
    runner_java = work_dir / "ProbeKf1Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".;{cp}", "ProbeKf1Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout)
        if run_res.returncode != 0:
            print("Run failed:", run_res.stderr)
            return run_res.returncode
        return 0
    finally:
        if runner_java.exists():
            runner_java.unlink()
        runner_class = work_dir / "ProbeKf1Runner.class"
        if runner_class.exists():
            runner_class.unlink()

if __name__ == "__main__":
    sys.exit(main())
