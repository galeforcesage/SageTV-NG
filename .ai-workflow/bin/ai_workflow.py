#!/usr/bin/env python3
import json
import subprocess
import sys
import os
import time
from datetime import datetime, timezone
from pathlib import Path

# Paths
SCRIPT_PATH = Path(__file__).resolve()
REPO_ROOT = SCRIPT_PATH.parent.parent.parent
WORKFLOW_DIR = REPO_ROOT / ".ai-workflow"
STATE_FILE = WORKFLOW_DIR / "state.json"
SESSION_LOG = WORKFLOW_DIR / "session_log.md"

def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

def load_state():
    if not STATE_FILE.exists():
        return {}
    with STATE_FILE.open("r", encoding="utf-8") as fh:
        return json.load(fh)

def save_state(state):
    STATE_FILE.write_text(json.dumps(state, indent=2))

def log_session(msg):
    SESSION_LOG.parent.mkdir(parents=True, exist_ok=True)
    with SESSION_LOG.open("a", encoding="utf-8") as fh:
        fh.write(f"- [{now_iso()}] {msg}\n")

# ---------------- STATUS ----------------

def cmd_status(args):
    state = load_state()

    print("=== AIWF STATUS ===")

    print(f"Project : {state.get('project', '')}")
    print(f"Branch  : {state.get('branch', '')}")
    print(f"Phase   : {state.get('phase', '')}")
    print(f"Task    : {state.get('task', '')}")

    print("")
    print(f"Status          : {state.get('status', '')}")
    print(f"Last actor      : {state.get('last_actor', '')}")
    print(f"Next actor      : {state.get('next_actor', '')}")

    print("")
    print(f"Last deployed   : {state.get('last_deployed_branch', '(none)')}")
    print(f"Safe to commit  : {state.get('safe_to_commit', False)}")
    print(f"Safe to push    : {state.get('safe_to_push', False)}")

    print("")
    print(f"Retry count     : {state.get('retry_count', 0)}/{state.get('retry_limit', 0)}")

    # Optional: full debug view
    if "--json" in args:
        print("\n--- RAW JSON ---")
        print(json.dumps(state, indent=2))

    return 0

# ---------------- TEST ----------------

def cmd_test(args):
    state = load_state()
    state["status"] = "testing"
    save_state(state)

    print("=== TEST PHASE ===")
    print("- Load STV in SageTV")
    print("- Verify menus, playback, comskip")
    print("\nThen run:")
    print("  aiwf pass")
    print("  aiwf fail")
    return 0

def cmd_pass(args):
    state = load_state()
    state["status"] = "tests_passed"
    save_state(state)

    log_session("tests passed")
    print("✅ TESTS PASSED")
    return 0

def cmd_fail(args):
    log_session("tests failed")
    print("❌ TEST FAILED — returning to Aider")
    return 0

# ---------------- DEPLOY ----------------

def cmd_deploy(args):
    state = load_state()

    print("=== DEPLOY PHASE ===")

    # Detect branch
    branch_result = subprocess.run(
        ["git", "branch", "--show-current"],
        capture_output=True, text=True
    )
    current_branch = branch_result.stdout.strip()

    server = os.getenv("AIWF_SERVER")
    user = os.getenv("AIWF_USER")
    container = os.getenv("AIWF_CONTAINER")

    if not server or not user or not container:
        print("❌ Missing env vars")
        return 1

    local_file = "stvs/SageTV7/SageTV7_dedup.xml"
    remote_tmp = "/tmp/SageTV7_dedup.xml"
    container_path = "/opt/sagetv/server/STVs/SageTV7/SageTV7.xml"

    # --- SCP ---
    print("Copying file to server...")
    scp = subprocess.run(
        ["scp", local_file, f"{user}@{server}:{remote_tmp}"],
        capture_output=True, text=True
    )

    if scp.returncode != 0:
        print("❌ SCP FAILED")
        print(scp.stderr)
        log_session("deploy failed: scp")
        return 1

    print("Stopping SageTV, backing up, deploying...")

    ssh_cmd = (
        f'docker exec {container} bash -c "/opt/sagetv/server/stopsage" && '
        f'docker exec {container} bash -c \'cd /opt/sagetv/server/STVs/SageTV7 && '
        f'cp SageTV7.xml SageTV7_backup_$(date +%Y%m%d_%H%M%S).xml\' && '
        f'docker cp {remote_tmp} {container}:{container_path} && '
        f'docker exec {container} bash -c "/opt/sagetv/server/startsage"'
    )

    ssh = subprocess.run(
        ["ssh", "-T", f"{user}@{server}", ssh_cmd],
        capture_output=True, text=True
    )

    if ssh.returncode != 0:
        print("❌ DEPLOY FAILED")
        print(ssh.stderr)
        log_session("deploy failed: ssh")
        return 1

    print(ssh.stdout)

    # --- VERIFY WITH RETRY LOOP (✅ FIXED) ---
    print("Verifying SageTV started...")

    check_cmd = f'docker exec {container} bash -c "pgrep -f sagetv"'

    success = False

    for attempt in range(5):
        try:
            check = subprocess.run(
                ["ssh", "-T", f"{user}@{server}", check_cmd],
                capture_output=True, text=True,
                timeout=5
            )
        except subprocess.TimeoutExpired:
            print(f"Retry {attempt+1}/5 (timeout)...")
            time.sleep(2)
            continue

        output = check.stdout.strip()

        if check.returncode == 0 and output:
            print(output)
            success = True
            break

        print(f"Retry {attempt+1}/5... waiting for SageTV")
        time.sleep(2)

    if not success:
        print("❌ SageTV failed to start after retries")
        log_session("deploy failed: sagetv not running (retry exhausted)")
        return 1

    # Record state
    state["last_deployed_branch"] = current_branch
    state["status"] = "deployed"

    save_state(state)
    log_session(f"deployed branch: {current_branch}")

    print("✅ DEPLOY COMPLETE")
    print("Run: aiwf test")

    return 0

# ---------------- ROLLBACK ----------------

def cmd_rollback(args):
    print("Rollback not automated yet")
    return 0

# ---------------- DISPATCH ----------------

DISPATCH = {
    "status": cmd_status,
    "test": cmd_test,
    "pass": cmd_pass,
    "fail": cmd_fail,
    "deploy": cmd_deploy,
    "rollback": cmd_rollback,
}

def main():
    if len(sys.argv) < 2:
        print("Usage: aiwf <command>")
        return 1

    cmd = sys.argv[1]
    func = DISPATCH.get(cmd)

    if not func:
        print(f"Unknown command: {cmd}")
        return 1

    return func(sys.argv[2:])

if __name__ == "__main__":
    sys.exit(main())