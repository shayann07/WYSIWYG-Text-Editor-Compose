"""Probe C-2: Prototype Flat-Array Storage Scaling Measurement.

Measures flat-array Document.text allocation across 1,000 / 5,000 / 20,000 units and verifies
exact document length invariants (exit code 1 on failure).
Note: Flat-array storage is an intentional prototype vertical-slice limitation documented in EditorEngine.kt:48-49.
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

public class ProbeC2Runner {
    public static void main(String[] args) {
        int[] docSizes = {1000, 5000, 20000};
        System.out.println("EVIDENCE_CLASS=scaling_measurement");
        System.out.println("DISPOSITION=DOCUMENTED_PROTOTYPE_LIMITATION");
        for (int size : docSizes) {
            List<TextUnit> units = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                char c = (i % 80 == 79) ? '\\n' : 'a';
                units.add(new TextUnit(i + 1, c, c == '\\n' ? UnitKind.PARAGRAPH_BREAK : UnitKind.TEXT, new TextStyleFlags(false, false)));
            }
            Document doc = new Document(units, new TextStyleFlags(false, false));

            long t0 = System.nanoTime();
            for (int r = 0; r < 100; r++) {
                String s = doc.getText();
                if (s.length() != size) throw new AssertionError("Expected size " + size);
            }
            long durMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("DOC_SIZE=" + size + "_100_TEXT_READS_MS=" + durMs);
        }
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
    runner_java = work_dir / "ProbeC2Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr, file=sys.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".{os.pathsep}{cp}", "ProbeC2Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout.strip())
        if run_res.returncode != 0:
            print("Assertion failed:", run_res.stderr, file=sys.stderr)
            return 1
        return 0
    finally:
        for p in work_dir.glob("ProbeC2Runner*.class"):
            p.unlink(missing_ok=True)
        runner_java.unlink(missing_ok=True)

if __name__ == "__main__":
    sys.exit(main())
