#!/usr/bin/env python3
"""Repo-local AI workflow harness - Milestone 1.

Stdlib only. Run via:

    python .ai-workflow/bin/ai_workflow.py <command> [args...]

Implemented commands: init, status, start, aider-next, aider-done,
copilot-next, copilot-done.

Reserved (Milestone 2+) commands print a notice and exit 1:
test, fail, pass, ready-commit, pushed, run-until-human.
"""

import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

SCRIPT_PATH = Path(__file__).resolve()
# Script lives at <repo>/.ai-workflow/bin/ai_workflow.py
REPO_ROOT = SCRIPT_PATH.parent.parent.parent
WORKFLOW_DIR = REPO_ROOT / ".ai-workflow"
MENUS_DIR = WORKFLOW_DIR / "menus"
BIN_DIR = WORKFLOW_DIR / "bin"
STATE_FILE = WORKFLOW_DIR / "state.json"
SESSION_LOG = WORKFLOW_DIR / "session_log.md"


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

STATUS_NAMES = frozenset({
    "idle",
    "aider_prompt_ready",
    "aider_patch_ready",
    "copilot_review_ready",
    "tests_failed",
    "tests_passed",
    "human_test_required",
    "ready_to_commit",
    "pushed",
    "menu_inventory_ready",
    "menu_plan_ready",
    "menu_patch_partial",
    "menu_review_notes_ready",
    "menu_runtime_test_required",
    "menu_runtime_failed",
    "menu_runtime_passed",
})

DEFAULT_STATE = {
    "version": 1,
    "project": "SageTV STV Optimization",
    "phase": "",
    "task": "",
    "branch": "",
    "status": "idle",
    "last_actor": "user",
    "next_actor": "user",
    "files_in_scope": [],
    "files_changed": [],
    "last_verified_command": "",
    "last_test_result": "not_run",
    "docker_container": "sagetv",
    "next_instruction": "",
    "safe_to_commit": False,
    "safe_to_push": False,
    "feature_name": "",
    "primary_lane": "generic_code",
    "requires_stv_access": True,
    "stv_access_lane": "stv_access",
    "user_entry_points": [],
    "functional_status": "not_started",
    "stv_access_status": "not_started",
    "feature_complete": False,
    "runner_mode": "manual",
    "retry_count": 0,
    "retry_limit": 3,
    "stop_reason": "",
    "human_gate_required": False,
    "human_gate_type": "",
    "human_gate_files": [],
    "automation_can_resume": False,
    "last_runner_step": "",
    "last_stop_timestamp": "",
}

# Default content for scaffolded files. Empty string => create empty file.
DEFAULT_FILES = {
    "current_task.md": "# Current Task\n\n(no task started)\n",
    "next_aider_prompt.md": "# Next Aider Prompt\n\n(no prompt queued)\n",
    "next_copilot_prompt.md": "# Next Copilot Prompt\n\n(no prompt queued)\n",
    "handoff.md": "# Handoff Notes\n\n(empty)\n",
    "blockers.md": "# Blockers\n\n(none)\n",
    "session_log.md": "# Session Log\n\n",
    "next_human_action.md": "# Next Human Action\n\n(none required)\n",
    "test_plan.md": "# Test Plan\n\n(not defined)\n",
    "test_results.md": "# Test Results\n\n(not run)\n",
    "human_test_required.md": "# Human Test Required\n\n(none required)\n",
    "resume_after_human.md": "# Resume After Human\n\n(not applicable)\n",
    "runner_log.md": "# Runner Log\n\n",
    "menus/menu_inventory.json": "{\n  \"menus\": []\n}\n",
    "menus/current_menu_task.md": "# Current Menu Task\n\n(none)\n",
    "menus/menu_baton.json": "{\n  \"holder\": \"user\",\n  \"menu_id\": \"\"\n}\n",
    "menus/menu_diff_notes.md": "# Menu Diff Notes\n\n(none)\n",
    "menus/menu_test_matrix.md": "# Menu Test Matrix\n\n(none)\n",
    "menus/menu_blockers.md": "# Menu Blockers\n\n(none)\n",
    "menus/menu_session_log.md": "# Menu Session Log\n\n",
}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def ensure_dirs() -> None:
    WORKFLOW_DIR.mkdir(parents=True, exist_ok=True)
    MENUS_DIR.mkdir(parents=True, exist_ok=True)
    BIN_DIR.mkdir(parents=True, exist_ok=True)


