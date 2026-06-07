from __future__ import annotations

from dataclasses import dataclass
from html import escape
from pathlib import Path
from typing import Iterable

from workflow.remote_workflow import (
    DEFAULT_TRIGGER_RULE,
    START_STOP_ACTIONS,
    STATUS_ACTION,
    WorkflowPlan,
    WorkflowTaskSpec,
    effective_dependency_trigger_rule_for_task,
    normalize_string_tuple,
    plan_dependency_map_for_action,
    require_workflow_action,
    sanitize_task_id,
)
from workflow.remote_workflow_validation import validate_workflow_json_file


NODE_WIDTH = 190
NODE_HEIGHT = 68
NODE_X_GAP = 280
NODE_Y_GAP = 110
LEFT_MARGIN = 70
TOP_MARGIN = 105
TITLE_Y = 42
SUBTITLE_Y = 69
START_END_WIDTH = 92
START_END_HEIGHT = 52
GROUP_PADDING_X = 18
GROUP_PADDING_TOP = 36
GROUP_PADDING_BOTTOM = 20


@dataclass(frozen=True)
class SvgNode:
    task_id: str
    x: float
    y: float
    width: int = NODE_WIDTH
    height: int = NODE_HEIGHT

    @property
    def center_y(self) -> float:
        return self.y + (self.height / 2)

    @property
    def right_x(self) -> float:
        return self.x + self.width


def host_count_label(count: int) -> str:
    suffix = "host" if count == 1 else "hosts"
    return f"{count} {suffix}"


def wrap_label(value: str, *, max_chars: int = 22) -> tuple[str, ...]:
    if len(value) <= max_chars:
        return (value,)
    parts = value.split("_")
    lines: list[str] = []
    current = ""
    for part in parts:
        candidate = part if not current else f"{current}_{part}"
        if len(candidate) <= max_chars:
            current = candidate
            continue
        if current:
            lines.append(current)
        current = part
    if current:
        lines.append(current)
    return tuple(lines) or (value,)


def task_levels(
    tasks: Iterable[WorkflowTaskSpec],
    dependency_map: dict[str, tuple[str, ...]],
) -> dict[str, int]:
    task_ids = {task.task_id for task in tasks}
    levels: dict[str, int] = {}
    visiting: set[str] = set()

    def resolve(task_id: str) -> int:
        if task_id in levels:
            return levels[task_id]
        if task_id in visiting:
            raise ValueError(f"Dependency cycle detected at task '{task_id}'.")
        visiting.add(task_id)
        upstream_levels = [
            resolve(upstream_id)
            for upstream_id in dependency_map.get(task_id, ())
            if upstream_id in task_ids
        ]
        visiting.remove(task_id)
        levels[task_id] = (max(upstream_levels) + 1) if upstream_levels else 0
        return levels[task_id]

    for task_id in task_ids:
        resolve(task_id)
    return levels


def layout_nodes(
    plan: WorkflowPlan,
    dependency_map: dict[str, tuple[str, ...]],
) -> dict[str, SvgNode]:
    levels = task_levels(plan.tasks, dependency_map)
    level_counts: dict[int, int] = {}
    nodes: dict[str, SvgNode] = {}
    for task_spec in plan.tasks:
        level = levels[task_spec.task_id]
        index = level_counts.get(level, 0)
        level_counts[level] = index + 1
        nodes[task_spec.task_id] = SvgNode(
            task_id=task_spec.task_id,
            x=LEFT_MARGIN + START_END_WIDTH + 188 + (level * NODE_X_GAP),
            y=TOP_MARGIN + (index * NODE_Y_GAP),
        )
    return nodes


def graph_dimensions(nodes: dict[str, SvgNode]) -> tuple[int, int]:
    if not nodes:
        return 720, 260
    max_right = max(node.right_x for node in nodes.values())
    max_bottom = max(node.y + node.height for node in nodes.values())
    width = int(max_right + NODE_X_GAP + START_END_WIDTH + 258)
    height = int(max(max_bottom + 105, 260))
    return width, height


