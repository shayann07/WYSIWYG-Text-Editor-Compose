"""One real owned Android SIGKILL/restart witness, separate from injected input and native walkthroughs."""
import hashlib
import json
import time
import uuid

PACKAGE = "dev.shayxo.composedoc.androidsample"
EXPECTED_TEXT = "اُردو workspace\nParent 42\nEnglish child\ncontinuation\n\ufffc\n"
PROPOSAL = "仮候補"


def receipt_if_ready(raw):
    # exec-out can return exit zero for a remote cat error. Only missing files
    # are a readiness retry; malformed receipts must still fail qualification.
    if raw.startswith(b"cat: ") and b"No such file or directory" in raw:
        return None
    return json.loads(raw)


def validated_pid(observed, requested):
    values = observed.decode().split()
    if len(values) != 1 or not values[0].isdigit() or int(values[0]) != requested or requested <= 1:
        raise ValueError("Refusing to kill a PID that is not the captured owned recovery child")
    return str(requested)


def check_restore(result, source_pid):
    if (result.get("status") != "PASS" or result.get("sourcePid") != source_pid
            or not isinstance(result.get("restoredPid"), int) or result["restoredPid"] == source_pid
            or result.get("canonicalText") != EXPECTED_TEXT
            or result.get("separateProposalText") != EXPECTED_TEXT + PROPOSAL
            or result.get("independentDescriptionChecked") is not True or result.get("newUnsavedOwner") is not True):
        raise ValueError("Recovery process witness did not execute the exact expected restoration")


def run_witness(run, output):
    request = str(uuid.uuid4())
    location = f"files/qualification/kill-{request}"
    process = PACKAGE + ":recoverywitness"
    component = PACKAGE + "/" + PACKAGE + ".RecoveryWitnessActivity"

    def poll_receipt(name, timeout=30):
        deadline = time.monotonic() + timeout
        attempt = 0
        while time.monotonic() < deadline:
            attempt += 1
            try:
                raw = run(f"kill-{name}-{attempt}", "exec-out", "run-as", PACKAGE, "cat", f"{location}/{name}.json", timeout=5)
            except RuntimeError:
                time.sleep(.1)
                continue
            value = receipt_if_ready(raw)
            if value is None:
                time.sleep(.1)
                continue
            return value
        raise TimeoutError(f"Owned recovery child did not publish {name}; no process qualification PASS")

    run("kill-start", "shell", "am", "start", "-f", "0x18000000", "-n", component, "--es", "request", request,
        "--es", "phase", "prepare")
    captured_pid = None
    try:
        ready = poll_receipt("late-ready")
        if ready.get("stage") != "AFTER_COMPLETED_CHECKPOINT" or not isinstance(ready.get("sourcePid"), int):
            raise ValueError("Recovery child never established its completed-checkpoint/later-edit boundary")
        captured_pid = validated_pid(run("kill-owned-identity", "shell", "pidof", process), ready["sourcePid"])
        run("kill-owned-child", "shell", "run-as", PACKAGE, "kill", "-9", captured_pid)
        captured_pid = None
        run("kill-restart", "shell", "am", "start", "-f", "0x18000000", "-n", component, "--es", "request", request,
            "--es", "phase", "restore")
        result = poll_receipt("restored")
        check_restore(result, ready["sourcePid"])
        for name in ("expected", "late", "restored"):
            raw = run("kill-" + name + "-document", "exec-out", "run-as", PACKAGE, "cat", f"{location}/{name}.astra")
            (output / ("process-" + name + ".astra")).write_bytes(raw)
        expected = (output / "process-expected.astra").read_bytes()
        if expected != (output / "process-restored.astra").read_bytes():
            raise ValueError("Recovered supported document/resource bytes differ from the completed checkpoint")
        if expected == (output / "process-late.astra").read_bytes():
            raise ValueError("The declared after-checkpoint loss window was not actually exercised")
        result.update(scope="One owned SIGKILL and fresh process; synthetic input setup, not power-loss/native IME evidence",
                      checkpointSha256=hashlib.sha256(expected).hexdigest(), cases=1, request=request)
        (output / "process-interruption.json").write_text(json.dumps(result, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
        captured_pid = validated_pid(run("restore-owned-identity", "shell", "pidof", process), result["restoredPid"])
        return result
    finally:
        if captured_pid is not None:
            # This is the exact PID just validated above, never a guessed/name-wide process kill.
            run("kill-clean-owned-child", "shell", "run-as", PACKAGE, "kill", "-9", captured_pid)