def scaffold_files() -> list:
    created = []
    for rel, content in DEFAULT_FILES.items():
        path = WORKFLOW_DIR / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            path.write_text(content, encoding="utf-8")
            created.append(str(path.relative_to(REPO_ROOT)))
    return created


def load_state() -> dict:
    if not STATE_FILE.exists():
        return dict(DEFAULT_STATE)
    try:
        with STATE_FILE.open("r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        print("ERROR: could not parse {0}: {1}".format(STATE_FILE, exc),
              file=sys.stderr)
        sys.exit(2)
    # Backfill missing keys so older state files still work.
    merged = dict(DEFAULT_STATE)
    merged.update(data)
    return merged


def save_state(state: dict) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(state, indent=2, sort_keys=True) + "\n"
    STATE_FILE.write_text(text, encoding="utf-8")


def log_session(message: str) -> None:
    SESSION_LOG.parent.mkdir(parents=True, exist_ok=True)
    line = "- [{0}] {1}\n".format(now_iso(), message)
    with SESSION_LOG.open("a", encoding="utf-8") as fh:
        fh.write(line)


def run_git(args: list) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git"] + args,
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        check=False,
    )


def parse_git_status_short(output: str) -> list:
    """Return list of working-tree paths from `git status --short` output.

    Each line is `XY path` or, for renames, `XY orig -> new`. We pick the
    post-rename path so the caller can decide whether it is in scope.
    """
    paths = []
    for raw in output.splitlines():
        if not raw.strip():
            continue
        # First two columns are status flags, then a space, then the path.
        body = raw[3:] if len(raw) > 3 else raw.strip()
        if " -> " in body:
            body = body.split(" -> ", 1)[1]
        # Strip surrounding quotes git emits for paths with special chars.
        body = body.strip()
        if len(body) >= 2 and body[0] == '"' and body[-1] == '"':
            body = body[1:-1]
        paths.append(body)
    return paths


def path_is_allowed_dirty(path: str) -> bool:
    """Milestone 1: only paths under .ai-workflow/ are allowed dirty."""
    norm = path.replace("\\", "/")
    return norm.startswith(".ai-workflow/") or norm == ".ai-workflow"


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def block_with_human_action(state: dict, reason: str, body: str,
                            exit_code: int = 1) -> int:
    state["stop_reason"] = reason
    state["human_gate_required"] = True
    state["last_stop_timestamp"] = now_iso()
    state["automation_can_resume"] = False
    write_text(WORKFLOW_DIR / "next_human_action.md", body)
    save_state(state)
    log_session("BLOCKED: {0}".format(reason))
    print("BLOCKED: {0}".format(reason), file=sys.stderr)
    print("See .ai-workflow/next_human_action.md", file=sys.stderr)
    return exit_code


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_init(args: list) -> int:
    force = "--force" in args
    ensure_dirs()
    created = scaffold_files()
    state_action = ""
    if STATE_FILE.exists() and not force:
        state_action = "state.json preserved (use --force to overwrite)"
    else:
        save_state(dict(DEFAULT_STATE))
        state_action = "state.json written"
    log_session("init: {0}; new files: {1}".format(
        state_action,
        ", ".join(created) if created else "none",
    ))
    print("Workflow directory: {0}".format(WORKFLOW_DIR))
    print(state_action)
    if created:
        print("Created files:")
        for c in created:
            print("  " + c)
    else:
        print("All scaffold files already present.")
    return 0


def cmd_status(args: list) -> int:
    state = load_state()
    fields = [
        "project",
        "branch",
        "phase",
        "task",
        "status",
        "last_actor",
        "next_actor",
        "primary_lane",
        "requires_stv_access",
        "files_changed",
        "human_gate_required",
        "stop_reason",
        "safe_to_commit",
        "safe_to_push",
        "next_instruction",
    ]
    width = max(len(f) for f in fields)
    for f in fields:
        value = state.get(f, "")
        if isinstance(value, list):
            value = ", ".join(value) if value else "(none)"
        print("{0:<{w}} : {1}".format(f, value, w=width))
    print("{0:<{w}} : {1}/{2}".format(
        "retry_count",
        state.get("retry_count", 0),
        state.get("retry_limit", 0),
        w=width,
    ))
    return 0


