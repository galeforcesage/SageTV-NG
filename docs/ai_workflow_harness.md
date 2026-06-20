Hybrid AI Toolchain Workflow
This project should no longer rely on one AI tool to remember the whole repo history or make all implementation decisions. Use a hybrid model where this PRD remains the source of product intent, VS Code/GitHub Copilot assists with focused code edits, Aider performs repo-aware implementation and patch application, local Ollama Qwen2.5:7B provides private/local reasoning for code navigation and repetitive changes, Docker receives live file updates without unnecessary image rebuilds, and Git remains the system of record for every successful step.
C.1 Core Principle: One Source of Truth, Multiple Execution Tools
The PRD defines what must be built and why. The implementation plan defines how the repo will change. Aider and GitHub Copilot should never be allowed to invent new product scope without the PRD being updated first. Every code session should start from the latest committed repo state, a short task brief, and a clear stop condition.
Function	Primary Tool	Reason
Design / PRD	Word + Copilot	Best place for requirements, scope, acceptance criteria, risks, and human-readable implementation intent.
Code planning	Aider + VS Code	Aider can work from actual repo files; VS Code is used to inspect, search, and review the proposed plan before changes.
Implementation	Aider first, GitHub Copilot second	Aider should make repo-aware edits with explicit file context. GitHub Copilot is useful for small local functions, tests, and editor autocomplete.
Local reasoning	Ollama Qwen2.5:7B	Use for private/local repo questions, repetitive transformations, summarizing files, and offline reasoning. Do not rely on it as the only source for complex architectural changes.
Docker live deployment	VS Code terminal + Docker CLI	Use bind mounts, docker cp, or in-container reload commands so code can be tested without rebuilding or restarting the full image unless dependencies changed.
Testing	Automated tests + human smoke tests	AI can write and run repeatable checks, but human verification is required for SageTV UI behavior, visual regressions, and latency feel.
Fix / re-implement loop	Aider + test logs	Feed failing test output and exact files back into Aider. Keep each correction small and commit after each stable pass.
Repo update / push	Git CLI + GitHub	Git is the protection against AI memory loss. Commit at phase boundaries and after each tested increment, then push to the cloud repo.
C.2 Recommended Build Loop
1.	Update the PRD here first. Clarify feature scope, acceptance criteria, risks, and non-goals before touching code.
2.	Create a small implementation plan. In VS Code, identify exact files, functions, tests, and Docker deployment path. The plan should fit one focused task, not the whole project.
3.	Start from a clean git state. Run status/diff checks before AI edits. If there is uncommitted work, commit it or intentionally stash it first.
4.	Use Aider for repo-aware edits. Add only the relevant files to the Aider context. Ask for the smallest patch that satisfies one acceptance criterion.
5.	Use GitHub Copilot for local helpers. Let Copilot complete functions, tests, and syntax while you remain in the editor, but do not let it rewrite broad areas without review.
6.	Deploy into Docker without rebuilding when possible. Copy changed source files into the running container, use bind-mounted source directories, or trigger the application’s reload path. Rebuild the image only when dependencies, base image, packaging, or container startup behavior changes.
7.	Run automated checks. Execute unit tests, XML parse checks, grep-based acceptance checks, idempotency checks, and compose/diff validation as applicable to the phase.
8.	Run human validation. Load the STV in SageTV, navigate representative screens, test plugin behavior, and confirm perceived latency or visual behavior.
9.	Fix in small loops. Feed exact failures, logs, and file names back into Aider. Avoid broad rewrites after a partial success.
10.	Commit and push. Commit only after tests pass. Include phase, tool, metric deltas, and test evidence in the message. Push to GitHub after each stable milestone.
C.3 Tool Boundaries and Anti-Loss Rules
•	Do not ask GitHub Copilot to remember project history. It should operate on current files and short tasks only.
•	Do not ask Aider to infer product scope from code alone. Give it the relevant PRD section and the specific acceptance criterion.
•	Do not let Ollama/Qwen be the only reviewer for high-risk changes. Use it for local reasoning, but verify with tests and human inspection.
•	Do not edit generated outputs as source. If Phase 4 modularization is complete, edit module files and compose the canonical SageTV7.xml.
•	Do not rebuild Docker by habit. Rebuild only when live file replacement cannot represent the change safely.
•	Do not continue after unexplained diffs. If git diff shows unexpected unrelated changes, stop and reconcile before further AI edits.
•	Commit before switching tools. A stable commit is the handoff point between Word planning, VS Code editing, Aider patching, Docker testing, and GitHub push.
C.4 Suggested Commit Discipline
Use a branch per phase or per tool-sized task. Commit after each successful AI-assisted increment, not after a full multi-day phase. Recommended commit message pattern: phase/tool/scope/result, for example: Phase 2: aider: add cache report mode; tests pass; SetLocal count +642. Push after each stable milestone so the GitHub cloud repo becomes the recovery point if a local AI session loses context or overwrites work.
C.5 Daily Operating Checklist
Use this checklist at the start of every coding session so the repo, Docker container, AI tools, and GitHub cloud copy stay synchronized. The goal is to keep each session small, reviewable, testable, and recoverable.
C.5.1 Pre-flight: protect existing work
git status --short
git branch --show-current
git fetch --all --prune
git log --oneline --decorate -5
git diff --stat
•	If git status is not clean, stop and decide whether to commit, stash, or discard the existing changes before asking any AI tool to edit files.
•	If the current branch is wrong, switch branches before starting. Do not mix unrelated fixes on the same branch.
•	If the local branch is behind the remote branch, pull or rebase before beginning so AI edits are based on the latest repo state.
C.5.2 Create or select the task branch
git switch -c phase2-cache-report-mode
# or, for an existing branch:
git switch phase2-cache-report-mode
Branch names should describe the phase and the smallest useful unit of work. Good examples include phase1-dedup-json-report, phase2-cache-idempotency, phase3-theme-cycle-detect, and phase4-compose-hooks.
C.5.3 Start Aider with local Ollama/Qwen for repo-aware work
ollama list
ollama run qwen2.5:7b
# In a separate terminal from the repo root:
aider --model ollama/qwen2.5:7b --no-auto-commits
Only add the files needed for the current task to Aider. Do not give Aider the whole repo unless the task truly requires cross-repo analysis.
/add output/stv_cache_patcher.py
/add tests/test_stv_cache_patcher.py
/add docs/cache_patcher_notes.md
C.5.4 Standard task prompt for Aider
We are implementing PRD section [phase/requirement/acceptance criterion].
Goal: [one specific outcome].
Constraints:
- Make the smallest safe patch.
- Preserve existing working behavior.
- Do not rewrite unrelated code.
- Add or update tests for the changed behavior.
- Stop after the patch and list exact commands I should run.
C.5.5 Use GitHub Copilot only for narrow editor tasks
•	Use GitHub Copilot autocomplete for small helper functions, argument parsing, docstrings, unit-test fixtures, and syntax-level cleanup.
•	Do not ask GitHub Copilot to rewrite large files, infer repo history, or continue multi-day work from memory.
•	If Copilot proposes edits outside the selected function or file, reject the broad edit and restate the task more narrowly.
C.5.6 Docker live-deploy without rebuilding when possible
docker ps
docker compose ps
docker cp output/stv_cache_patcher.py sagetv:/opt/sagetv/tools/stv_cache_patcher.py
docker exec -it sagetv bash
python /opt/sagetv/tools/stv_cache_patcher.py /var/media/SageTV7.xml --report
•	Prefer bind-mounted source directories for rapid iteration. If a file is bind-mounted, edit it in VS Code and run the tool inside the container without copying.
•	Use docker cp for individual changed files when bind mounts are not available.
•	Rebuild the image only if dependencies, base image packages, entrypoint behavior, environment variables, or container startup scripts changed.
C.5.7 Automated checks before human testing
python -m pytest tests/ -q
python stv_analyzer.py SageTV7.xml --json current_baseline.json
python -m xml.etree.ElementTree SageTV7_phase_output.xml
grep -c 'SetLocal' SageTV7_phase_output.xml
grep -c 'GetLocal.*GetLocal' SageTV7_phase_output.xml
git diff --stat
git diff --check
For each phase, run the phase-specific acceptance checks from Section 7 before loading the output in SageTV. If automated checks fail, do not begin human UI testing yet.
C.5.8 Human smoke test checklist
•	Load the generated STV in a non-production SageTV test instance first.
•	Navigate Home, Videos, Recordings, Guide, Search, Settings, and plugin-contributed screens.
•	Move focus repeatedly through long lists and note whether selection lag improves or regresses.
•	Change at least one setting affected by cached GetProperty or GetServerProperty calls and verify the UI updates correctly.
•	Start and stop media playback to verify any GetCurrentMediaFile-dependent cache refreshes.
•	Install and uninstall representative STVi plugins when the phase touches plugin hooks or modularization.
C.5.9 Fix-loop prompt for failed tests
The last patch failed this exact check:
[paste command]
Failure output:
[paste full error/log excerpt]
Only inspect and change the files already added to this Aider session unless you ask me first.
Make the smallest fix, preserve passing behavior, and tell me the exact retest command.
C.5.10 Commit, tag milestone, and push
git status --short
git diff --stat
git add output/stv_cache_patcher.py tests/test_stv_cache_patcher.py docs/cache_patcher_notes.md
git commit -m "Phase 2: aider: add cache report mode; tests pass"
git push -u origin phase2-cache-report-mode
For stable phase outputs, optionally add a lightweight tag after the branch is pushed and verified.
git tag phase2-cache-report-pass
git push origin phase2-cache-report-pass
C.5.11 End-of-session handoff note
At the end of each session, write a short handoff note in the relevant issue, PR, or local work log. Include what changed, what passed, what failed, which Docker container/file was tested, and the next exact task. This note is the recovery point if any AI tool loses context later.
Session summary:
- Branch:
- PRD section / acceptance criterion:
- Files changed:
- Commands run:
- Tests passed:
- Docker test path:
- Remaining risks:
- Next task:
C.6 Repo-Local AI Workflow Harness
The manual checklist in C.5 should be treated as the operating model, not the long-term user interface. The repo should include a small local workflow harness that records where each tool stopped, what changed, what must happen next, and whether the repo is safe to continue. This harness is intentionally simple: it uses plain files, JSON state, Markdown handoffs, and script commands so it works with Aider, VS Code, GitHub Copilot, Docker CLI, and normal Git without needing a cloud service.
C.6.1 Harness goals
•	Make every AI-assisted step resumable after context loss.
•	Prevent Aider, GitHub Copilot, or the user from accidentally continuing from a dirty or ambiguous repo state.
•	Write the next prompt for the right tool instead of relying on memory.
•	Record which actor finished last and which actor should act next.
•	Keep Docker live-deploy and test results tied to a specific commit, branch, and changed file set.
•	Make git commits and GitHub pushes the stable recovery points.
C.6.2 Drop-in repo file layout
.ai-workflow/
  state.json
  current_task.md
  next_aider_prompt.md
  next_copilot_prompt.md
  handoff.md
  test_plan.md
  test_results.md
  blockers.md
  session_log.md