def graph_center_y(nodes: dict[str, SvgNode], height: int) -> float:
    if not nodes:
        return height / 2
    top = min(node.y for node in nodes.values())
    bottom = max(node.y + node.height for node in nodes.values())
    return top + ((bottom - top) / 2)


def svg_text(
    *,
    x: float,
    y: float,
    value: str,
    size: int,
    fill: str,
    weight: str | None = None,
    anchor: str | None = None,
) -> str:
    attrs = [
        f'x="{x}"',
        f'y="{y}"',
        'font-family="Arial, sans-serif"',
        f'font-size="{size}"',
        f'fill="{fill}"',
    ]
    if weight:
        attrs.append(f'font-weight="{weight}"')
    if anchor:
        attrs.append(f'text-anchor="{anchor}"')
    return f"<text {' '.join(attrs)}>{escape(value)}</text>"


def svg_task_node(task_spec: WorkflowTaskSpec, node: SvgNode, host_count: int) -> str:
    lines = [
        (
            f'<rect x="{node.x}" y="{node.y}" width="{node.width}" '
            f'height="{node.height}" rx="8" fill="#ffffff" stroke="#5f728a" '
            'stroke-width="1.3" filter="url(#shadow)"/>'
        )
    ]
    label_lines = wrap_label(task_spec.task_id)
    first_line_y = node.y + (23 if len(label_lines) == 1 else 17)
    for index, label in enumerate(label_lines[:2]):
        lines.append(
            svg_text(
                x=node.x + (node.width / 2),
                y=first_line_y + (index * 16),
                value=label,
                size=13,
                fill="#172033",
                weight="700",
                anchor="middle",
            )
        )
    lines.append(
        svg_text(
            x=node.x + (node.width / 2),
            y=node.y + node.height - 14,
            value=f"{task_spec.task_type} | {host_count_label(host_count)}",
            size=11,
            fill="#5d6b7a",
            anchor="middle",
        )
    )
    return "\n".join(lines)


def svg_endpoint_node(
    *,
    x: float,
    center_y: float,
    label: str,
    fill: str,
) -> str:
    y = center_y - (START_END_HEIGHT / 2)
    return "\n".join(
        [
            (
                f'<rect x="{x}" y="{y}" width="{START_END_WIDTH}" '
                f'height="{START_END_HEIGHT}" rx="22" fill="{fill}" '
                'stroke="#c7d0dd" filter="url(#shadow)"/>'
            ),
            svg_text(
                x=x + (START_END_WIDTH / 2),
                y=center_y + 5,
                value=label,
                size=14,
                fill="#172033",
                weight="700",
                anchor="middle",
            ),
        ]
    )


def svg_edge(
    *,
    source_x: float,
    source_y: float,
    target_x: float,
    target_y: float,
    label: str | None = None,
) -> str:
    source_control_x = source_x + max(45, (target_x - source_x) / 3)
    target_control_x = target_x - max(45, (target_x - source_x) / 3)
    parts = [
        (
            f'<path d="M {source_x:.1f} {source_y:.1f} '
            f'C {source_control_x:.1f} {source_y:.1f}, '
            f'{target_control_x:.1f} {target_y:.1f}, '
            f'{target_x:.1f} {target_y:.1f}" fill="none" stroke="#7b8794" '
            'stroke-width="1.5" marker-end="url(#arrow)"/>'
        )
    ]
    if label:
        parts.append(
            svg_text(
                x=(source_x + target_x) / 2,
                y=((source_y + target_y) / 2) - 8,
                value=label,
                size=11,
                fill="#7a4f01",
                anchor="middle",
            )
        )
    return "\n".join(parts)


