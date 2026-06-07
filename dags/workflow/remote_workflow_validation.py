from __future__ import annotations

from pathlib import Path

from common.config_loader import get_current_env_name

from workflow.remote_workflow import (
    RUN_ACTION,
    SCRIPT_TASK_TYPES,
    START_STOP_ACTIONS,
    STATUS_ACTION,
    SYSTEMD_TASK_TYPE,
    RemoteWorkflowDefinition,
    WorkflowPlan,
    apply_task_runtime_overrides,
    load_workflow_definition_from_json,
    normalize_string_tuple,
    prepare_workflow_plan,
    resolve_topology_for_env,
    resolved_remote_execution_mode,
    validate_task_spec,
)


def environment_names_from_workflow(workflow: RemoteWorkflowDefinition) -> tuple[str, ...]:
    names: set[str] = set()
    environments = workflow.environments or {}
    for env_name, topology in environments.items():
        if isinstance(topology, dict):
            names.update(collect_nested_environment_names(topology))
        if env_name not in {"base", "default", "defaults"}:
            names.add(str(env_name))
    return tuple(sorted(name for name in names if name not in {"base", "default", "defaults"}))


def collect_nested_environment_names(topology: dict) -> set[str]:
    names: set[str] = set()
    host_groups = topology.get("host_groups") or {}
    if not isinstance(host_groups, dict):
        return names
    for host_group_config in host_groups.values():
        if not isinstance(host_group_config, dict):
            continue
        environments = host_group_config.get("environments") or {}
        if isinstance(environments, dict):
            names.update(str(env_name) for env_name in environments)
    return names


def workflow_validation_envs(
    workflow: RemoteWorkflowDefinition,
    envs: str | list[str] | tuple[str, ...] | None = None,
) -> tuple[str, ...]:
    if envs is not None:
        selected_envs = normalize_string_tuple(envs)
        if not selected_envs:
            raise ValueError("At least one environment must be selected.")
        return selected_envs

    return (
        workflow.target_runtime_envs
        or environment_names_from_workflow(workflow)
        or (get_current_env_name(),)
    )


def validate_workflow_plan_runtime_configs(
    *,
    workflow: RemoteWorkflowDefinition,
    plan: WorkflowPlan,
    current_env: str,
) -> None:
    for action in workflow.actions:
        validation_action = action if action in (RUN_ACTION, STATUS_ACTION) else None
        for task_spec in plan.tasks:
            for host_target in plan.hosts_by_task_id.get(task_spec.task_id, ()):
                effective_task = apply_task_runtime_overrides(
                    task_spec,
                    host_target.task_overrides,
                )
                try:
                    validate_task_spec(
                        effective_task,
                        action=validation_action,
                    )
                except Exception as exc:
                    raise ValueError(
                        f"Task '{task_spec.task_id}' on host '{host_target.host}' "
                        f"is invalid for env '{current_env}' action '{action}': {exc}"
                    ) from exc


def validate_workflow_json_file(
    config_file: str | Path,
    *,
    envs: str | list[str] | tuple[str, ...] | None = None,
    config_root: str | Path | None = None,
) -> tuple[RemoteWorkflowDefinition, dict[str, WorkflowPlan]]:
    workflow = load_workflow_definition_from_json(
        config_file,
        config_root=config_root,
    )
    plans: dict[str, WorkflowPlan] = {}
    for env_name in workflow_validation_envs(workflow, envs):
        topology = resolve_topology_for_env(workflow, env_name)
        plan = prepare_workflow_plan(
            workflow=workflow,
            topology=topology,
            current_env=env_name,
        )
        validate_workflow_plan_runtime_configs(
            workflow=workflow,
            plan=plan,
            current_env=env_name,
        )
        plans[env_name] = plan
    return workflow, plans


def workflow_execution_warnings(plan: WorkflowPlan) -> tuple[str, ...]:
    warnings: list[str] = []
    for task_spec in plan.tasks:
        for host_target in plan.hosts_by_task_id.get(task_spec.task_id, ()):
            effective_task = apply_task_runtime_overrides(
                task_spec,
                host_target.task_overrides,
            )
            task_context = f"task '{task_spec.task_id}' host '{host_target.host}'"
            execution_mode = resolved_remote_execution_mode(effective_task)
            if effective_task.sudo_user and effective_task.sudo_mode != "non_interactive":
                warnings.append(
                    f"{task_context}: sudo_mode='{effective_task.sudo_mode}' can prompt "
                    "for sudo credentials; use sudo_mode='non_interactive' with NOPASSWD "
                    "sudoers for fully non-interactive Airflow execution."
                )
            if execution_mode == "systemd_run":
                warnings.append(
                    f"{task_context}: remote_execution_mode resolves to systemd_run. "
                    "The remote command runs in a transient systemd unit and Airflow "
                    "polls that unit for the result; if the Airflow worker or SSH "
                    "connection is interrupted, the retry can find the same unit by "
                    "dag_id/run_id/task/host."
                )
            elif effective_task.task_type in SCRIPT_TASK_TYPES:
                warnings.append(
                    f"{task_context}: script commands run in the foreground over SSH. "
                    "If the Airflow worker or SSH connection is interrupted, Airflow "
                    "cannot keep tracking that remote process and this task attempt fails."
                )
            elif effective_task.task_type == SYSTEMD_TASK_TYPE:
                warnings.append(
                    f"{task_context}: systemd start/stop/status is issued synchronously "
                    "over SSH. The service state is recoverable with a later status run, "
                    "but an interrupted Airflow worker or SSH connection fails the task "
                    "attempt that was waiting on systemctl."
                )
    return tuple(dict.fromkeys(warnings))