def cmd_start(args: list) -> int:
    if len(args) < 3:
        print("usage: start <branch> <phase> <task>", file=sys.stderr)
        return 2
    branch, phase, task = args[0], args[1], args[2]

    ensure_dirs()
    scaffold_files()
    state = load_state()

    # --- Git checks ---------------------------------------------------------
    cur = run_git(["branch", "--show-current"])
    if cur.returncode != 0:
        print("ERROR: git branch --show-current failed:\n" + cur.stderr,
              file=sys.stderr)
        return 2
    current_branch = cur.stdout.strip()

    if current_branch != branch:
        body = (
            "# Next Human Action\n\n"
            "Branch mismatch.\n\n"
            "- Requested: `{0}`\n"
            "- Current  : `{1}`\n\n"
            "Run `git checkout {0}` (or create the branch) before "
            "re-running `start`.\n"
        ).format(branch, current_branch)
        return block_with_human_action(
            state, "branch_mismatch", body,
        )

    st = run_git(["status", "--short"])
    if st.returncode != 0:
        print("ERROR: git status --short failed:\n" + st.stderr,
              file=sys.stderr)
        return 2

    dirty = parse_git_status_short(st.stdout)
    bad = [p for p in dirty if not path_is_allowed_dirty(p)]
    if bad:
        listing = "\n".join("- `{0}`".format(p) for p in bad)
        body = (
            "# Next Human Action\n\n"
            "Working tree is dirty outside `.ai-workflow/`.\n\n"
            "Offending paths:\n\n{0}\n\n"
            "Commit, stash, or revert these before re-running `start`.\n"
        ).format(listing)
        return block_with_human_action(
            state, "dirty_working_tree", body,
        )

    # --- Mutate state -------------------------------------------------------
    state["branch"] = branch
    state["phase"] = phase
    state["task"] = task
    state["status"] = "aider_prompt_ready"
    state["last_actor"] = "user"
    state["next_actor"] = "aider"
    state["safe_to_commit"] = False
    state["safe_to_push"] = False
    state["stop_reason"] = ""
    state["files_changed"] = []
    state["human_gate_required"] = False
    state["human_gate_type"] = ""
    state["human_gate_files"] = []

    # --- Write task / prompt scaffolds -------------------------------------
    write_text(
        WORKFLOW_DIR / "current_task.md",
        (
            "# Current Task\n\n"
            "- Branch: `{0}`\n"
            "- Phase : `{1}`\n"
            "- Task  : `{2}`\n"
            "- Started: {3}\n"
        ).format(branch, phase, task, now_iso()),
    )
    write_text(
        WORKFLOW_DIR / "next_aider_prompt.md",
        (
            "# Next Aider Prompt\n\n"
            "Branch: `{0}`\n"
            "Phase : `{1}`\n"
            "Task  : `{2}`\n\n"
            "Describe the change for Aider here. When ready, run:\n\n"
            "    python .ai-workflow/bin/ai_workflow.py aider-next\n"
        ).format(branch, phase, task),
    )

    save_state(state)
    log_session("start: branch={0} phase={1} task={2}".format(
        branch, phase, task))
    print("Started: branch={0} phase={1} task={2}".format(branch, phase, task))
    print("Status -> aider_prompt_ready (next_actor=aider)")
    return 0


def cmd_aider_next(args: list) -> int:
    state = load_state()
    if state.get("next_actor") != "aider":
        print(
            "ERROR: next_actor is '{0}', expected 'aider'.".format(
                state.get("next_actor")),
            file=sys.stderr,
        )
        return 1
    prompt_path = WORKFLOW_DIR / "next_aider_prompt.md"
    print("Prompt file: {0}".format(prompt_path))
    print("-" * 60)
    if prompt_path.exists():
        sys.stdout.write(prompt_path.read_text(encoding="utf-8"))
    else:
        print("(prompt file missing)")
    print("-" * 60)
    log_session("aider-next: displayed next_aider_prompt.md")
    return 0


