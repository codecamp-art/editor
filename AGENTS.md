# AI Agent Rules

## Project

This repository contains Airflow DAG code under `dags/`.

The remote workflow framework is implemented in `dags/workflow/remote_workflow.py`.
It loads JSON workflow definitions from `dags/remote_workflows/**/*.json` and
registers Airflow DAGs for remote systemd services, Linux scripts, Windows
scripts, and general dependency-based remote workflows.

## Deployment Boundary

- Airflow runtime code belongs under `dags/workflow`, `dags/common`, and JSON
  workflow files under `dags/remote_workflows`.
- Local-only validation and graph preview utilities belong under `dags/tests`.
- Do not make `remote_workflow.py` depend on test-only helpers such as graph
  rendering or JSON validation scripts.
- JSON examples under `dags/remote_workflows` must remain loadable by Airflow
  without importing files from `dags/tests`.

## Remote Workflow Capabilities

- Start/stop workflows generate separate DAGs; status DAGs are generated for
  start/stop workflows.
- General workflows can use `actions: ["run"]` and do not need start, stop, or
  status controls.
- If only start dependencies are defined, stop dependencies default to the
  reversed start graph. Explicit stop dependencies override that behavior.
- Task dependencies must remain a DAG. Cycles should fail fast with clear error
  messages.
- Task groups can contain multiple tasks and support dependencies within the
  group and dependencies from tasks or groups outside the group.
- Airflow UI parameters support running the whole workflow, one or more tasks,
  one or more task groups, and status-only execution for start/stop workflows.
- Script fields support string, boolean, enum, and multi-enum parameters, with
  environment-level defaults and preset parameter overrides.

## JSON Configuration Rules

- Prefer self-contained JSON files: each system should define its own
  `host_groups`, runtime environment selection, schedules, metadata, and task
  graph in one JSON file.
- `host_groups` define remote execution targets. `tasks` and `task_groups`
  compose those targets into an Airflow dependency graph.
- Host group targets may define services or scripts once and expand them across
  multiple hosts using templated IDs such as `{host_id}` and other variables.
- Missing host targets in an environment should be skipped after validation has
  confirmed that the referenced task or group ID exists in the configuration.
- Use `enabled_in_envs` to decide where a JSON file is active.
- Use `runtime_envs` when one Airflow environment, such as PROD, must generate
  DAGs for multiple target environments such as PROD and DR.
- Use `dag_id_prefix`, `dag_id`, `dag_ids`, or schedule-pair DAG IDs only when
  a stable Airflow DAG ID must be pinned.
- Keep `dag_metadata` action-specific. Use it for action descriptions and tags.

## Remote Execution Rules

- Default `platform` is `rhel8`.
- `sudo_user`, `working_dir`, `platform`, `remote_env_vars`, command fields,
  retry settings, timeout settings, and remote execution settings can be
  overridden in order from defaults to service or script definitions, then
  environment level, then host level.
- Default sudo mode is non-interactive. Avoid interactive sudo in Airflow.
- Default `remote_execution_mode` is `auto`.
- RHEL7 Linux scripts run in the foreground. RHEL7 predefined systemd services
  use system-level units. RHEL8 systemd services and Linux scripts can use
  user-scope transient systemd units.
- Do not reintroduce `systemd_run_scope`; systemd scope is inferred from
  platform and task type.
- Default retry behavior is 2 retries with 10 seconds between retries, unless
  JSON overrides it.

## Local Validation

- Use `dags/tests/validate_remote_workflow.py` for local JSON validation and SVG
  graph preview generation.
- Example:

```powershell
$env:PYTHONPATH = "D:\Codes\local\Test\dags"
python dags\tests\validate_remote_workflow.py dags\remote_workflows\example_full_workflow.json --config-root dags\remote_workflows --graph-out dags\remote_workflows\graph_previews
```

- The graph tool writes SVG files. Use a `.svg` output path when one env/action
  graph is selected, or a directory when multiple graphs are selected.
- Validation tools may stub Airflow modules for local execution; production DAG
  parsing must not rely on those stubs.

## Engineering Rules

- Keep changes scoped to the workflow framework unless the user asks for a
  broader refactor.
- Preserve existing JSON behavior when refactoring. Add tests before or during
  changes that touch parsing, task expansion, dependency handling, DAG ID
  generation, or remote command construction.
- Prefer reusing existing helper functions in `remote_workflow.py` over adding
  parallel parsing or dependency logic.
- Do not duplicate JSON schema behavior in graph or validation tools; those
  tools should call the same workflow-building functions that Airflow uses.
- Do not commit generated `__pycache__` changes or local graph preview output
  unless the user explicitly asks for those artifacts.