def svg_group_box(group_id: str, member_nodes: list[SvgNode]) -> str:
    min_x = min(node.x for node in member_nodes) - GROUP_PADDING_X
    max_x = max(node.right_x for node in member_nodes) + GROUP_PADDING_X
    min_y = min(node.y for node in member_nodes) - GROUP_PADDING_TOP
    max_y = max(node.y + node.height for node in member_nodes) + GROUP_PADDING_BOTTOM
    return "\n".join(
        [
            (
                f'<rect x="{min_x}" y="{min_y}" width="{max_x - min_x}" '
                f'height="{max_y - min_y}" rx="10" fill="#f2f6ff" '
                'stroke="#7e9ed6" stroke-width="1.4" stroke-dasharray="6 4"/>'
            ),
            svg_text(
                x=min_x + 12,
                y=min_y + 22,
                value=group_id,
                size=13,
                fill="#7e9ed6",
                weight="700",
            ),
        ]
    )


def terminal_task_ids(
    task_ids: tuple[str, ...],
    dependency_map: dict[str, tuple[str, ...]],
) -> set[str]:
    upstream_references = {
        upstream_id
        for upstream_ids in dependency_map.values()
        for upstream_id in upstream_ids
        if upstream_id in task_ids
    }
    return set(task_ids) - upstream_references


def workflow_plan_to_svg(
    plan: WorkflowPlan,
    *,
    action: str,
    workflow_id: str = "remote_workflow",
    env_name: str | None = None,
) -> str:
    require_workflow_action(action)
    dependency_map = plan_dependency_map_for_action(plan, action)
    group_lookup = {group.group_id: group for group in plan.groups}
    nodes = layout_nodes(plan, dependency_map)
    width, height = graph_dimensions(nodes)
    center_y = graph_center_y(nodes, height)
    end_x = width - LEFT_MARGIN - START_END_WIDTH - 188
    task_ids = tuple(task.task_id for task in plan.tasks)
    terminal_ids = terminal_task_ids(task_ids, dependency_map)

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        "<defs>",
        '<marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0,0 L0,6 L9,3 z" fill="#7b8794" /></marker>',
        '<filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="2" stdDeviation="2" flood-color="#8090a5" flood-opacity="0.22"/></filter>',
        "</defs>",
        '<rect width="100%" height="100%" fill="#f7f9fc"/>',
        svg_text(
            x=38,
            y=TITLE_Y,
            value=f"{workflow_id} - {action.upper()} DAG",
            size=22,
            fill="#172033",
            weight="700",
        ),
        svg_text(
            x=38,
            y=SUBTITLE_Y,
            value=f"Target env: {env_name or 'current'} | Logical Airflow Graph View preview",
            size=13,
            fill="#5d6b7a",
        ),
    ]

    for group_spec in plan.groups:
        member_nodes = [
            nodes[task_spec.task_id]
            for task_spec in group_spec.tasks
            if task_spec.task_id in nodes
        ]
        if member_nodes:
            lines.append(svg_group_box(group_spec.group_id, member_nodes))

    if not plan.tasks:
        lines.append(
            svg_edge(
                source_x=LEFT_MARGIN + START_END_WIDTH,
                source_y=center_y,
                target_x=end_x - 6,
                target_y=center_y,
            )
        )
    else:
        for task_spec in plan.tasks:
            node = nodes[task_spec.task_id]
            upstream_ids = dependency_map.get(task_spec.task_id, ())
            trigger_rule = effective_dependency_trigger_rule_for_task(
                task_spec,
                group_lookup,
                action,
            )
            edge_label = None if trigger_rule == DEFAULT_TRIGGER_RULE else trigger_rule
            if not upstream_ids:
                lines.append(
                    svg_edge(
                        source_x=LEFT_MARGIN + START_END_WIDTH,
                        source_y=center_y,
                        target_x=node.x - 6,
                        target_y=node.center_y,
                    )
                )
                continue
            for upstream_id in upstream_ids:
                upstream_node = nodes.get(upstream_id)
                if not upstream_node:
                    continue
                lines.append(
                    svg_edge(
                        source_x=upstream_node.right_x,
                        source_y=upstream_node.center_y,
                        target_x=node.x - 6,
                        target_y=node.center_y,
                        label=edge_label,
                    )
                )
        for task_id in terminal_ids:
            node = nodes[task_id]
            lines.append(
                svg_edge(
                    source_x=node.right_x,
                    source_y=node.center_y,
                    target_x=end_x - 6,
                    target_y=center_y,
                )
            )

    lines.extend(
        [
            svg_endpoint_node(
                x=LEFT_MARGIN,
                center_y=center_y,
                label="start",
                fill="#d8f3dc",
            ),
            svg_endpoint_node(
                x=end_x,
                center_y=center_y,
                label="end",
                fill="#fde2e4",
            ),
        ]
    )

    task_lookup = {task_spec.task_id: task_spec for task_spec in plan.tasks}
    for task_id, node in nodes.items():
        task_spec = task_lookup[task_id]
        lines.append(
            svg_task_node(
                task_spec=task_spec,
                node=node,
                host_count=len(plan.hosts_by_task_id.get(task_id, ())),
            )
        )

    lines.append("</svg>")
    return "\n".join(lines)


