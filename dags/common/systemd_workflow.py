from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from airflow.sdk import get_current_context, task
from airflow.exceptions import AirflowSkipException
from airflow.providers.standard.operators.empty import EmptyOperator
from airflow.providers.standard.sensors.external_task import ExternalTaskSensor

from common.config_loader import get_current_env_name, load_topology_for_current_env
from common.dag_factory import (
    DEFAULT_RUNTIME_ENV_FILE,
    build_executor_config,
    build_runtime_context,
    dag_decorator,
    get_sysid_from_file,
)
from common.field_schema import (
    COMMON_FIELDS,
    SYSTEMD_CONTROL_FIELDS,
    build_airflow_params_from_fields,
    merge_field_definitions,
    validate_fields,
)
from common.remote_command import build_sudo_bash_command
from common.ssh_hook import MSSSHHook, MSSSHOperator


DEFAULT_SYSTEMD_TOPOLOGY_FILE = Path(__file__).resolve().parents[1] / "configs" / "systemd_topologies.json"


@dataclass(frozen=True)
class ExternalDagDependency:
    dag_id: str
    task_id: str = "end"
    allowed_states: tuple[str, ...] = ("success",)
    failed_states: tuple[str, ...] = ("failed", "skipped")
    timeout_seconds: int = 4 * 60 * 60
    poke_interval_seconds: int = 60
    enabled_in_envs: tuple[str, ...] = ("dev", "qa", "prod", "dr")


@dataclass(frozen=True)
class SystemdProcessSpec:
    process_id: str
    service_name: str
    service_user: str
    host_group: str
    platform: str
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
    upstream_dags_for_start: tuple[ExternalDagDependency, ...] = ()
    upstream_dags_for_stop: tuple[ExternalDagDependency, ...] = ()
    tags: tuple[str, ...] = ("systemd", "ssh", "kerberos")
    command_timeout_seconds: int = 1800


def build_systemd_airflow_fields(extra_fields: dict) -> dict:
    return merge_field_definitions(
        COMMON_FIELDS,
        SYSTEMD_CONTROL_FIELDS,
        extra_fields,
    )


def build_systemd_command(platform: str, service_name: str, service_user: str, action: str) -> str:
    if platform == "rhel7":
        inner = f"sudo systemd {action} {service_name}"
    elif platform == "rhel8":
        inner = f"systemd --user {action} {service_name}"
    else:
        raise ValueError(f"Unsupported platform '{platform}'.")

    return build_sudo_bash_command(
        sudo_user=service_user,
        inner_command=inner,
    )


def resolve_hosts_for_process(topology: dict, host_group: str) -> list[str]:
    groups = topology.get("host_groups", {})
    if host_group not in groups:
        raise KeyError(f"Host group '{host_group}' not found in topology.")
    return groups[host_group]


def filter_enabled_processes(processes: tuple[SystemdProcessSpec, ...], current_env: str) -> list[SystemdProcessSpec]:
    return [p for p in processes if current_env in p.enabled_in_envs]


def filter_enabled_dependencies(
    dependencies: tuple[ExternalDagDependency, ...],
    current_env: str,
) -> tuple[ExternalDagDependency, ...]:
    return tuple(dep for dep in dependencies if current_env in dep.enabled_in_envs)


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


def create_external_dependency_sensors(
    *,
    dependencies: tuple[ExternalDagDependency, ...],
    executor_config: dict,
) -> list[ExternalTaskSensor]:
    sensors: list[ExternalTaskSensor] = []

    for dep in dependencies:
        sensor = ExternalTaskSensor(
            task_id=f"wait_for__{dep.dag_id.replace('-', '_')}__{dep.task_id.replace('-', '_')}",
            external_dag_id=dep.dag_id,
            external_task_id=dep.task_id,
            allowed_states=list(dep.allowed_states),
            failed_states=list(dep.failed_states),
            timeout=dep.timeout_seconds,
            poke_interval=dep.poke_interval_seconds,
            mode="reschedule",
            executor_config=executor_config,
        )
        sensors.append(sensor)

    return sensors


def create_systemd_dag(
    *,
    workflow: SystemdWorkflowDefinition,
    dag_id: str,
    action: str,
    schedule: str,
    source_file: str | Path,
    runtime_env_file: str | Path = DEFAULT_RUNTIME_ENV_FILE,
    topology_file: str | Path = DEFAULT_SYSTEMD_TOPOLOGY_FILE,
):
    if action not in {"start", "stop"}:
        raise ValueError("action must be 'start' or 'stop'")

    owner = get_sysid_from_file(source_file)
    runtime_context = build_runtime_context(
        owner=owner,
        config_file=runtime_env_file,
    )
    topology = load_topology_for_current_env(topology_file)
    current_env = get_current_env_name()

    enabled_processes = filter_enabled_processes(workflow.processes, current_env)
    validate_dependency_graph(enabled_processes)

    airflow_fields = build_systemd_airflow_fields(workflow.fields)
    airflow_params = build_airflow_params_from_fields(airflow_fields)
    executor_config = build_executor_config(runtime_context)

    raw_upstream_dependencies = (
        workflow.upstream_dags_for_start if action == "start" else workflow.upstream_dags_for_stop
    )
    upstream_dependencies = filter_enabled_dependencies(raw_upstream_dependencies, current_env)

    @dag_decorator(
        dag_id=dag_id,
        description=workflow.description,
        schedule=schedule,
        tags=list(workflow.tags),
        timezone=runtime_context["timezone"],
        params=airflow_params,
        owner=owner,
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

        validated_task = validate_inputs.override(
            executor_config=executor_config
        )()

        start_node = EmptyOperator(task_id="start", executor_config=executor_config)
        end_node = EmptyOperator(task_id="end", executor_config=executor_config)

        wait_sensors = create_external_dependency_sensors(
            dependencies=upstream_dependencies,
            executor_config=executor_config,
        )

        if wait_sensors:
            for sensor in wait_sensors:
                sensor >> start_node

        task_map: dict[tuple[str, str], MSSSHOperator] = {}
        process_roots: dict[str, list[MSSSHOperator]] = {}

        for process in enabled_processes:
            hosts = resolve_hosts_for_process(topology, process.host_group)
            process_tasks: list[MSSSHOperator] = []

            for host in hosts:
                task_id = f"{process.process_id}__{host.replace('.', '_').replace('-', '_')}"
                command = build_systemd_command(
                    platform=process.platform,
                    service_name=process.service_name,
                    service_user=process.service_user,
                    action=action,
                )

                op = MSSSHOperator(
                    task_id=task_id,
                    ssh_hook=MSSSHHook(
                        remote_host=host,
                        username=runtime_context["kerberos_principal"],
                        enable_kerberos=True,
                    ),
                    command=command,
                    cmd_timeout=workflow.command_timeout_seconds,
                    executor_config=executor_config,
                )

                task_map[(process.process_id, host)] = op
                process_tasks.append(op)

            process_roots[process.process_id] = process_tasks

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
                if not process.start_after:
                    current_tasks >> end_node

        @task(task_id="filter_target_mode")
        def filter_target_mode(validated: dict) -> dict:
            return validated

        filter_result = filter_target_mode.override(
            executor_config=executor_config
        )(validated_task)

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
                    task_id=f"allow__{process.process_id}__{host.replace('.', '_').replace('-', '_')}",
                    executor_config=executor_config,
                )(filter_result, process.process_id)

                allow_task >> original_task

        validated_task >> start_node

    return _dag()
