"""Probe A-1: Unbounded Composition Lock / Permanent Editor Wedge.

Demonstrates:
- When composition is active, any attempt to perform editing actions (e.g. from shortcuts or toolbar)
  is trapped into the pending queue.
- Once pending reaches 256, all further edits are rejected with "Finish composing before another command."
- If the platform IME fails to emit an InputMethodEvent on endComposition(), composition is never cleared,
  and the editor is permanently wedged.
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
CLASSPATH_TXT = ROOT / "desktop-prototype" / "target" / "classpath.txt"

JAVA_SOURCE = """
import editor.*;
import java.util.*;

public class ProbeA1Runner {
    public static void main(String[] args) {
        EditorEngine eng = new EditorEngine(new Document(new ArrayList<>(), new TextStyleFlags(false, false)), () -> System.currentTimeMillis());
        eng.replace("hello", "Fixture", false, eng.snapshot().getSelection(), null);
        
        // Start IME composition: composing "k"
        eng.inputMethod("k", 0, 1);
        System.out.println("COMPOSITION_ACTIVE=" + (eng.snapshot().getMarked() != null));
        System.out.println("INITIAL_NOTICE=" + eng.snapshot().getNotice());

        // Queue 260 actions while IME is open (e.g. user clicking toolbar bold/italic or shortcuts)
        for (int i = 0; i < 260; i++) {
            eng.toggleFormat(FormatKey.BOLD);
        }

        System.out.println("WAITING_STATUS=" + eng.snapshot().getWaiting());
        System.out.println("FINAL_NOTICE=" + eng.snapshot().getNotice());
        boolean isWedged = "Finish composing before another command.".equals(eng.snapshot().getNotice());
        System.out.println("EDITOR_PERMANENTLY_WEDGED=" + isWedged);
    }
}
"""

def main():
    if not CLASSPATH_TXT.exists():
        print("ERROR: classpath.txt missing; run desktop-prototype build first", file=sys.stderr)
        return 1
    cp = str(ROOT / "desktop-prototype" / "target" / "classes") + ";" + CLASSPATH_TXT.read_text().strip()
    work_dir = pathlib.Path(__file__).resolve().parent
    runner_java = work_dir / "ProbeA1Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".;{cp}", "ProbeA1Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout)
        if run_res.returncode != 0:
            print("Run failed:", run_res.stderr)
            return run_res.returncode
        return 0
    finally:
        if runner_java.exists():
            runner_java.unlink()
        runner_class = work_dir / "ProbeA1Runner.class"
        if runner_class.exists():
            runner_class.unlink()

if __name__ == "__main__":
    sys.exit(main())
