"""Probe A-1: Settlement Watchdog and Epoch Retirement Verification.

Explicitly separates:
- Headless queue-filling does NOT prove Windows OS callback loss (WINDOWS_OS_CALLBACK_LOSS_PROVEN=false).
- Verifies that when an IME fails to respond within the bounded watchdog window:
  1. checkSettlementWatchdog() cancels pending commands and sets settlementTimedOut=true without silently auto-committing preedit.
  2. abortComposition() / abortAndRetireComposition() increments imeEpoch and cleanly restores the document.
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

public class ProbeA1Runner {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        final long[] clock = new long[] { 1000L };
        EditorEngine eng = new EditorEngine(new Document(new ArrayList<>(), new TextStyleFlags(false, false)), () -> clock[0]);
        eng.replace("hello", "Fixture", false, eng.snapshot().getSelection(), null);

        // Start IME composition: composing "k"
        eng.inputMethod("k", 0, 1);
        check(eng.snapshot().getMarked() != null, "Composition must be active");

        // Queue a command while IME is active
        eng.toggleFormat(FormatKey.BOLD);
        check(eng.snapshot().getWaiting(), "Engine must enter waiting state");
        check(!eng.getSettlementTimedOut(), "Watchdog must not time out before threshold");

        // Advance clock past watchdog threshold (2000ms) without delivering a native IME callback
        clock[0] += 2500L;
        boolean timedOut = eng.checkSettlementWatchdog(2000L);
        check(timedOut && eng.getSettlementTimedOut(), "Watchdog must report timeout after threshold");
        check(!eng.snapshot().getWaiting(), "Pending queue must be cleared on watchdog timeout");
        check(eng.snapshot().getMarked() != null, "Watchdog must NOT silently auto-commit or discard preedit");
        check("hello".equals(eng.snapshot().getCommitted().getText()), "Committed document must remain 'hello'");

        // Explicit recovery via abortComposition increments imeEpoch and restores pre-session text
        long epochBefore = eng.getImeEpoch();
        eng.abortComposition();
        check(eng.getImeEpoch() == epochBefore + 1, "imeEpoch must increment on abort");
        check(eng.snapshot().getMarked() == null && !eng.getSettlementTimedOut(), "Composition and timeout cleared");
        check("hello".equals(eng.snapshot().getVisible().getText()), "Visible text must restore to 'hello'");

        System.out.println("EVIDENCE_CLASS=compiled_regression_assertion");
        System.out.println("WINDOWS_OS_CALLBACK_LOSS_PROVEN=false (headless probe tests bounded watchdog & epoch recovery, not OS IME failure)");
        System.out.println("WATCHDOG_CANCELLED_PENDING_WITHOUT_AUTOCOMMIT=true");
        System.out.println("EPOCH_RETIREMENT_RESTORED_DOCUMENT=true");
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
    runner_java = work_dir / "ProbeA1Runner.java"
    runner_java.write_text(JAVA_SOURCE, encoding="utf-8")
    try:
        compile_res = subprocess.run(["javac", "-cp", cp, str(runner_java)], capture_output=True, text=True)
        if compile_res.returncode != 0:
            print("Compile failed:", compile_res.stderr, file=sys.stderr)
            return 1
        run_res = subprocess.run(["java", "-cp", f".{os.pathsep}{cp}", "ProbeA1Runner"], cwd=str(work_dir), capture_output=True, text=True)
        print(run_res.stdout.strip())
        if run_res.returncode != 0:
            print("Assertion failed:", run_res.stderr, file=sys.stderr)
            return 1
        return 0
    finally:
        for p in work_dir.glob("ProbeA1Runner*.class"):
            p.unlink(missing_ok=True)
        runner_java.unlink(missing_ok=True)

if __name__ == "__main__":
    sys.exit(main())
