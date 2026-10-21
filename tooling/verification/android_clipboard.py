"""Real system clipboard URI grants between two independently installed binary consumers.

Input setup and paste action are instrumented; never label this a human/native IME walkthrough.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

from android_check import PACKAGE, wait_for_owned_emulator, wait_for_services
from android_process import receipt_if_ready, validated_pid


def validate_transport(source, restored):
    if source["uid"] == restored["uid"]:
        raise ValueError("Transport used the same application UID")
    if (restored.get("event") != "Rich paste applied" or restored.get("readOnly") is not True
            or restored.get("independentDescriptionChecked") is not True):
        raise ValueError("The peer did not verify a read-only rich paste and its independent expected structure")
    if source["resources"] != restored["resources"] or len(restored["resources"]) != 1:
        raise ValueError("The owned resource identity changed in transport")
    original_items = set(source["items"]) - {""}
    pasted_items = set(restored["items"]) - {""}
    if len(original_items) != 2 or len(pasted_items) != 2 or not original_items.isdisjoint(pasted_items):
        raise ValueError("Paste did not allocate fresh list item identities")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--consumer-receipt", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only an explicitly identified disposable emulator is accepted")
    receipt = json.loads(args.consumer_receipt.read_text(encoding="utf-8"))
    if receipt.get("status") != "PASS" or "androidPeer" not in receipt:
        parser.error("A passing independently packaged peer consumer is required")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    peer = receipt["androidPeer"]["applicationId"]
    expected_peer = PACKAGE + ".peerconsumer"
    if peer != expected_peer:
        parser.error("Unexpected disposable peer application identity")

    def run(label, *command, timeout=120):
        result = subprocess.run([adb, "-s", args.serial, *command], capture_output=True, timeout=timeout)
        (output / (label + ".stdout")).write_bytes(result.stdout)
        (output / (label + ".stderr")).write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError(label + " failed; raw response retained")
        return result.stdout

    def poll(package, path):
        deadline = time.monotonic() + 30
        attempt = 0
        stage = Path(path).parent.name.split("-", 1)[0] + "-" + Path(path).stem
        while time.monotonic() < deadline:
            attempt += 1
            value = receipt_if_ready(run(f"poll-{package}-{stage}-{attempt}", "exec-out", "run-as", package, "cat", path))
            if value is not None:
                return value
            time.sleep(.1)
        raise TimeoutError("Clipboard witness did not produce its exact receipt: " + path)

    result = dict(status="RUNNING", scope="Actual cross-UID clipboard transport and publisher SIGKILL; injected fixture actions",
                  consumerReceiptSha256=hashlib.sha256(args.consumer_receipt.read_bytes()).hexdigest(),
                  artifacts=dict(receipt["androidArtifacts"], **receipt["androidPeer"]["artifacts"]))
    try:
        result["emulatorIdentity"] = wait_for_owned_emulator(run, args.avd_name)
        result["serviceReadiness"] = wait_for_services(run)
        artifacts = dict(receipt["androidArtifacts"], **receipt["androidPeer"]["artifacts"])
        for index, (name, digest) in enumerate(artifacts.items()):
            if "/apk/debug/" not in name.replace("\\", "/"):
                continue
            if hashlib.sha256(Path(name).read_bytes()).hexdigest() != digest:
                raise ValueError("Packaged artifact changed after consumer verification")
            run(f"install-{index}", "install", "-r", name)
        token = str(uuid.uuid4())
        run("publish", "shell", "am", "start", "-f", "0x18000000", "-n", PACKAGE + "/" + PACKAGE + ".ClipboardPublishActivity",
            "--es", "transport", token)
        source = poll(PACKAGE, f"files/qualification/published-{token}/ready.json")
        (output / "instrumented-rich-source.png").write_bytes(
            run("source-render", "exec-out", "screencap", "-p"))
        pid = validated_pid(run("source-identity", "shell", "pidof", PACKAGE + ":clipboardwitness"), source["pid"])
        run("kill-publisher", "shell", "run-as", PACKAGE, "kill", "-9", pid)
        run("paste", "shell", "am", "start", "-f", "0x18000000", "-n", peer + "/" + PACKAGE + ".ClipboardTransportActivity",
            "--es", "transport", token, "--ez", "blank", "true", "--ez", "check-witness", "true",
            "--es", "qualification-root", "peer-clipboard-" + token)
        restored = poll(peer, f"files/qualification/transport-{token}/result.json")
        validate_transport(source, restored)
        for package, path, name in (
            (PACKAGE, f"files/qualification/published-{token}/expected.astra", "clipboard-source.astra"),
            (peer, f"files/qualification/transport-{token}/document.astra", "clipboard-pasted.astra"),
        ):
            (output / name).write_bytes(run(name, "exec-out", "run-as", package, "cat", path))
        run("clear-publisher-cache", "shell", "am", "start", "-f", "0x18000000", "-n",
            PACKAGE + "/" + PACKAGE + ".ClipboardPublishActivity", "--es", "transport", token,
            "--ez", "clear-cache", "true")
        cleared = poll(PACKAGE, f"files/qualification/cleared-{token}/ready.json")
        if cleared.get("cleared") is not True or cleared.get("pid") == source["pid"]:
            raise ValueError("Cache cleanup was not executed in a fresh publisher process")
        run("verify-revoked-cache", "shell", "am", "start", "-f", "0x18000000", "-n",
            peer + "/" + PACKAGE + ".ClipboardTransportActivity", "--es", "transport", token,
            "--ez", "blank", "true", "--es", "revoked-uri", source["uri"],
            "--es", "qualification-root", "peer-cleanup-" + token)
        cleanup = poll(peer, f"files/qualification/transport-{token}/cleanup.json")
        if cleanup.get("revoked") is not True or cleanup.get("ownedDocumentPreserved") is not True:
            raise ValueError("Clipboard cleanup did not revoke access while preserving the pasted document")
        result.update(status="PASS", source=source, restored=restored, cleanup=cleanup, cases=2, token=token)
    except Exception as error:
        result.update(status="FAIL", error=str(error))
        try:
            run("failure-logcat", "logcat", "-d", "-t", "1500", timeout=10)
        except Exception as diagnostic:
            result["diagnosticError"] = str(diagnostic)
        raise
    finally:
        (output / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS cross-UID rich clipboard after publisher process death; " + str(output))


if __name__ == "__main__":
    main()
