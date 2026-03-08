from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from airflow.decorators import get_current_context, task
from airflow.exceptions import AirflowSkipException
from airflow.operators.empty import EmptyOperator
from airflow.providers.ssh.operators.ssh import SSHOperator

from common.config_loader import get_current_env_name, load_topology_for_current_env
from common.dag_factory import (
    DEFAULT_RUNTIME_ENV_FILE,
    build_executor_config,
    build_runtime_context,
    dag_decorator,
)
from common.field_schema import (
    COMMON_FIELDS,
    SYSTEMD_CONTROL_FIELDS,
    build_airflow_params_from_fields,
    merge_field_definitions,
    validate_fields,
)
from common.remote_command import build_sudo_bash_command
from common.ssh_hook import MSSSHHook


DEFAULT_SYSTEMD_TOPOLOGY_FILE = Path(__file__).resolve().parents[1] / "configs" / "systemd_topologies.json"


@dataclass(frozen=True)
class SystemdProcessSpec:
    process_id: str
    service_name: str
    service_user: str
    host_group: str
    platform: str  # rhel7 | rhel8
    start_after: tuple[str, ...] = ()
    enabled_in_envs: tuple[str, ...] = ("dev", "qa", "prod", "dr")


@dataclass(frozen=True)
class SystemdWorkflowDefinition:
    workflow_id: str
    title: str
    description: str
    schedule_start: str
    schedule_stop: str
    fields: dict
    processes: tuple[SystemdProcessSpec, ...]
    tags: tuple[str, ...] = ("systemd", "ssh", "kerberos")
    command_timeout_seconds: int = 1800


def build_systemd_airflow_fields(extra_fields: dict) -> dict:
    return merge_field_definitions(
        COMMON_FIELDS,
        SYSTEMD_CONTROL_FIELDS,
        extra_fields,
    )


def build_systemd_command(platform: str, service_name: str, service_user: str, action: str) -> str:
    # Keep the command pattern aligned to your requirement wording.
    if platform == "rhel7":
        inner = ["sudo", "systemd", action, service_name]
    elif platform == "rhel8":
        inner = ["systemd", "--user", action, service_name]
    else:
        raise ValueError(f"Unsupported platform '{platform}'.")

    return build_sudo_bash_command(
        sudo_user=service_user,
        script_and_args=inner,
    )


def resolve_hosts_for_process(topology: dict, host_group: str) -> list[str]:
    groups = topology.get("host_groups", {})
    if host_group not in groups:
        raise KeyError(f"Host group '{host_group}' not found in topology.")
    return groups[host_group]


def filter_enabled_processes(processes: tuple[SystemdProcessSpec, ...], current_env: str) -> list[SystemdProcessSpec]:
    return [p for p in processes if current_env in p.enabled_in_envs]


def build_process_lookup(processes: list[SystemdProcessSpec]) -> dict[str, SystemdProcessSpec]:
    lookup = {}
    for p in processes:
        if p.process_id in lookup:
            raise ValueError(f"Duplicate process_id detected: {p.process_id}")
        lookup[p.process_id] = p
    return lookup


def validate_dependency_graph(processes: list[SystemdProcessSpec]) -> None:
    lookup = build_process_lookup(processes)
    for p in processes:
        for dep in p.start_after:
            if dep not in lookup:
                raise ValueError(f"Process '{p.process_id}' depends on unknown process '{dep}'.")


