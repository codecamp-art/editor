from __future__ import annotations

from pathlib import Path

from workflow.remote_workflow import (
    DEFAULT_TRIGGER_RULE,
    START_STOP_ACTIONS,
    STATUS_ACTION,
    WorkflowPlan,
    effective_dependency_trigger_rule_for_task,
    normalize_string_tuple,
    plan_dependency_map_for_action,
    require_workflow_action,
    sanitize_task_id,
)
from workflow.remote_workflow_validation import validate_workflow_json_file


def mermaid_quote(value: str) -> str:
    return '"' + value.replace('"', '\\"') + '"'


def mermaid_node_id(task_id: str) -> str:
    return f"n_{sanitize_task_id(task_id)}"


def workflow_plan_to_mermaid(
    plan: WorkflowPlan,
    *,
    action: str,
    title: str | None = None,
) -> str:
    require_workflow_action(action)
    dependency_map = plan_dependency_map_for_action(plan, action)
    group_lookup = {group_spec.group_id: group_spec for group_spec in plan.groups}
    group_task_ids = {
        group_spec.group_id: tuple(task_spec.task_id for task_spec in group_spec.tasks)
        for group_spec in plan.groups
    }
    grouped_task_ids = {
        task_id
        for task_ids in group_task_ids.values()
        for task_id in task_ids
    }

    lines = ["flowchart TD"]
    if title:
        lines.append(f"  %% {title}")
    lines.extend(["  wf_start((start))", "  wf_end((end))"])

    for group_spec in plan.groups:
        lines.append(
            f"  subgraph g_{sanitize_task_id(group_spec.group_id)}"
            f"[{mermaid_quote(group_spec.group_id)}]"
        )
        for task_spec in group_spec.tasks:
            lines.append(
                f"    {mermaid_node_id(task_spec.task_id)}"
                f"[{mermaid_quote(task_spec.task_id)}]"
            )
        lines.append("  end")

    for task_spec in plan.tasks:
        if task_spec.task_id in grouped_task_ids:
            continue
        lines.append(
            f"  {mermaid_node_id(task_spec.task_id)}"
            f"[{mermaid_quote(task_spec.task_id)}]"
        )

    if not plan.tasks:
        lines.append("  wf_start --> wf_end")
        return "\n".join(lines)

    upstream_references: set[str] = set()
    for task_spec in plan.tasks:
        task_node_id = mermaid_node_id(task_spec.task_id)
        upstream_ids = dependency_map.get(task_spec.task_id, ())
        trigger_rule = effective_dependency_trigger_rule_for_task(
            task_spec,
            group_lookup,
            action,
        )
        if not upstream_ids:
            lines.append(f"  wf_start --> {task_node_id}")
            continue
        for upstream_id in upstream_ids:
            upstream_references.add(upstream_id)
            upstream_node_id = mermaid_node_id(upstream_id)
            if trigger_rule == DEFAULT_TRIGGER_RULE:
                lines.append(f"  {upstream_node_id} --> {task_node_id}")
            else:
                lines.append(
                    f"  {upstream_node_id} -->|{trigger_rule}| {task_node_id}"
                )

    for task_spec in plan.tasks:
        if task_spec.task_id not in upstream_references:
            lines.append(f"  {mermaid_node_id(task_spec.task_id)} --> wf_end")

    return "\n".join(lines)


def workflow_graph_markdown(
    config_file: str | Path,
    *,
    envs: str | list[str] | tuple[str, ...] | None = None,
    actions: str | list[str] | tuple[str, ...] | None = None,
    config_root: str | Path | None = None,
) -> str:
    workflow, plans = validate_workflow_json_file(
        config_file,
        envs=envs,
        config_root=config_root,
    )
    selected_actions = (
        normalize_string_tuple(actions)
        if actions is not None
        else workflow.actions
    )
    if not selected_actions:
        raise ValueError("At least one workflow action must be selected.")
    for action in selected_actions:
        require_workflow_action(action)

    sections: list[str] = []
    for env_name, plan in plans.items():
        for action in selected_actions:
            if action not in workflow.actions and not (
                action == STATUS_ACTION and any(
                    start_stop_action in workflow.actions
                    for start_stop_action in START_STOP_ACTIONS
                )
            ):
                continue
            title = f"{workflow.workflow_id} {env_name} {action}"
            graph = workflow_plan_to_mermaid(plan, action=action, title=title)
            sections.append(f"## {title}\n\n```mermaid\n{graph}\n```")
    return "\n\n".join(sections)


def write_workflow_graph_markdown(
    config_file: str | Path,
    output_file: str | Path,
    *,
    envs: str | list[str] | tuple[str, ...] | None = None,
    actions: str | list[str] | tuple[str, ...] | None = None,
    config_root: str | Path | None = None,
) -> Path:
    output_path = Path(output_file)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        workflow_graph_markdown(
            config_file,
            envs=envs,
            actions=actions,
            config_root=config_root,
        ),
        encoding="utf-8",
    )
    return output_path
