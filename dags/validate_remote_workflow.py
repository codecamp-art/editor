from __future__ import annotations

import argparse
import sys
import types
from pathlib import Path
from typing import Any


def split_csv_values(values: list[str] | None) -> tuple[str, ...] | None:
    if not values:
        return None
    split_values = [
        item.strip()
        for value in values
        for item in value.split(",")
        if item.strip()
    ]
    return tuple(dict.fromkeys(split_values)) or None


def install_airflow_validation_stubs() -> None:
    airflow = types.ModuleType("airflow")
    airflow_exceptions = types.ModuleType("airflow.exceptions")

    class AirflowSkipException(Exception):
        pass

    airflow_exceptions.AirflowSkipException = AirflowSkipException
    sys.modules.setdefault("airflow", airflow)
    sys.modules.setdefault("airflow.exceptions", airflow_exceptions)

    param_module = types.ModuleType("airflow.models.param")

    class Param:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            self.args = args
            self.kwargs = kwargs

    param_module.Param = Param
    sys.modules.setdefault("airflow.models", types.ModuleType("airflow.models"))
    sys.modules.setdefault("airflow.models.param", param_module)

    empty_module = types.ModuleType("airflow.providers.standard.operators.empty")

    class EmptyOperator:
        pass

    empty_module.EmptyOperator = EmptyOperator
    sys.modules.setdefault("airflow.providers", types.ModuleType("airflow.providers"))
    sys.modules.setdefault("airflow.providers.standard", types.ModuleType("airflow.providers.standard"))
    sys.modules.setdefault(
        "airflow.providers.standard.operators",
        types.ModuleType("airflow.providers.standard.operators"),
    )
    sys.modules.setdefault("airflow.providers.standard.operators.empty", empty_module)

    sensor_module = types.ModuleType("airflow.providers.standard.sensors.external_task")

    class ExternalTaskSensor:
        pass

    sensor_module.ExternalTaskSensor = ExternalTaskSensor
    sys.modules.setdefault(
        "airflow.providers.standard.sensors",
        types.ModuleType("airflow.providers.standard.sensors"),
    )
    sys.modules.setdefault("airflow.providers.standard.sensors.external_task", sensor_module)

    sdk_module = types.ModuleType("airflow.sdk")

    def get_current_context() -> dict[str, Any]:
        return {}

    def task(*args: Any, **kwargs: Any):
        def decorator(fn):
            fn.override = lambda **override_kwargs: fn
            return fn

        return decorator

    class TaskGroup:
        pass

    sdk_module.get_current_context = get_current_context
    sdk_module.task = task
    sdk_module.TaskGroup = TaskGroup
    sys.modules.setdefault("airflow.sdk", sdk_module)

    task_group_module = types.ModuleType("airflow.utils.task_group")
    task_group_module.TaskGroup = TaskGroup
    sys.modules.setdefault("airflow.utils", types.ModuleType("airflow.utils"))
    sys.modules.setdefault("airflow.utils.task_group", task_group_module)


def install_runtime_stubs() -> None:
    dag_factory_module = types.ModuleType("common.dag_factory")
    dag_factory_module.DEFAULT_RUNTIME_ENV_FILE = Path("dags/configs/runtime_envs.json")
    dag_factory_module.build_minimal_tenant_executor_config = lambda runtime_context: {}
    dag_factory_module.build_runtime_context = lambda owner=None, config_file=None: {
        "owner": owner or "airflow",
        "timezone": "Asia/Shanghai",
    }
    dag_factory_module.dag_decorator = lambda **kwargs: (lambda fn: fn)
    sys.modules.setdefault("common.dag_factory", dag_factory_module)

    ssh_hook_module = types.ModuleType("common.ssh_hook")

    class MSSSHHook:
        pass

    ssh_hook_module.MSSSHHook = MSSSHHook
    ssh_hook_module.execute_ssh_command = lambda **kwargs: "ok"
    sys.modules.setdefault("common.ssh_hook", ssh_hook_module)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate a remote workflow JSON file and optionally render a Mermaid graph."
    )
    parser.add_argument("config", type=Path, help="Path to a remote workflow JSON file.")
    parser.add_argument(
        "--config-root",
        type=Path,
        default=None,
        help="Root directory used to derive default workflow_id for nested JSON files.",
    )
    parser.add_argument(
        "--env",
        action="append",
        dest="envs",
        help="Environment to validate. Can be repeated or comma-separated.",
    )
    parser.add_argument(
        "--action",
        action="append",
        dest="actions",
        help="Action to render in the graph. Can be repeated or comma-separated.",
    )
    parser.add_argument(
        "--graph-out",
        type=Path,
        default=None,
        help="Write all requested graphs to one Markdown file.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    dags_dir = Path(__file__).resolve().parent
    if str(dags_dir) not in sys.path:
        sys.path.insert(0, str(dags_dir))

    install_airflow_validation_stubs()
    install_runtime_stubs()

    from workflow.remote_workflow import (  # noqa: WPS433
        validate_workflow_json_file,
        workflow_execution_warnings,
        write_workflow_graph_markdown,
    )

    envs = split_csv_values(args.envs)
    actions = split_csv_values(args.actions)

    try:
        workflow, plans = validate_workflow_json_file(
            args.config,
            envs=envs,
            config_root=args.config_root,
        )
        print(f"OK: {args.config}")
        print(f"workflow_id: {workflow.workflow_id}")
        print(f"actions: {', '.join(workflow.actions)}")
        print(f"validated_envs: {', '.join(plans) if plans else '(none)'}")
        for env_name, plan in plans.items():
            print(
                f"env {env_name}: {len(plan.tasks)} tasks, "
                f"{len(plan.groups)} task_groups"
            )
            for warning in workflow_execution_warnings(plan):
                print(f"WARNING: {warning}", file=sys.stderr)

        if args.graph_out:
            graph_path = write_workflow_graph_markdown(
                args.config,
                args.graph_out,
                envs=envs,
                actions=actions,
                config_root=args.config_root,
            )
            print(f"graph: {graph_path}")
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