def create_systemd_dag(
    *,
    workflow: SystemdWorkflowDefinition,
    dag_id: str,
    action: str,  # start | stop
    schedule: str,
    runtime_env_file: str | Path = DEFAULT_RUNTIME_ENV_FILE,
    topology_file: str | Path = DEFAULT_SYSTEMD_TOPOLOGY_FILE,
):
    if action not in {"start", "stop"}:
        raise ValueError("action must be 'start' or 'stop'")

    runtime_context = build_runtime_context(runtime_env_file)
    topology = load_topology_for_current_env(topology_file)
    current_env = get_current_env_name()

    enabled_processes = filter_enabled_processes(workflow.processes, current_env)
    validate_dependency_graph(enabled_processes)

    airflow_fields = build_systemd_airflow_fields(workflow.fields)
    airflow_params = build_airflow_params_from_fields(airflow_fields)

    @dag_decorator(
        dag_id=dag_id,
        description=workflow.description,
        schedule=schedule,
        tags=list(workflow.tags),
        timezone=runtime_context["timezone"],
        params=airflow_params,
    )
    def _dag():
        @task(task_id="validate_inputs")
        def validate_inputs() -> dict:
            context = get_current_context()
            raw_params = dict(context["params"])
            validated = validate_fields(raw_params, airflow_fields)

            if validated["target_mode"] == "single_process" and not validated["target_process"]:
                raise ValueError("target_process is required when target_mode=single_process.")

            if validated["target_mode"] == "single_process":
                process_ids = {p.process_id for p in enabled_processes}
                if validated["target_process"] not in process_ids:
                    raise ValueError(
                        f"target_process '{validated['target_process']}' not found in enabled processes: {sorted(process_ids)}"
                    )

            return validated

        validated_task = validate_inputs()

        start_node = EmptyOperator(task_id="start")
        end_node = EmptyOperator(task_id="end")

        task_map: dict[tuple[str, str], SSHOperator] = {}
        process_roots: dict[str, list[SSHOperator]] = {}
        process_lookup = build_process_lookup(enabled_processes)

        for process in enabled_processes:
            hosts = resolve_hosts_for_process(topology, process.host_group)

            process_tasks: list[SSHOperator] = []

            for host in hosts:
                task_id = f"{process.process_id}__{host.replace('.', '_').replace('-', '_')}"
                command = build_systemd_command(
                    platform=process.platform,
                    service_name=process.service_name,
                    service_user=process.service_user,
                    action=action,
                )

                op = SSHOperator(
                    task_id=task_id,
                    ssh_hook=MSSSHHook(
                        remote_host=host,
                        username=runtime_context["kerberos_principal"],
                        enable_kerberos=True,
                    ),
                    command=command,
                    cmd_timeout=workflow.command_timeout_seconds,
                    executor_config=build_executor_config(runtime_context),
                )

                validated_task >> op
                task_map[(process.process_id, host)] = op
                process_tasks.append(op)

            process_roots[process.process_id] = process_tasks

        # Start DAG: dep >> current
        # Stop DAG: current >> dep
        for process in enabled_processes:
            current_tasks = process_roots.get(process.process_id, [])

            if not process.start_after:
                start_node >> current_tasks

            for dep_id in process.start_after:
                dep_tasks = process_roots.get(dep_id, [])

                if action == "start":
                    for dep_task in dep_tasks:
                        for cur_task in current_tasks:
                            dep_task >> cur_task
                else:
                    for cur_task in current_tasks:
                        for dep_task in dep_tasks:
                            cur_task >> dep_task

        for process in enabled_processes:
            current_tasks = process_roots.get(process.process_id, [])
            if not current_tasks:
                continue

            if action == "start":
                has_downstream = any(process.process_id in p.start_after for p in enabled_processes)
                if not has_downstream:
                    current_tasks >> end_node
            else:
                # In stop DAG, original roots become terminal nodes
                if not process.start_after:
                    current_tasks >> end_node

        # Single-process filter task
        @task(task_id="filter_target_mode")
        def filter_target_mode(validated: dict) -> dict:
            return validated

        filter_result = filter_target_mode(validated_task)

        # Lightweight skip guards per process/host
        for process in enabled_processes:
            hosts = resolve_hosts_for_process(topology, process.host_group)

            for host in hosts:
                original_task = task_map[(process.process_id, host)]

                @task(task_id=f"allow__{process.process_id}__{host.replace('.', '_').replace('-', '_')}")
                def allow_process(target: dict, process_id: str) -> str:
                    if target["target_mode"] == "workflow":
                        return "run"
                    if target["target_mode"] == "single_process" and target["target_process"] == process_id:
                        return "run"
                    raise AirflowSkipException(
                        f"Skipping process '{process_id}' because target_mode=single_process and it is not selected."
                    )

                allow_task = allow_process.override(
                    task_id=f"allow__{process.process_id}__{host.replace('.', '_').replace('-', '_')}"
                )(filter_result, process.process_id)

                # Rewire to insert guard immediately before remote task
                allow_task >> original_task

        start_node >> validated_task

    return _dag()