scripts/
  ai-workflow.ps1
  ai_workflow.py
The .ai-workflow directory is the shared memory. Aider, Copilot, Docker tests, and the user should read and update these files rather than relying on chat history or editor memory.
C.6.3 State file schema
{
  "version": 1,
  "project": "SageTV STV Optimization",
  "phase": "Phase 2",
  "task": "cache report mode",
  "branch": "phase2-cache-report-mode",
  "status": "aider_prompt_ready",
  "last_actor": "user",
  "next_actor": "aider",
  "files_in_scope": [],
  "files_changed": [],
  "last_verified_command": "",
  "last_test_result": "not_run",
  "docker_container": "sagetv",
  "next_instruction": "Give Aider next_aider_prompt.md and stop after patch.",
  "safe_to_commit": false,
  "safe_to_push": false
}
C.6.4 Baton-passing statuses
Status	Next Actor	Meaning
task_started	user	Task exists but the repo has not been prepared yet.
aider_prompt_ready	aider	Paste next_aider_prompt.md into Aider.
aider_patch_ready	vscode_copilot	Aider changed files; review in VS Code before continuing.
copilot_review_ready	test_runner	VS Code/GitHub Copilot review is complete; run tests.
tests_failed	aider	A failure prompt has been written for Aider with exact logs.
tests_passed	user	Automated tests passed; human smoke test or commit may proceed.
ready_to_commit	git	Repo is clean enough to stage and commit the task result.
pushed	user	GitHub is now the recovery point for this task.
C.6.5 Command interface
./scripts/ai-workflow.ps1 init
./scripts/ai-workflow.ps1 start phase2-cache-report-mode "Phase 2" "cache report mode"
./scripts/ai-workflow.ps1 status
./scripts/ai-workflow.ps1 aider-next
./scripts/ai-workflow.ps1 aider-done
./scripts/ai-workflow.ps1 copilot-next
./scripts/ai-workflow.ps1 copilot-done
./scripts/ai-workflow.ps1 test
./scripts/ai-workflow.ps1 fail "python -m pytest tests/ -q"
./scripts/ai-workflow.ps1 pass "python -m pytest tests/ -q"
./scripts/ai-workflow.ps1 ready-commit
./scripts/ai-workflow.ps1 pushed
The PowerShell script should be the user-facing command on Windows. The Python script should hold the cross-platform logic and be callable directly from Linux, WSL, CI, or Docker if needed.
C.6.6 What each command must do
•	init creates .ai-workflow and default files if missing.
•	start checks git status, records branch/phase/task, writes current_task.md, and creates the first next_aider_prompt.md.
•	status prints the current baton state, last actor, next actor, files changed, and safe-to-commit flags.
•	aider-next prints the path to next_aider_prompt.md and verifies that next_actor is aider.
•	aider-done records git diff --name-only, sets last_actor to aider, sets next_actor to vscode_copilot, and writes next_copilot_prompt.md.
•	copilot-next prints the Copilot review instructions and reminds the user to reject broad rewrites.
•	copilot-done records review completion and sets next_actor to test_runner.
•	test runs configured test commands or prints the test plan if commands are not yet configured.
•	fail records failed command output location, sets status to tests_failed, and writes a narrow Aider fix prompt.
•	pass records the last verified command, sets status to tests_passed, and blocks commit until git diff is reviewed.
•	ready-commit verifies tests_passed, writes a commit message suggestion, and sets safe_to_commit to true.
•	pushed records that GitHub has the stable milestone and sets status to pushed.
C.6.7 Aider handoff prompt template
Read .ai-workflow/current_task.md and .ai-workflow/state.json first.
Implement only the current task.
Use only the files listed in files_in_scope unless you ask first.
Make the smallest safe patch.
Preserve existing working behavior.
Add or update tests if the task changes behavior.
When done, stop and list exact commands to run.
Do not commit.
C.6.8 GitHub Copilot handoff prompt template
Review the current git diff only.
Do not infer old repo history.
Do not rewrite unrelated code.
Focus on syntax errors, obvious missed tests, narrow refactors, and readability issues.
If a broader issue is found, write it to .ai-workflow/blockers.md instead of changing it immediately.
C.6.9 Docker and test integration
The harness should not assume Docker rebuilds are required. For each task it should record whether the change is source-only, dependency-changing, image-changing, or compose-changing. Source-only changes should use bind mounts or docker cp. Dependency, image, entrypoint, or environment changes should require an explicit rebuild flag.
docker ps
docker compose ps
docker cp [changed-file] sagetv:[container-path]
docker exec sagetv [test-command]
C.6.10 Aider bootstrap prompt to create the harness
Paste the following into Aider from the repo root to create the first version of the harness:
Create a repo-local AI workflow harness for this project.
Create these files:
- .ai-workflow/state.json
- .ai-workflow/current_task.md
- .ai-workflow/next_aider_prompt.md
- .ai-workflow/next_copilot_prompt.md
- .ai-workflow/handoff.md
- .ai-workflow/test_plan.md
- .ai-workflow/test_results.md
- .ai-workflow/blockers.md
- .ai-workflow/session_log.md
- scripts/ai-workflow.ps1
- scripts/ai_workflow.py
Implement commands: init, start, status, aider-next, aider-done, copilot-next, copilot-done, test, fail, pass, ready-commit, pushed.
Use JSON for state and Markdown for prompts/handoffs.
Do not require network access.
Do not auto-commit or auto-push.
Make the PowerShell wrapper call the Python script so logic stays in one place.
Before changing files, show the planned file contents and ask for confirmation.
C.6.11 Operating rule after the harness exists
Once the harness is in the repo, do not manually copy the full checklist from this PRD into every AI session. Instead, run the harness command for the current baton state, then give the generated prompt file to the correct tool. The harness becomes the shared memory; git commits and GitHub pushes remain the durable recovery points.
C.6.12 Feature lanes for reusable media-center work
The harness should be generic enough to support any future SageTV-NG media-center feature, not just STV optimization. Because nearly every user-facing feature needs an STV access path, the harness must treat STV work as a standard required companion lane for feature delivery. The functional lane proves the capability exists; the STV access lane proves users can discover it, configure it, launch it, and recover from errors inside the SageTV UI.
C.6.12.1 Standard feature lanes
Lane	Used For	STV Requirement
generic_code	Utilities, analyzers, scripts, tests, docs	Usually none unless the output affects generated STV files.
server_api	Endpoints, metadata APIs, server behavior, protocol changes	Required if users need a menu entry, setting, status screen, or launch point.
media_playback	Players, codecs, telemetry overlays, captions, subtitles, device behavior	Required for playback menu entry, options menu, overlay controls, and user settings.
client_ui	Android, Windows, Mac, PWA, mini-client UI behavior	Required if legacy STV users need parallel access, fallback behavior, or configuration.
docker_deploy	Image, compose, bind mounts, container scripts, live deploy	Required if the feature needs an STV-visible service status, configuration, or reload path.
stv_access	Menus, hooks, settings screens, launch buttons, options panels	Required for nearly all user-facing features.
stv_menu_slow_lane	High-risk STV graph/menu edits	Used when the STV access work changes menu structure, focus, hooks, navigation, or plugin attachment points.
C.6.12.2 Feature lifecycle rule
Every new user-facing feature should be planned as two linked tracks: the functional implementation track and the STV access track. The functional implementation proves the capability works. The STV access track proves users can discover it, configure it, launch it, see status/error feedback, and back out safely inside the SageTV UI. A feature should not be considered complete until both tracks pass their tests.
Default rule: if a feature is intended for end users, set requires_stv_access to true unless the PRD explicitly declares the feature as internal-only, developer-only, background-only, or diagnostic-only.
For most media-center features, STV access should not be postponed as a cleanup task. It should be planned alongside the backend, playback, client, Docker, or API work so menu placement, settings, options panels, status/error screens, and runtime tests are known before implementation starts.
C.6.12.3 Required state fields for reusable feature work
{
  "feature_name": "DJI video telemetry playback",
  "primary_lane": "media_playback",
  "requires_stv_access": true,
  "stv_access_lane": "stv_menu_slow_lane",
  "user_entry_points": ["Videos", "Options menu", "Settings"],
  "functional_status": "not_started",
  "stv_access_status": "not_started",
  "feature_complete": false
}
C.6.12.4 Completion gate for all user-facing features
•	A feature is not complete if backend code works but no STV menu path exposes it to users.
•	A feature is not complete if an STV menu entry exists but the backend capability is untested.
•	A feature is not complete if the STV entry works only from one screen but lacks required settings, options, error states, or status feedback.
•	A feature is not complete if the STV access path has not passed human runtime navigation testing.
•	The harness should block feature_complete=true until functional_status and stv_access_status are both passed.
C.6.12.5 Default lane flow for a new media-center feature
1.	Define the feature in the PRD, including user-visible entry points.
2.	Start the harness with the primary functional lane, such as media_playback or server_api.
3.	Implement and test the functional capability first using Aider plus focused VS Code/GitHub Copilot review.
4.	Open the STV access lane and inventory the menus, hooks, option panels, and settings screens that must expose the feature.
5.	If the STV work touches menu graph structure, focus traversal, hooks, or navigation, escalate to stv_menu_slow_lane automatically.
6.	Run static STV checks, compose checks, and human SageTV runtime navigation tests.
7.	Mark the feature complete only after both the functional lane and the STV access lane are passed and committed.
C.6.13 Special slow lane for STV menu work
STV menu work is the highest-risk part of this project because VS Code/GitHub Copilot often struggles to preserve menu graph structure, navigation behavior, widget IDs, focus rules, and plugin hook behavior across long edits. Any task that modifies Menu widgets, screen guards, BeforeMenuLoad, AfterMenuLoad, focus traversal, visible panels, option menus, or STVi hook targets must use a slower baton process than ordinary Python tooling changes.
C.6.13.1 Menu-specific workflow files
.ai-workflow/menus/
  menu_inventory.json
  current_menu_task.md
  menu_baton.json
  menu_diff_notes.md
  menu_test_matrix.md
  menu_blockers.md
  menu_session_log.md
