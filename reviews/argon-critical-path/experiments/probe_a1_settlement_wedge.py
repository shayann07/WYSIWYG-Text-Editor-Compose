"""Probe A-1: Settlement Watchdog and Ordinary AWT Host/InputContext Retirement Verification.

Explicitly separates:
- Headless queue-filling does NOT prove Windows OS callback loss (WINDOWS_OS_CALLBACK_LOSS_PROVEN=false).
- Verifies that when an IME fails to respond within the bounded watchdog window:
  1. checkSettlementWatchdog() preserves its deadline across non-terminal candidate updates, cancels pending commands on timeout, and sets settlementTimedOut=true without silently auto-committing preedit.
  2. abortAndRetireComposition() establishes the retirement boundary before rollback, rejecting both reentrant and delayed ordinary AWT InputMethodEvent callbacks while installing a fresh replacement host for subsequent input (including identical text).
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
CLASSPATH_TXT = ROOT / "desktop-prototype" / "target" / "classpath.txt"

JAVA_SOURCE = """
import editor.*;
import java.awt.event.InputMethodEvent;
import java.awt.font.TextHitInfo;
import java.text.AttributedString;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.Unit;

public class ProbeA1Runner {
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void commit(EditorSurface s, String t) {
        s.dispatchEvent(new InputMethodEvent(
            s,
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            new AttributedString(t).getIterator(),
            t.length(),
            TextHitInfo.leading(0),
            null
        ));
    }

    private static void preedit(EditorSurface s, String t, int committed, int caret) {
        s.dispatchEvent(new InputMethodEvent(
            s,
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            new AttributedString(t).getIterator(),
            committed,
            TextHitInfo.leading(caret),
            null
        ));
    }

    public static void main(String[] args) {
        // 1. Ordinary late AWT callback after abort + identical text on replacement host
        EditorEngine e = new EditorEngine(new Document(new ArrayList<>()), () -> 0L);
        EditorSurface s = new EditorSurface(e, new ViewState(e.snapshot()), null, null);
        e.inputMethod("X", 0, 1);
        s.abortAndRetireComposition();
        commit(s, "X");
        String lateResult = e.snapshot().getCommitted().getText();
        check("".equals(lateResult), "Expected empty text after late callback on retired surface, got: " + lateResult);
        EditorSurface sNext = s.getReplacementSurface();
        check(sNext != null && !sNext.getRetired() && sNext.getInputContext() != null, "Replacement host must be active");
        preedit(sNext, "X", 0, 1);
        commit(sNext, "X");
        check("X".equals(e.snapshot().getCommitted().getText()), "Identical text 'X' on replacement host must succeed");
        sNext.removeNotify();
        s.removeNotify();

        // 2. Reentrant ordinary AWT commit during abort
        EditorEngine e2 = new EditorEngine(new Document(new ArrayList<>()), () -> 0L);
        EditorSurface s2 = new EditorSurface(e2, new ViewState(e2.snapshot()), null, null);
        e2.inputMethod("Y", 0, 1);
        s2.setOnEndCompositionRequested(() -> {
            commit(s2, "Y");
            return Unit.INSTANCE;
        });
        s2.abortAndRetireComposition();
        String reentrantResult = e2.snapshot().getCommitted().getText();
        check("".equals(reentrantResult), "Expected empty text after reentrant commit during abort, got: " + reentrantResult);
        if (s2.getReplacementSurface() != null) s2.getReplacementSurface().removeNotify();
        s2.removeNotify();

        // 3. Watchdog timeout across non-terminal candidate updates
        AtomicLong clock = new AtomicLong();
        EditorEngine e3 = new EditorEngine(new Document(new ArrayList<>()), () -> clock.get());
        e3.inputMethod("a", 0, 1);
        e3.toggleFormat(FormatKey.BOLD);
        clock.set(1000);
        e3.inputMethod("ab", 0, 2);
        check(e3.snapshot().getWaiting() && Long.valueOf(0L).equals(e3.getSettlementRequestedAt()), "Candidate update must preserve deadline 0L");
        clock.set(10000);
        boolean timedOut = e3.checkSettlementWatchdog(2000);
        boolean waitingAfter = e3.snapshot().getWaiting();
        Long deadlineAfter = e3.getSettlementRequestedAt();
        check(timedOut && !waitingAfter && deadlineAfter == null, "Expected timeout=true waiting=false deadline=null");
        check(e3.getSettlementTimedOut() && e3.snapshot().getMarked() != null, "Watchdog must not silently auto-commit preedit");

        System.out.println("ORDINARY_LATE_CALLBACK_AFTER_ABORT=" + lateResult);
        System.out.println("REENTRANT_COMMIT_DURING_ABORT=" + reentrantResult);
        System.out.println("AFTER_CANDIDATE_UPDATE_TIMEOUT=" + timedOut + " WAITING=" + waitingAfter + " DEADLINE_START=" + deadlineAfter);
        System.out.println("EVIDENCE_CLASS=compiled_regression_assertion");
        System.out.println("WINDOWS_OS_CALLBACK_LOSS_PROVEN=false (headless probe tests bounded watchdog & host retirement, not OS IME failure)");
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