def selected_graph_actions(
    workflow_actions: tuple[str, ...],
    actions: str | list[str] | tuple[str, ...] | None,
) -> tuple[str, ...]:
    selected_actions = (
        normalize_string_tuple(actions)
        if actions is not None
        else workflow_actions
    )
    if not selected_actions:
        raise ValueError("At least one workflow action must be selected.")
    for action in selected_actions:
        require_workflow_action(action)
    return selected_actions


def action_can_render(
    *,
    workflow_actions: tuple[str, ...],
    action: str,
) -> bool:
    if action in workflow_actions:
        return True
    return action == STATUS_ACTION and any(
        start_stop_action in workflow_actions
        for start_stop_action in START_STOP_ACTIONS
    )


def workflow_graph_svgs(
    config_file: str | Path,
    *,
    envs: str | list[str] | tuple[str, ...] | None = None,
    actions: str | list[str] | tuple[str, ...] | None = None,
    config_root: str | Path | None = None,
) -> tuple[tuple[str, str, str, str], ...]:
    workflow, plans = validate_workflow_json_file(
        config_file,
        envs=envs,
        config_root=config_root,
    )
    selected_actions = selected_graph_actions(workflow.actions, actions)

    documents: list[tuple[str, str, str, str]] = []
    for env_name, plan in plans.items():
        for action in selected_actions:
            if not action_can_render(workflow_actions=workflow.actions, action=action):
                continue
            documents.append(
                (
                    workflow.workflow_id,
                    env_name,
                    action,
                    workflow_plan_to_svg(
                        plan,
                        action=action,
                        workflow_id=workflow.workflow_id,
                        env_name=env_name,
                    ),
                )
            )
    if not documents:
        raise ValueError("No workflow graph matched the selected env/action filters.")
    return tuple(documents)


def graph_svg_file_name(workflow_id: str, env_name: str, action: str) -> str:
    return f"{sanitize_task_id(workflow_id)}_{sanitize_task_id(env_name)}_{sanitize_task_id(action)}.svg"


def write_workflow_graph_svg(
    config_file: str | Path,
    output_path: str | Path,
    *,
    envs: str | list[str] | tuple[str, ...] | None = None,
    actions: str | list[str] | tuple[str, ...] | None = None,
    config_root: str | Path | None = None,
) -> tuple[Path, ...]:
    documents = workflow_graph_svgs(
        config_file,
        envs=envs,
        actions=actions,
        config_root=config_root,
    )
    destination = Path(output_path)
    if destination.suffix.lower() == ".svg" and len(documents) == 1:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(documents[0][3], encoding="utf-8")
        return (destination,)
    if destination.suffix.lower() == ".svg":
        raise ValueError(
            "--graph-out points to one .svg file, but the selected filters "
            f"produce {len(documents)} graphs. Use an output directory or narrow "
            "the env/action filters."
        )

    destination.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for workflow_id, env_name, action, svg in documents:
        path = destination / graph_svg_file_name(workflow_id, env_name, action)
        path.write_text(svg, encoding="utf-8")
        paths.append(path)
    return tuple(paths)