The harness should create the menu-specific folder only when a task enters the menu slow lane. This avoids treating normal Python tool changes as long-running UI graph surgery.
C.6.13.2 Menu baton states
Status	Next Actor	Meaning
menu_inventory_ready	user	Target menus, IDs, Sym prefixes, hooks, and current structure have been captured before editing.
menu_plan_ready	aider	A small menu-edit plan exists; Aider may make the first constrained patch.
menu_patch_partial	vscode_copilot	Aider made a partial structural change; VS Code review must inspect exact menu diff and reject broad rewrites.
menu_review_notes_ready	aider	VS Code/GitHub Copilot review produced narrow notes; Aider may continue from those notes only.
menu_runtime_test_required	human	XML and static checks passed, but SageTV navigation/focus/plugin behavior must be manually tested.
menu_runtime_failed	aider	Human test found a specific UI failure; Aider gets exact screen, steps, expected result, and actual result.
menu_runtime_passed	git	Menu change passed static checks and human SageTV smoke tests; commit may proceed.
C.6.13.3 Slow-lane rules for Aider and VS Code/GitHub Copilot
•	One menu or one menu family per task. Do not allow a single task to edit many unrelated Menu widgets.
•	Inventory before editing. Capture menu name, ID, Sym prefix, parent/child structure, BeforeMenuLoad, AfterMenuLoad, listeners, focus widgets, option menus, and hook targets before any patch.
•	Aider handles structural XML edits first. Aider should create or move graph structure because it works from explicit file context and can be forced to stop after a small patch.
•	VS Code/GitHub Copilot reviews and assists only narrowly. Use it for reading diffs, spotting syntax mistakes, adding comments, or writing helper tests. Do not ask it to redesign the menu graph or continue a large menu migration from memory.
•	Every handoff must write where it stopped. If a menu is only partially wired, record the exact completed sub-step, unfinished sub-step, current status, and next actor in menu_baton.json and menu_session_log.md.
•	No commit until runtime test. Static XML checks are not sufficient for menus. The change must be loaded in SageTV and navigated by a human before it can be marked ready_to_commit.
C.6.13.4 Required menu inventory fields
{
  "menu_name": "Video Browser",
  "menu_id": "45",
  "sym_prefix": "OPUS4A",
  "source_file": "SageTV7.xml or modules/opus4a.stv",
  "entry_actions": ["BeforeMenuLoad", "AfterMenuLoad"],
  "focus_widgets": [],
  "option_menu_widgets": [],
  "plugin_hook_targets": [],
  "known_navigation_paths": [],
  "test_required": true
}
C.6.13.5 Menu handoff note template
Menu handoff:
- Menu name / ID:
- Branch:
- Source file:
- Last actor:
- Next actor:
- Completed sub-step:
- Incomplete sub-step:
- Files changed:
- Static checks run:
- SageTV runtime test status:
- Exact next instruction:
C.6.13.6 Menu-specific test rule
For STV menu changes, the harness should require both static and runtime evidence. Static evidence includes XML parse success, ID uniqueness, Ref resolution, IsCurrentMenu guard count, hook target resolution, and compose round-trip checks. Runtime evidence includes manually loading the STV, navigating into the changed menu, moving focus in every major direction, opening and closing option menus, returning to the previous screen, and confirming plugin-contributed UI still appears in the expected location.
If the menu cannot be fully tested in one sitting, the harness must leave the status at menu_runtime_test_required or menu_runtime_failed. It must not allow ready_to_commit for that task until the human runtime test is complete and recorded.
C.6.14 Automatic Runner Mode
The harness should support an automatic runner mode that turns the PRD and Markdown guidance into operational repo instructions. In this mode, the user should not manually copy the full checklist into every tool. Instead, the harness reads state.json, writes the next prompt file, advances the baton, runs configured checks, and repeats until it reaches a human-required gate.
./scripts/ai-workflow.ps1 run-until-human
C.6.14.1 What run-until-human does
1.	Reads .ai-workflow/state.json to determine status, lane, last_actor, next_actor, retry count, and whether STV access is required.
2.	Verifies git safety before doing anything else. If the repo has unexplained changes, mismatched branch state, unresolved conflicts, or untracked files outside the task scope, it stops.
3.	If next_actor is aider, writes next_aider_prompt.md and prints or launches the Aider instruction for the current task.
4.	After Aider finishes, records changed files, updates handoff.md, and moves the baton to vscode_copilot unless the state says Aider requested clarification.
5.	If next_actor is vscode_copilot, writes next_copilot_prompt.md for a narrow diff review and prevents broad rewrite instructions.
6.	After Copilot review, records review notes and moves the baton to test_runner.
7.	If next_actor is test_runner, runs configured automated tests, static STV checks, Docker live-deploy checks, and lane-specific acceptance checks.
8.	If tests fail, writes test_results.md and a focused Aider fix prompt, increments retry_count, and returns the baton to Aider.
9.	If tests pass and the feature does not require human STV validation, moves toward ready_to_commit.
10.	If tests pass and STV access or menu work is involved, writes human_test_required.md and stops.
C.6.14.2 Stop conditions
•	Human STV testing is required. Any feature with requires_stv_access=true must stop for human SageTV runtime validation before completion.
•	Git state is unsafe. Stop if there are unresolved conflicts, unexpected diffs, untracked files outside the task scope, branch mismatch, failed rebase/merge state, or dirty generated outputs that cannot be explained.
•	Aider or Copilot requests clarification. Stop if either tool cannot proceed without a product decision, architectural decision, missing file, missing test fixture, or user confirmation.
•	Tests keep failing past retry limit. Stop after the configured retry limit so the user can inspect whether the task scope or design is wrong.
•	Menu slow-lane runtime validation is required. Stop when status is menu_runtime_test_required, menu_runtime_failed, or any equivalent state that requires the user to navigate SageTV manually.
•	Potential broad rewrite detected. Stop if the diff touches unrelated subsystems, rewrites large files unexpectedly, removes working behavior, or changes generated files instead of source modules.
C.6.14.3 Files written by automatic mode
File	Purpose
next_human_action.md	Short instruction telling the user exactly why automation stopped and what to do next.
human_test_required.md	Human SageTV validation checklist with exact screens, menus, settings, playback paths, plugin hooks, and expected results.
resume_after_human.md	Commands and state update instructions to resume automation after the user records pass/fail results.
runner_log.md	Chronological log of automatic state transitions, commands run, files changed, retries, and stop reasons.
C.6.14.4 Retry and failure policy
Automatic mode should use conservative retries. The default retry limit should be three failed automated test loops per task. Each retry must narrow the prompt by including the exact failed command, failure output, changed files, and the instruction to make the smallest fix. If the same failure appears twice with no material diff improvement, the runner should stop early and ask for human review rather than letting AI churn on the repo.
C.6.14.5 Required state fields for automatic mode
{
  "runner_mode": "run_until_human",
  "retry_count": 0,
  "retry_limit": 3,
  "stop_reason": "",
  "human_gate_required": false,
  "human_gate_type": "",
  "human_gate_files": [],
  "automation_can_resume": false,
  "last_runner_step": "",
  "last_stop_timestamp": ""
}
C.6.14.6 Resume after human testing
After human testing, the user should record the result in human_test_required.md or the menu test matrix. If the test passed, the harness should mark the relevant lane passed, write resume_after_human.md, and allow ready-commit. If the test failed, the harness should capture the exact screen, navigation path, expected result, actual result, screenshots or log paths if available, and generate a new Aider fix prompt. Automation may resume only after the human result has been recorded and the git state remains safe.
C.6.14.7 Bootstrap prompt update
The Aider bootstrap prompt in C.6.10 should be extended so the initial harness implementation includes run-until-human mode and the three human gate files. The implementation must not auto-commit or auto-push by default. It may suggest commit and push commands only after tests pass, STV runtime gates are satisfied, and the user explicitly approves.