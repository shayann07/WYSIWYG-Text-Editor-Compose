"""Master runner for all independent investigation probes under reviews/argon-critical-path/experiments/.

Executes:
1. probe_kf1_isolate_bidi.py
2. probe_kf2_kf3_break_geometry.py
3. probe_a1_settlement_wedge.py
4. probe_a3_partial_commit_data_loss.py
5. probe_b1_soft_wrap_end_key.py
6. probe_c1_direction_cascade.py
7. probe_c2_keystroke_scaling.py
"""
import json
import pathlib
import subprocess
import sys
import time

EXPERIMENTS_DIR = pathlib.Path(__file__).resolve().parent

PROBES = [
    ("KF-1 Isolate Bidi", "probe_kf1_isolate_bidi.py"),
    ("KF-2 & KF-3 Break Selection & Geometry", "probe_kf2_kf3_break_geometry.py"),
    ("A-1 Composition Wedge", "probe_a1_settlement_wedge.py"),
    ("A-3 Partial Commit Data Loss", "probe_a3_partial_commit_data_loss.py"),
    ("B-1 Soft-Wrap End Key Truncation", "probe_b1_soft_wrap_end_key.py"),
    ("C-1 Direction Cascade", "probe_c1_direction_cascade.py"),
    ("C-2 Keystroke & Navigation Scaling", "probe_c2_keystroke_scaling.py"),
]

def main():
    results = []
    all_passed = True
    print("================================================================================")
    print("RUNNING ALL ARGON CRITICAL PATH INVESTIGATION PROBES")
    print("================================================================================")

    for name, script_name in PROBES:
        script_path = EXPERIMENTS_DIR / script_name
        t0 = time.monotonic()
        res = subprocess.run([sys.executable, str(script_path)], capture_output=True, text=True)
        elapsed = round(time.monotonic() - t0, 3)
        passed = (res.returncode == 0)
        if not passed:
            all_passed = False
        print(f"[{'PASS' if passed else 'FAIL'}] {name} ({elapsed}s)")
        for line in res.stdout.strip().splitlines():
            print(f"  {line}")
        if res.stderr.strip():
            print(f"  STDERR: {res.stderr.strip()}")
        results.append({
            "probe": name,
            "script": script_name,
            "passed": passed,
            "exit_code": res.returncode,
            "elapsed_seconds": elapsed,
            "output": res.stdout.strip()
        })
        print("--------------------------------------------------------------------------------")

    summary_file = EXPERIMENTS_DIR / "probe_results_summary.json"
    summary_file.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"All probes completed. Summary written to {summary_file}")
    return 0 if all_passed else 1

if __name__ == "__main__":
    sys.exit(main())