def cmd_aider_done(args: list) -> int:
    state = load_state()
    diff = run_git(["diff", "--name-only"])
    if diff.returncode != 0:
        print("ERROR: git diff --name-only failed:\n" + diff.stderr,
              file=sys.stderr)
        return 2
    changed = [p.strip() for p in diff.stdout.splitlines() if p.strip()]

    state["files_changed"] = changed
    state["status"] = "aider_patch_ready"
    state["last_actor"] = "aider"
    state["next_actor"] = "vscode_copilot"
    state["safe_to_commit"] = False

    if changed:
        file_list = "\n".join("- `{0}`".format(p) for p in changed)
    else:
        file_list = "_(no files changed yet)_"
    write_text(
        WORKFLOW_DIR / "next_copilot_prompt.md",
        (
            "# Next Copilot Prompt\n\n"
            "Aider has handed off. Review the following files and verify "
            "the patch is correct, complete, and consistent with the task "
            "described in `current_task.md`.\n\n"
            "## Files changed\n\n{0}\n\n"
            "When review is complete, run:\n\n"
            "    python .ai-workflow/bin/ai_workflow.py copilot-done\n"
        ).format(file_list),
    )
    write_text(
        WORKFLOW_DIR / "handoff.md",
        (
            "# Handoff: Aider -> Copilot\n\n"
            "- Timestamp: {0}\n"
            "- Branch   : `{1}`\n"
            "- Task     : `{2}`\n\n"
            "## Files changed\n\n{3}\n"
        ).format(
            now_iso(),
            state.get("branch", ""),
            state.get("task", ""),
            file_list,
        ),
    )

    save_state(state)
    log_session("aider-done: {0} file(s) changed".format(len(changed)))
    print("Aider patch recorded ({0} file(s)).".format(len(changed)))
    print("Status -> aider_patch_ready (next_actor=vscode_copilot)")
    return 0


def cmd_copilot_next(args: list) -> int:
    state = load_state()
    if state.get("next_actor") != "vscode_copilot":
        print(
            "ERROR: next_actor is '{0}', expected 'vscode_copilot'.".format(
                state.get("next_actor")),
            file=sys.stderr,
        )
        return 1
    prompt_path = WORKFLOW_DIR / "next_copilot_prompt.md"
    print("Prompt file: {0}".format(prompt_path))
    print("-" * 60)
    if prompt_path.exists():
        sys.stdout.write(prompt_path.read_text(encoding="utf-8"))
    else:
        print("(prompt file missing)")
    print("-" * 60)
    log_session("copilot-next: displayed next_copilot_prompt.md")
    return 0


def cmd_copilot_done(args: list) -> int:
    state = load_state()
    state["status"] = "copilot_review_ready"
    state["last_actor"] = "vscode_copilot"
    state["next_actor"] = "test_runner"
    state["safe_to_commit"] = False

    write_text(
        WORKFLOW_DIR / "handoff.md",
        (
            "# Handoff: Copilot -> Test Runner\n\n"
            "- Timestamp: {0}\n"
            "- Branch   : `{1}`\n"
            "- Task     : `{2}`\n\n"
            "Review complete. Tests are next.\n"
        ).format(
            now_iso(),
            state.get("branch", ""),
            state.get("task", ""),
        ),
    )

    save_state(state)
    log_session("copilot-done: review complete")
    print("Copilot review recorded.")
    print("Status -> copilot_review_ready (next_actor=test_runner)")
    return 0


def cmd_not_implemented(args: list) -> int:
    print("Not implemented in Milestone 1", file=sys.stderr)
    return 1


# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

DISPATCH = {
    "init": cmd_init,
    "status": cmd_status,
    "start": cmd_start,
    "aider-next": cmd_aider_next,
    "aider-done": cmd_aider_done,
    "copilot-next": cmd_copilot_next,
    "copilot-done": cmd_copilot_done,
    # Reserved for Milestone 2+.
    "test": cmd_not_implemented,
    "fail": cmd_not_implemented,
    "pass": cmd_not_implemented,
    "ready-commit": cmd_not_implemented,
    "pushed": cmd_not_implemented,
    "run-until-human": cmd_not_implemented,
}


def usage() -> str:
    cmds = " ".join(sorted(DISPATCH.keys()))
    return (
        "usage: ai_workflow.py <command> [args...]\n"
        "commands: " + cmds + "\n"
    )


def main(argv: list) -> int:
    if len(argv) < 2:
        sys.stderr.write(usage())
        return 2
    cmd = argv[1]
    handler = DISPATCH.get(cmd)
    if handler is None:
        sys.stderr.write("unknown command: {0}\n".format(cmd))
        sys.stderr.write(usage())
        return 2
    return handler(argv[2:])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
