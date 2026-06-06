from __future__ import annotations

from dataclasses import dataclass, replace
from fnmatch import fnmatchcase
from pathlib import Path
from typing import Any

import re
import shlex

from airflow.exceptions import AirflowSkipException
from airflow.providers.standard.operators.empty import EmptyOperator
from airflow.providers.standard.sensors.external_task import ExternalTaskSensor
from airflow.sdk import get_current_context, task

try:
    from airflow.utils.task_group import TaskGroup
except ImportError:
    from airflow.sdk import TaskGroup  # type: ignore[attr-defined,no-redef]

from common.config_loader import (
    get_current_env_name,
    get_current_loc_name,
    load_json_file,
)
from common.dag_factory import (
    DEFAULT_RUNTIME_ENV_FILE,
    build_minimal_tenant_executor_config,
    build_runtime_context,
    dag_decorator,
)
from common.field_schema import (
    COMMON_FIELDS,
    build_airflow_params_from_fields,
    merge_field_definitions,
    validate_fields,
)
from common.remote_command import (
    build_env_exports,
    build_sudo_bash_command,
    shell_join,
    split_extra_args,
    validate_sudo_mode,
)
from common.ssh_hook import MSSSHHook, execute_ssh_command


ALL_RUNTIME_ENVS = ("dev", "qa", "prod", "dr")
START_ACTION = "start"
STOP_ACTION = "stop"
STATUS_ACTION = "status"
START_STOP_ACTIONS = (START_ACTION, STOP_ACTION)
RUN_ACTION = "run"
SUPPORTED_WORKFLOW_ACTIONS = (*START_STOP_ACTIONS, STATUS_ACTION, RUN_ACTION)
SUPPORTED_SYSTEMD_ACTIONS = (*START_STOP_ACTIONS, STATUS_ACTION)
SYSTEMD_TASK_TYPE = "systemd"
LINUX_SCRIPT_TASK_TYPE = "linux_script"
WINDOWS_SCRIPT_TASK_TYPE = "windows_script"
SCRIPT_TASK_TYPES = {LINUX_SCRIPT_TASK_TYPE, WINDOWS_SCRIPT_TASK_TYPE}
SUPPORTED_TASK_TYPES = {SYSTEMD_TASK_TYPE, *SCRIPT_TASK_TYPES}
REMOTE_COMMAND_ACTIONS = SUPPORTED_WORKFLOW_ACTIONS
SUPPORTED_SYSTEMD_PLATFORMS = {"rhel7", "rhel8"}
SUPPORTED_WINDOWS_SHELLS = {"powershell", "cmd", "raw"}
HOST_GROUP_ONLY_RUNTIME_KEYS = ("remote_env_vars", "windows_shell")
DEPENDENCY_KEYS = ("depends_on", "start_after")
START_DEPENDENCY_KEYS = ("start_depends_on", "start_after", "depends_on")
STOP_DEPENDENCY_KEYS = ("stop_depends_on", "stop_after")
DEFAULT_TOPOLOGY_KEYS = ("base", "default", "defaults")
FLAT_TOPOLOGY_KEYS = ("host_defaults", "host_group_defaults", "host_groups", "variables")
TOPOLOGY_METADATA_KEYS = (
    "host_defaults",
    "host_group_defaults",
    "host_groups",
    "variables",
)
HOST_GROUP_BULK_TASK_KEYS = (
    "service_name",
    "service_names",
    "services",
    "commands",
    "scripts",
    "processes",
)
TASK_TARGET_KEYS = ("target", "target_id", "targets", "target_ids")
RUNTIME_OVERRIDE_KEYS = (
    "task_type",
    "type",
    "commands",
    "fields",
    "working_dir",
    "remote_env_vars",
    "preset_params",
    "sudo_mode",
    "command_timeout_seconds",
    "windows_shell",
)


@dataclass(frozen=True)
class ExternalDagDependency:
    dag_id: str
    task_id: str = "end"
    allowed_states: tuple[str, ...] = ("success",)
    failed_states: tuple[str, ...] = ("failed", "skipped")
    timeout_seconds: int = 4 * 60 * 60
    poke_interval_seconds: int = 60
    enabled_in_envs: tuple[str, ...] = ALL_RUNTIME_ENVS


@dataclass(frozen=True)
class HostTarget:
    host: str
    sudo_user: str | None = None
    ssh_user: str | None = None
    ssh_conn_id: str | None = None
    ssh_username_env_var: str = "SSH_USERNAME"
    ssh_password_env_var: str = "SSH_PASSWORD"
    enable_kerberos: bool | None = None
    task_overrides: dict[str, Any] | None = None


@dataclass(frozen=True)
class WorkflowTaskSpec:
    task_id: str
    task_type: str
    host_group: str
    sudo_user: str | None = None
    ssh_user: str | None = None
    ssh_conn_id: str | None = None
    ssh_username_env_var: str = "SSH_USERNAME"
    ssh_password_env_var: str = "SSH_PASSWORD"
    enable_kerberos: bool | None = None
    platform: str = "rhel8"
    systemd: dict[str, Any] | None = None
    commands: dict[str, Any] | None = None
    fields: dict[str, Any] | None = None
    working_dir: str | None = None
    remote_env_vars: dict[str, Any] | None = None
    preset_params: dict[str, Any] | None = None
    depends_on: tuple[str, ...] = ()
    start_depends_on: tuple[str, ...] = ()
    stop_depends_on: tuple[str, ...] = ()
    enabled_in_envs: tuple[str, ...] = ALL_RUNTIME_ENVS
    optional: bool = False
    enabled: bool = True
    sudo_mode: str = "login"
    command_timeout_seconds: int | None = None
    windows_shell: str = "powershell"
    env_overrides: dict[str, dict[str, Any]] | None = None
    group_id: str | None = None


@dataclass(frozen=True)
class WorkflowTaskGroupSpec:
    group_id: str
    tooltip: str | None = None
    depends_on: tuple[str, ...] = ()
    start_depends_on: tuple[str, ...] = ()
    stop_depends_on: tuple[str, ...] = ()
    enabled_in_envs: tuple[str, ...] = ALL_RUNTIME_ENVS
    optional: bool = False
    enabled: bool = True
    env_overrides: dict[str, dict[str, Any]] | None = None
    tasks: tuple[WorkflowTaskSpec, ...] = ()


@dataclass(frozen=True)
class WorkflowSchedulePair:
    schedule_id: str
    run: str | list[str] | tuple[str, ...] | None = None
    start: str | list[str] | tuple[str, ...] | None = None
    stop: str | list[str] | tuple[str, ...] | None = None
    status: str | list[str] | tuple[str, ...] | None = None
    dag_id_prefix: str | None = None
    run_dag_id: str | None = None
    start_dag_id: str | None = None
    stop_dag_id: str | None = None
    status_dag_id: str | None = None
    enabled_in_envs: tuple[str, ...] = ALL_RUNTIME_ENVS


@dataclass(frozen=True)
class RemoteWorkflowDefinition:
    workflow_id: str
    description: str
    schedule_run: str | list[str] | tuple[str, ...] | None
    schedule_start: str | list[str] | tuple[str, ...] | None
    schedule_stop: str | list[str] | tuple[str, ...] | None
    fields: dict
    tasks: tuple[WorkflowTaskSpec, ...] = ()
    task_groups: tuple[WorkflowTaskGroupSpec, ...] = ()
    upstream_dags_for_run: tuple[ExternalDagDependency, ...] = ()
    upstream_dags_for_start: tuple[ExternalDagDependency, ...] = ()
    upstream_dags_for_stop: tuple[ExternalDagDependency, ...] = ()
    upstream_dags_for_status: tuple[ExternalDagDependency, ...] = ()
    schedule_pairs: tuple[WorkflowSchedulePair, ...] = ()
    actions: tuple[str, ...] = START_STOP_ACTIONS
    target_runtime_envs: tuple[str, ...] = ()
    enabled_in_envs: tuple[str, ...] = ALL_RUNTIME_ENVS
    environments: dict[str, Any] | None = None
    tags: tuple[str, ...] = ("remote-workflow", "ssh")
    run_description: str = ""
    start_description: str = ""
    stop_description: str = ""
    status_description: str = ""
    run_tags: tuple[str, ...] = ()
    start_tags: tuple[str, ...] = ()
    stop_tags: tuple[str, ...] = ()
    status_tags: tuple[str, ...] = ()
    owner: str | None = None
    command_timeout_seconds: int = 1800


@dataclass(frozen=True)
class WorkflowPlan:
    tasks: tuple[WorkflowTaskSpec, ...]
    groups: tuple[WorkflowTaskGroupSpec, ...]
    hosts_by_task_id: dict[str, tuple[HostTarget, ...]]
    run_upstream_task_ids: dict[str, tuple[str, ...]]
    start_upstream_task_ids: dict[str, tuple[str, ...]]
    stop_upstream_task_ids: dict[str, tuple[str, ...]]


def normalize_string_tuple(value: tuple[str, ...] | list[str] | str | None) -> tuple[str, ...]:
    if value is None:
        return ()
    if isinstance(value, str):
        if "," in value:
            return tuple(item.strip() for item in value.split(",") if item.strip())
        stripped = value.strip()
        return (stripped,) if stripped else ()
    return tuple(str(item).strip() for item in value if str(item).strip())


def normalize_schedule_value(value: Any) -> str | tuple[str, ...] | None:
    if value is None:
        return None
    if isinstance(value, str):
        stripped = value.strip()
        return stripped or None
    if isinstance(value, (list, tuple)):
        schedules = tuple(str(item).strip() for item in value if str(item).strip())
        return schedules or None
    raise TypeError("Schedule value must be a string, list of strings, or null.")


def first_config_value(data: dict[str, Any], keys: tuple[str, ...], default: Any = None) -> Any:
    for key in keys:
        if key in data:
            return data[key]
    return default


def require_workflow_action(action: str) -> None:
    if action not in SUPPORTED_WORKFLOW_ACTIONS:
        raise ValueError(f"action must be one of {SUPPORTED_WORKFLOW_ACTIONS}")


def is_start_stop_action(action: str) -> bool:
    return action in START_STOP_ACTIONS


def workflow_action_value(
    action: str,
    *,
    run: Any,
    start: Any,
    stop: Any,
    status: Any = None,
) -> Any:
    require_workflow_action(action)
    if action == RUN_ACTION:
        return run
    if action == START_ACTION:
        return start
    if action == STOP_ACTION:
        return stop
    return status


def dependency_fields_from_config(
    data: dict[str, Any],
    *,
    depends_on_default: tuple[str, ...] = (),
    start_depends_on_default: tuple[str, ...] | None = None,
    stop_depends_on_default: tuple[str, ...] = (),
) -> tuple[tuple[str, ...], tuple[str, ...], tuple[str, ...]]:
    depends_on = normalize_string_tuple(
        first_config_value(data, DEPENDENCY_KEYS, depends_on_default)
    )
    if start_depends_on_default is None:
        start_depends_on_default = depends_on
    start_depends_on = normalize_string_tuple(
        first_config_value(data, START_DEPENDENCY_KEYS, start_depends_on_default)
    )
    stop_depends_on = normalize_string_tuple(
        first_config_value(data, STOP_DEPENDENCY_KEYS, stop_depends_on_default)
    )
    return depends_on, start_depends_on, stop_depends_on


def optional_json_object(value: Any, *, name: str) -> dict[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise TypeError(f"{name} must be a JSON object.")
    return value


def deep_merge_dicts(*items: dict | None) -> dict:
    merged: dict = {}
    for item in items:
        if not item:
            continue
        for key, value in item.items():
            if isinstance(value, dict) and isinstance(merged.get(key), dict):
                merged[key] = deep_merge_dicts(merged[key], value)
            else:
                merged[key] = value
    return merged


def unique_preserving_order(values: list[str] | tuple[str, ...]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(value for value in values if value))


def sanitize_task_id(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_]+", "_", value).strip("_")
    return safe or "target"


def render_template_value(value: Any, tokens: dict[str, str]) -> Any:
    if isinstance(value, str):
        rendered = value
        for key, token_value in tokens.items():
            rendered = rendered.replace(f"{{{key}}}", str(token_value))
        return rendered
    if isinstance(value, list):
        return [render_template_value(item, tokens) for item in value]
    if isinstance(value, tuple):
        return tuple(render_template_value(item, tokens) for item in value)
    if isinstance(value, dict):
        return {
            render_template_value(key, tokens): render_template_value(item, tokens)
            for key, item in value.items()
        }
    return value


def build_env_tokens(topology: dict, current_env: str) -> dict[str, str]:
    tokens = {
        "env": current_env,
        "loc": get_current_loc_name(),
    }
    tokens.update({str(key): str(value) for key, value in topology.get("variables", {}).items()})
    return tokens


def get_default_environment_topology(environments: dict[str, Any]) -> dict[str, Any]:
    default_topology: dict[str, Any] = {}
    for key in DEFAULT_TOPOLOGY_KEYS:
        raw_topology = environments.get(key)
        if raw_topology is None:
            continue
        default_topology = deep_merge_dicts(
            default_topology,
            optional_json_object(
                raw_topology,
                name=f"Workflow environment '{key}' topology",
            ),
        )
    return default_topology


def resolve_topology_for_env(workflow: RemoteWorkflowDefinition, current_env: str) -> dict:
    environments = optional_json_object(workflow.environments, name="Workflow environments")
    default_topology = get_default_environment_topology(environments)
    env_topology = optional_json_object(
        environments.get(current_env),
        name=f"Workflow environment '{current_env}' topology",
    )
    topology = deep_merge_dicts(default_topology, env_topology)
    topology.setdefault("host_groups", {})
    return topology


def extract_host_defaults_from_topology(topology: dict[str, Any]) -> dict[str, Any]:
    defaults = {
        key: value
        for key, value in topology.items()
        if key not in TOPOLOGY_METADATA_KEYS
    }
    for key in ("host_defaults", "host_group_defaults"):
        raw_defaults = topology.get(key)
        if raw_defaults is None:
            continue
        if not isinstance(raw_defaults, dict):
            raise TypeError(f"Topology {key} must be a JSON object.")
        defaults = deep_merge_dicts(defaults, raw_defaults)
    defaults.pop("hosts", None)
    return defaults


def apply_host_group_env_overrides(
    host_group_id: str,
    host_group_config: dict[str, Any],
    current_env: str,
) -> dict[str, Any]:
    env_overrides = host_group_config.get("environments")
    if env_overrides is None:
        return host_group_config
    env_overrides = optional_json_object(
        env_overrides,
        name=f"Host group '{host_group_id}' environments",
    )

    env_config = optional_json_object(
        env_overrides.get(current_env),
        name=f"Host group '{host_group_id}' environment '{current_env}'",
    )

    base_config = {
        key: value
        for key, value in host_group_config.items()
        if key != "environments"
    }
    return deep_merge_dicts(base_config, env_config)


def task_id_from_service_name(service_name: str) -> str:
    base_name = str(service_name).split("/")[-1]
    if base_name.endswith(".service"):
        base_name = base_name[:-len(".service")]
    return sanitize_task_id(base_name)


def bulk_item_task_id(host_group_id: str, item: dict[str, Any]) -> str:
    task_id = item.get("task_id") or item.get("id")
    if task_id:
        return str(task_id)

    service_name = item.get("service_name") or item.get("name")
    if service_name:
        return task_id_from_service_name(str(service_name))

    raise ValueError(
        f"Bulk task in host_group '{host_group_id}' must define task_id."
    )


def host_group_bulk_items(host_group_config: dict[str, Any]) -> tuple[dict[str, Any], ...]:
    items: list[dict[str, Any]] = []

    def append_items(raw_items: Any, *, item_kind: str) -> None:
        if raw_items is None:
            return
        if not isinstance(raw_items, list):
            raise TypeError(f"host_group.{item_kind} must be a list when provided.")
        for raw_item in raw_items:
            if isinstance(raw_item, str):
                if item_kind not in {"service_name", "service_names", "services"}:
                    raise TypeError(f"host_group.{item_kind} entries must be JSON objects.")
                items.append(
                    {
                        "type": SYSTEMD_TASK_TYPE,
                        "service_name": raw_item,
                    }
                )
                continue
            if not isinstance(raw_item, dict):
                raise TypeError(
                    f"host_group.{item_kind} entries must be strings or JSON objects."
                )
            item = dict(raw_item)
            if item_kind in {"service_name", "service_names", "services"}:
                item.setdefault("type", SYSTEMD_TASK_TYPE)
                service_name = item.get("service_name", item.get("name"))
                if service_name:
                    item["service_name"] = service_name
            elif item_kind in {"commands", "scripts"}:
                item.setdefault("type", LINUX_SCRIPT_TASK_TYPE)
            items.append(item)

    for item_kind in HOST_GROUP_BULK_TASK_KEYS:
        raw_items = host_group_config.get(item_kind)
        if item_kind in {"service_name", "commands"} and not isinstance(raw_items, list):
            raw_items = None
        append_items(raw_items, item_kind=item_kind)
    return tuple(items)


def command_map_from_bulk_item(item: dict[str, Any]) -> dict[str, Any] | None:
    if isinstance(item.get("commands"), dict):
        return item["commands"]

    commands = {
        action: item[action]
        for action in REMOTE_COMMAND_ACTIONS
        if action in item
    }
    if "command" in item:
        commands.setdefault(RUN_ACTION, item["command"])
    return commands or None


def task_override_config_from_bulk_item(item: dict[str, Any]) -> dict[str, Any]:
    override_config = {
        key: value
        for key, value in item.items()
        if key not in {"id", "name", "command", *REMOTE_COMMAND_ACTIONS}
        and value is not None
    }
    service_name = item.get("service_name") or item.get("name")
    commands = command_map_from_bulk_item(item)
    if service_name:
        override_config["service_name"] = service_name
    if commands is not None:
        override_config["commands"] = commands
    return override_config


def task_config_from_bulk_item(
    *,
    host_group_id: str,
    item: dict[str, Any],
) -> dict[str, Any]:
    task_type = item.get("task_type", item.get("type"))
    service_name = item.get("service_name") or item.get("name")
    commands = command_map_from_bulk_item(item)

    if task_type is None:
        task_type = SYSTEMD_TASK_TYPE if service_name else LINUX_SCRIPT_TASK_TYPE

    task_id = bulk_item_task_id(host_group_id, item)

    task_config = {
        key: value
        for key, value in item.items()
        if key not in {"id", "name", "command", *REMOTE_COMMAND_ACTIONS}
        and value is not None
    }
    task_config.update(
        {
            "task_id": task_id,
            "host_group": host_group_id,
            "type": task_type,
        }
    )
    if service_name:
        task_config["service_name"] = service_name
    if commands is not None:
        task_config["commands"] = commands
    return task_config


def target_host_group_id(target_id: str) -> str:
    return f"__target_{sanitize_task_id(target_id)}"


def item_list_identity(item: dict[str, Any], *, item_kind: str, index: int) -> str:
    explicit_id = item.get("id") or item.get("task_id")
    if explicit_id:
        return str(explicit_id)
    service_name = item.get("service_name") or item.get("name")
    if service_name:
        return f"service:{service_name}"
    return f"{item_kind}:{index}"


def merge_bulk_items_by_identity(
    base_items: tuple[dict[str, Any], ...],
    override_items: tuple[dict[str, Any], ...],
    *,
    item_kind: str,
) -> tuple[dict[str, Any], ...]:
    merged: dict[str, dict[str, Any]] = {}
    order: list[str] = []
    for index, item in enumerate((*base_items, *override_items)):
        identity = item_list_identity(item, item_kind=item_kind, index=index)
        if identity not in merged:
            order.append(identity)
            merged[identity] = dict(item)
            continue
        merged[identity] = deep_merge_dicts(merged[identity], item)
    return tuple(merged[identity] for identity in order)


def merged_host_group_bulk_items(
    base_config: dict[str, Any],
    override_config: dict[str, Any] | None,
) -> tuple[dict[str, Any], ...]:
    override_config = override_config or {}
    items: list[dict[str, Any]] = []
    for item_kind in HOST_GROUP_BULK_TASK_KEYS:
        base_items = host_group_bulk_items({item_kind: base_config.get(item_kind)})
        override_items = host_group_bulk_items({item_kind: override_config.get(item_kind)})
        items.extend(
            merge_bulk_items_by_identity(
                base_items,
                override_items,
                item_kind=item_kind,
            )
        )
    return tuple(items)


def host_entry_identity(entry: dict[str, Any], *, index: int) -> str:
    explicit_id = entry.get("id") or entry.get("host_id")
    if explicit_id and "{" not in str(explicit_id):
        return str(explicit_id)
    return str(entry.get("host") or entry.get("hostname") or explicit_id or f"host_{index + 1}")


def normalize_host_entry(raw_entry: Any) -> dict[str, Any]:
    if isinstance(raw_entry, str):
        return {"host": raw_entry}
    if not isinstance(raw_entry, dict):
        raise TypeError("host_groups.hosts entries must be strings or JSON objects.")
    return dict(raw_entry)


def expand_host_entries(raw_hosts: Any) -> tuple[dict[str, Any], ...]:
    if raw_hosts is None:
        return ()
    if not isinstance(raw_hosts, list):
        raise TypeError("host_groups.hosts must be a list when provided.")

    entries: list[dict[str, Any]] = []
    for raw_entry in raw_hosts:
        entry = normalize_host_entry(raw_entry)
        nested_hosts = entry.get("hosts")
        if nested_hosts is None:
            entries.append(entry)
            continue
        shared = {
            key: value
            for key, value in entry.items()
            if key != "hosts"
        }
        if not isinstance(nested_hosts, list):
            raise TypeError("nested host entry hosts must be a list.")
        for nested_host in nested_hosts:
            entries.append(deep_merge_dicts(shared, normalize_host_entry(nested_host)))
    return tuple(entries)


def merge_host_entries(
    base_entries: tuple[dict[str, Any], ...],
    override_entries: tuple[dict[str, Any], ...],
) -> tuple[dict[str, Any], ...]:
    merged: dict[str, dict[str, Any]] = {}
    order: list[str] = []
    for index, entry in enumerate((*base_entries, *override_entries)):
        identity = host_entry_identity(entry, index=index)
        if identity not in merged:
            order.append(identity)
            merged[identity] = dict(entry)
            continue
        merged[identity] = deep_merge_dicts(merged[identity], entry)
    return tuple(merged[identity] for identity in order)


def host_group_runtime_defaults(config: dict[str, Any]) -> dict[str, Any]:
    excluded = {
        "hosts",
        "host",
        "hostname",
        "host_id",
        "id",
        "variables",
        "environments",
    }
    defaults: dict[str, Any] = {}
    for key, value in config.items():
        if value is None or key in excluded:
            continue
        if key in HOST_GROUP_BULK_TASK_KEYS:
            if key in {"commands", "service_name"} and not isinstance(value, list):
                defaults[key] = value
            continue
        defaults[key] = value
    return defaults


def host_entry_runtime_defaults(entry: dict[str, Any]) -> dict[str, Any]:
    excluded = {
        "hosts",
        "host",
        "hostname",
        "host_id",
        "id",
        "variables",
    }
    defaults: dict[str, Any] = {}
    for key, value in entry.items():
        if value is None or key in excluded:
            continue
        if key in HOST_GROUP_BULK_TASK_KEYS:
            if key in {"commands", "service_name"} and not isinstance(value, list):
                defaults[key] = value
            continue
        defaults[key] = value
    return defaults


def host_connection_config(entry: dict[str, Any]) -> dict[str, Any]:
    connection_keys = {
        "host",
        "hostname",
        "sudo_user",
        "ssh_user",
        "ssh_conn_id",
        "ssh_username_env_var",
        "ssh_password_env_var",
        "enable_kerberos",
        "enabled",
        "enabled_in_envs",
        "variables",
    }
    return {
        key: value
        for key, value in entry.items()
        if key in connection_keys and value is not None
    }


def tokens_for_inventory_host(
    *,
    current_env: str,
    group_config: dict[str, Any],
    host_entry: dict[str, Any],
) -> dict[str, str]:
    tokens = {"env": current_env, "loc": get_current_loc_name()}
    tokens.update({str(key): str(value) for key, value in group_config.get("variables", {}).items()})
    tokens.update({str(key): str(value) for key, value in host_entry.get("variables", {}).items()})
    host = render_template_value(
        host_entry.get("host") or host_entry.get("hostname") or "",
        tokens,
    )
    tokens.update(
        {
            "host": str(host),
            "hostname": str(host),
        }
    )
    raw_host_id = host_entry.get("host_id") or host_entry.get("id") or sanitize_task_id(str(host))
    host_id = render_template_value(raw_host_id, tokens)
    tokens["host_id"] = sanitize_task_id(str(host_id))
    return tokens


def target_id_from_inventory_item(
    *,
    item: dict[str, Any],
    item_index: int,
    tokens: dict[str, str],
) -> str:
    raw_id = item.get("id") or item.get("task_id")
    if raw_id is None:
        service_name = item.get("service_name") or item.get("name")
        if service_name:
            raw_id = f"{task_id_from_service_name(str(service_name))}_{{host_id}}"
        else:
            raw_id = f"{item.get('type', LINUX_SCRIPT_TASK_TYPE)}_{item_index + 1}_{{host_id}}"
    return sanitize_task_id(str(render_template_value(raw_id, tokens)))


def merge_target_task_config(
    *,
    task_configs_by_id: dict[str, dict[str, Any]],
    target_id: str,
    current_env: str,
    env_task_config: dict[str, Any],
) -> None:
    if target_id not in task_configs_by_id:
        task_configs_by_id[target_id] = {
            "task_id": target_id,
            "host_group": target_host_group_id(target_id),
            "enabled_in_envs": [current_env],
            "env_overrides": {
                current_env: env_task_config,
            },
        }
        return

    task_config = task_configs_by_id[target_id]
    merge_enabled_in_env(task_config, current_env)
    merge_task_env_override(task_config, current_env, env_task_config)


def add_target_host_group_env(
    *,
    environments: dict[str, Any],
    target_id: str,
    current_env: str,
    host_config: dict[str, Any],
) -> None:
    default_topology = environments.setdefault("defaults", {})
    host_groups = default_topology.setdefault("host_groups", {})
    host_group_id = target_host_group_id(target_id)
    target_group = host_groups.setdefault(host_group_id, {"environments": {}})
    env_config = target_group.setdefault("environments", {}).setdefault(
        current_env,
        {"hosts": []},
    )
    env_config.setdefault("hosts", []).append(host_config)


def build_remote_target_task_configs(
    environments: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    task_configs_by_id: dict[str, dict[str, Any]] = {}

    for host_group_id, host_group_config in iter_inventory_host_groups(environments):
        legacy_keys = [key for key in ("task_group", "task_group_id") if key in host_group_config]
        if legacy_keys:
            raise ValueError(
                f"host_group '{host_group_id}' no longer supports {', '.join(legacy_keys)}; "
                "compose targets in tasks or task_groups instead."
            )

        base_group_config = {
            key: value
            for key, value in host_group_config.items()
            if key != "environments"
        }
        env_configs = host_group_environment_configs(
            environments=environments,
            host_group_id=host_group_id,
            host_group_config=host_group_config,
        )
        for current_env, env_group_config in env_configs:
            env_common_runtime = host_group_runtime_defaults(env_group_config)
            env_items_by_identity = {
                item_list_identity(item, item_kind="env", index=index): item
                for index, item in enumerate(host_group_bulk_items(env_group_config))
            }
            group_config = deep_merge_dicts(
                {
                    key: value
                    for key, value in base_group_config.items()
                    if key not in HOST_GROUP_BULK_TASK_KEYS and key != "hosts"
                },
                {
                    key: value
                    for key, value in env_group_config.items()
                    if key not in HOST_GROUP_BULK_TASK_KEYS and key != "hosts"
                },
            )
            group_items = merged_host_group_bulk_items(base_group_config, env_group_config)
            host_entries = merge_host_entries(
                expand_host_entries(base_group_config.get("hosts")),
                expand_host_entries(env_group_config.get("hosts")),
            )

            for host_entry in host_entries:
                tokens = tokens_for_inventory_host(
                    current_env=current_env,
                    group_config=group_config,
                    host_entry=host_entry,
                )
                host_items = merged_host_group_bulk_items({}, host_entry)
                items = merge_bulk_items_by_identity(
                    group_items,
                    host_items,
                    item_kind="host",
                )
                if not items:
                    continue

                host_config = render_template_value(host_connection_config(host_entry), tokens)
                for item_index, item in enumerate(items):
                    item_identity = item_list_identity(
                        item,
                        item_kind="host",
                        index=item_index,
                    )
                    item_config = deep_merge_dicts(
                        host_group_runtime_defaults(group_config),
                        host_entry_runtime_defaults(host_entry),
                        item,
                        env_common_runtime,
                        env_items_by_identity.get(item_identity),
                    )
                    rendered_item = render_template_value(item_config, tokens)
                    target_id = target_id_from_inventory_item(
                        item=rendered_item,
                        item_index=item_index,
                        tokens=tokens,
                    )
                    env_task_config = task_config_from_bulk_item(
                        host_group_id=target_host_group_id(target_id),
                        item=rendered_item,
                    )
                    env_task_config.pop("task_id", None)
                    env_task_config.pop("host_group", None)
                    add_target_host_group_env(
                        environments=environments,
                        target_id=target_id,
                        current_env=current_env,
                        host_config=host_config,
                    )
                    merge_target_task_config(
                        task_configs_by_id=task_configs_by_id,
                        target_id=target_id,
                        current_env=current_env,
                        env_task_config=env_task_config,
                    )

    return task_configs_by_id


def environment_names_from_workflow_environments(
    environments: dict[str, Any],
) -> tuple[str, ...]:
    return tuple(
        env_name
        for env_name, env_config in environments.items()
        if env_name not in DEFAULT_TOPOLOGY_KEYS and isinstance(env_config, dict)
    )


def host_group_environment_configs(
    *,
    environments: dict[str, Any],
    host_group_id: str,
    host_group_config: dict[str, Any],
) -> tuple[tuple[str, dict[str, Any]], ...]:
    configs_by_env: dict[str, dict[str, Any]] = {}

    nested_envs = optional_json_object(
        host_group_config.get("environments"),
        name=f"Host group '{host_group_id}' environments",
    )
    for env_name, env_config in nested_envs.items():
        if isinstance(env_config, dict):
            configs_by_env[env_name] = deep_merge_dicts(
                configs_by_env.get(env_name),
                env_config,
            )

    for env_name in environment_names_from_workflow_environments(environments):
        env_topology = optional_json_object(
            environments.get(env_name),
            name=f"Workflow environment '{env_name}' topology",
        )
        env_host_groups = optional_json_object(
            env_topology.get("host_groups"),
            name=f"Workflow environment '{env_name}' host_groups",
        )
        env_host_group_config = env_host_groups.get(host_group_id)
        if isinstance(env_host_group_config, dict):
            configs_by_env[env_name] = deep_merge_dicts(
                configs_by_env.get(env_name),
                env_host_group_config,
            )

    return tuple(configs_by_env.items())


def merge_enabled_in_env(
    task_config: dict[str, Any],
    env_name: str,
) -> None:
    task_config["enabled_in_envs"] = list(
        unique_preserving_order(
            [
                *normalize_string_tuple(task_config.get("enabled_in_envs")),
                env_name,
            ]
        )
    )


def merge_task_env_override(
    task_config: dict[str, Any],
    env_name: str,
    env_override: dict[str, Any],
) -> None:
    env_overrides = dict(task_config.get("env_overrides") or {})
    env_overrides[env_name] = deep_merge_dicts(
        env_overrides.get(env_name),
        env_override,
    )
    task_config["env_overrides"] = env_overrides


def iter_inventory_host_groups(environments: dict[str, Any]) -> tuple[tuple[str, dict], ...]:
    default_topology = get_default_environment_topology(environments)
    host_groups = optional_json_object(
        default_topology.get("host_groups"),
        name="Workflow default host_groups",
    )
    return tuple(
        (host_group_id, host_group_config)
        for host_group_id, host_group_config in host_groups.items()
        if isinstance(host_group_config, dict)
    )


def reject_host_group_only_runtime_keys(
    config: dict[str, Any],
    *,
    scope: str,
) -> None:
    invalid_keys = [key for key in HOST_GROUP_ONLY_RUNTIME_KEYS if key in config]
    if invalid_keys:
        raise ValueError(
            f"{scope} must define {', '.join(invalid_keys)} in host_groups, "
            "not in tasks or task_groups."
        )

    env_overrides = config.get("env_overrides") or {}
    if not isinstance(env_overrides, dict):
        return
    for env_name, env_config in env_overrides.items():
        if not isinstance(env_config, dict):
            continue
        invalid_env_keys = [
            key for key in HOST_GROUP_ONLY_RUNTIME_KEYS if key in env_config
        ]
        if invalid_env_keys:
            raise ValueError(
                f"{scope} env_overrides.{env_name} must define "
                f"{', '.join(invalid_env_keys)} in host_groups.environments, "
                "not in task or task_group overrides."
            )


def extract_systemd_overrides(config: dict[str, Any]) -> dict[str, Any]:
    systemd_overrides = dict(config.get("systemd") or {})
    for key in ("platform", "service_name"):
        if key in config and not isinstance(config[key], list):
            systemd_overrides[key] = config[key]
    return systemd_overrides


def extract_task_runtime_overrides(config: dict[str, Any]) -> dict[str, Any]:
    overrides: dict[str, Any] = {}
    if not config:
        return overrides

    systemd_overrides = extract_systemd_overrides(config)
    if "platform" in config:
        overrides["platform"] = config["platform"]
    if systemd_overrides:
        overrides["systemd"] = systemd_overrides

    for key in RUNTIME_OVERRIDE_KEYS:
        if key in config:
            if key == "commands" and isinstance(config[key], list):
                continue
            overrides[key] = config[key]

    return overrides


def apply_task_runtime_overrides(
    task_spec: WorkflowTaskSpec,
    overrides: dict[str, Any] | None,
    tokens: dict[str, str] | None = None,
) -> WorkflowTaskSpec:
    if not overrides:
        return task_spec

    rendered = render_template_value(overrides, tokens or {})
    merged_systemd = deep_merge_dicts(task_spec.systemd, rendered.get("systemd"))
    merged_commands = deep_merge_dicts(task_spec.commands, rendered.get("commands"))
    merged_fields = deep_merge_dicts(task_spec.fields, rendered.get("fields"))
    merged_env_vars = deep_merge_dicts(task_spec.remote_env_vars, rendered.get("remote_env_vars"))
    merged_preset_params = deep_merge_dicts(
        task_spec.preset_params,
        rendered.get("preset_params"),
    )

    return replace(
        task_spec,
        task_type=rendered.get("task_type", rendered.get("type", task_spec.task_type)),
        platform=rendered.get("platform", task_spec.platform),
        systemd=merged_systemd or None,
        commands=merged_commands or None,
        fields=merged_fields or None,
        working_dir=rendered.get("working_dir", task_spec.working_dir),
        remote_env_vars=merged_env_vars or None,
        preset_params=merged_preset_params or None,
        sudo_mode=rendered.get("sudo_mode", task_spec.sudo_mode),
        command_timeout_seconds=(
            int(rendered["command_timeout_seconds"])
            if rendered.get("command_timeout_seconds") is not None
            else task_spec.command_timeout_seconds
        ),
        windows_shell=rendered.get("windows_shell", task_spec.windows_shell),
    )


def build_workflow_airflow_fields(
    *,
    extra_fields: dict,
    task_ids: tuple[str, ...],
    task_group_ids: tuple[str, ...],
    include_control_action: bool = True,
) -> dict:
    control_fields = (
        {
            "control_action": {
                "type": "enum",
                "default": "default",
                "values": ["default", "start", "stop", "status"],
                "description": (
                    "default runs this DAG's native action. Use status to check state only."
                ),
                "include_in_cli": False,
            },
        }
        if include_control_action
        else {}
    )
    return merge_field_definitions(
        COMMON_FIELDS,
        control_fields,
        {
            "target_scope": {
                "type": "enum",
                "default": "workflow",
                "values": ["workflow", "task_group", "task"],
                "description": "Run the full workflow, one task group, or one task only.",
                "include_in_cli": False,
            },
            "target_task": {
                "type": "enum",
                "default": "",
                "values": ["", *task_ids],
                "description": "Deprecated single-task selector; use target_tasks.",
                "include_in_cli": False,
            },
            "target_tasks": {
                "type": "multi_enum",
                "default": [],
                "values": list(task_ids),
                "description": (
                    "Select one or more tasks when target_scope is task, or narrow a "
                    "selected task_group to specific tasks."
                ),
                "include_in_cli": False,
            },
            "target_task_group": {
                "type": "enum",
                "default": "",
                "values": ["", *task_group_ids],
                "description": "Deprecated single-task-group selector; use target_task_groups.",
                "include_in_cli": False,
            },
            "target_task_groups": {
                "type": "multi_enum",
                "default": [],
                "values": list(task_group_ids),
                "description": (
                    "Select one or more task groups when target_scope is task_group."
                ),
                "include_in_cli": False,
            },
        },
        extra_fields,
    )


def field_definitions_with_defaults(
    fields: dict[str, Any] | None,
    preset_params: dict[str, Any] | None,
) -> dict[str, Any]:
    resolved_fields = deep_merge_dicts(fields)
    for field_name, default_value in (preset_params or {}).items():
        if field_name in resolved_fields:
            resolved_fields[field_name] = {
                **resolved_fields[field_name],
                "default": default_value,
            }
    return resolved_fields


def build_script_airflow_fields(plan: WorkflowPlan) -> dict[str, Any]:
    fields: dict[str, Any] = {}
    for task_spec in plan.tasks:
        task_hosts = plan.hosts_by_task_id.get(task_spec.task_id, ())
        for host_target in task_hosts:
            effective_task = apply_task_runtime_overrides(
                task_spec,
                host_target.task_overrides,
            )
            if effective_task.task_type not in SCRIPT_TASK_TYPES:
                continue
            fields = merge_field_definitions(
                fields,
                field_definitions_with_defaults(
                    effective_task.fields,
                    effective_task.preset_params,
                ),
            )
    return fields


def merge_params_with_priority(*param_maps: dict[str, Any] | None) -> dict[str, Any]:
    merged: dict[str, Any] = {}
    for item in param_maps:
        if item:
            merged.update(item)
    return merged


def build_args_from_fields(validated: dict, fields: dict) -> list[str]:
    args: list[str] = []

    for field_name, spec in fields.items():
        if spec.get("include_in_cli", True) is False:
            continue

        value = validated.get(field_name)
        if value in (None, "") or (isinstance(value, list) and not value):
            continue

        cli_name = spec.get("cli_name", field_name)
        transform = spec.get("transform")

        if transform == "lower_bool":
            value = str(bool(value)).lower()
        elif isinstance(value, list):
            value = spec.get("cli_joiner", ",").join(str(item) for item in value)

        args.append(f"--{cli_name}={value}")

    return args


def build_env_vars_from_fields(validated: dict, fields: dict) -> dict[str, str]:
    env_vars: dict[str, str] = {}

    for field_name, spec in fields.items():
        if spec.get("export_to_env") is not True:
            continue

        value = validated.get(field_name)
        if value in (None, "") or (isinstance(value, list) and not value):
            continue

        env_name = spec.get("env_name")
        if not env_name:
            continue

        transform = spec.get("env_transform")
        if transform == "lower_bool":
            value = str(bool(value)).lower()
        elif isinstance(value, list):
            value = spec.get("env_joiner", ",").join(str(item) for item in value)

        env_vars[env_name] = str(value)

    return env_vars


def resolve_script_runtime_params(
    *,
    validated_params: dict[str, Any] | None,
    task_spec: WorkflowTaskSpec,
) -> tuple[dict[str, Any], dict[str, Any]]:
    fields = task_spec.fields or {}
    resolved_params = merge_params_with_priority(
        task_spec.preset_params,
        validated_params or {},
    )
    return resolved_params, fields


def resolve_requested_action(validated: dict, dag_action: str) -> str:
    requested = validated.get("control_action") or "default"
    return dag_action if requested == "default" else requested


def allowed_runtime_actions_for_dag(dag_action: str) -> tuple[str, ...]:
    if is_start_stop_action(dag_action):
        return (dag_action, STATUS_ACTION)
    return (dag_action,)


def normalize_target_selection(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    if isinstance(value, str):
        stripped = value.strip()
        return (stripped,) if stripped else ()
    if isinstance(value, (list, tuple)):
        return tuple(str(item).strip() for item in value if str(item).strip())
    return (str(value).strip(),) if str(value).strip() else ()


def selected_target_tasks(validated: dict) -> tuple[str, ...]:
    return unique_preserving_order(
        [
            *normalize_target_selection(validated.get("target_task")),
            *normalize_target_selection(validated.get("target_tasks")),
        ]
    )


def selected_target_task_groups(validated: dict) -> tuple[str, ...]:
    return unique_preserving_order(
        [
            *normalize_target_selection(validated.get("target_task_group")),
            *normalize_target_selection(validated.get("target_task_groups")),
        ]
    )


def target_matches(validated: dict, task_id: str, group_id: str | None) -> bool:
    target_scope = validated["target_scope"]
    if target_scope == "workflow":
        return True
    if target_scope == "task":
        return task_id in selected_target_tasks(validated)
    if target_scope == "task_group":
        selected_task_ids = selected_target_tasks(validated)
        return (
            bool(group_id)
            and group_id in selected_target_task_groups(validated)
            and (not selected_task_ids or task_id in selected_task_ids)
        )
    raise ValueError(f"Unsupported target_scope '{target_scope}'.")


def render_task_templates(task_spec: WorkflowTaskSpec, tokens: dict[str, str]) -> WorkflowTaskSpec:
    return replace(
        task_spec,
        sudo_user=render_template_value(task_spec.sudo_user, tokens),
        ssh_user=render_template_value(task_spec.ssh_user, tokens),
        ssh_conn_id=render_template_value(task_spec.ssh_conn_id, tokens),
        host_group=render_template_value(task_spec.host_group, tokens),
        platform=render_template_value(task_spec.platform, tokens),
        systemd=render_template_value(task_spec.systemd, tokens),
        commands=render_template_value(task_spec.commands, tokens),
        fields=render_template_value(task_spec.fields, tokens),
        working_dir=render_template_value(task_spec.working_dir, tokens),
        remote_env_vars=render_template_value(task_spec.remote_env_vars, tokens),
        preset_params=render_template_value(task_spec.preset_params, tokens),
        depends_on=render_template_value(task_spec.depends_on, tokens),
        start_depends_on=render_template_value(task_spec.start_depends_on, tokens),
        stop_depends_on=render_template_value(task_spec.stop_depends_on, tokens),
        env_overrides=None,
    )


def render_group_templates(
    group_spec: WorkflowTaskGroupSpec,
    tokens: dict[str, str],
) -> WorkflowTaskGroupSpec:
    return replace(
        group_spec,
        tooltip=render_template_value(group_spec.tooltip, tokens),
        depends_on=render_template_value(group_spec.depends_on, tokens),
        start_depends_on=render_template_value(group_spec.start_depends_on, tokens),
        stop_depends_on=render_template_value(group_spec.stop_depends_on, tokens),
        env_overrides=None,
    )


def is_enabled_for_env(
    *,
    enabled: bool,
    enabled_in_envs: tuple[str, ...],
    current_env: str,
) -> bool:
    return enabled and current_env in enabled_in_envs


def apply_task_env_overrides(
    task_spec: WorkflowTaskSpec,
    current_env: str,
    tokens: dict[str, str],
) -> WorkflowTaskSpec:
    override = (task_spec.env_overrides or {}).get(current_env, {})
    if not override:
        return render_task_templates(task_spec, tokens)

    runtime_overrides = extract_task_runtime_overrides(override)
    merged_systemd = deep_merge_dicts(task_spec.systemd, runtime_overrides.get("systemd"))
    merged_commands = deep_merge_dicts(task_spec.commands, runtime_overrides.get("commands"))
    merged_fields = deep_merge_dicts(task_spec.fields, runtime_overrides.get("fields"))
    merged_preset_params = deep_merge_dicts(
        task_spec.preset_params,
        runtime_overrides.get("preset_params"),
    )
    depends_on, start_depends_on, stop_depends_on = dependency_fields_from_config(
        override,
        depends_on_default=task_spec.depends_on,
        start_depends_on_default=task_spec.start_depends_on,
        stop_depends_on_default=task_spec.stop_depends_on,
    )

    resolved = replace(
        task_spec,
        task_type=runtime_overrides.get(
            "task_type",
            runtime_overrides.get("type", task_spec.task_type),
        ),
        host_group=override.get("host_group", task_spec.host_group),
        sudo_user=override.get("sudo_user", task_spec.sudo_user),
        ssh_user=override.get("ssh_user", task_spec.ssh_user),
        ssh_conn_id=override.get("ssh_conn_id", task_spec.ssh_conn_id),
        ssh_username_env_var=override.get("ssh_username_env_var", task_spec.ssh_username_env_var),
        ssh_password_env_var=override.get("ssh_password_env_var", task_spec.ssh_password_env_var),
        enable_kerberos=override.get("enable_kerberos", task_spec.enable_kerberos),
        platform=runtime_overrides.get("platform", task_spec.platform),
        systemd=merged_systemd,
        commands=merged_commands,
        fields=merged_fields or None,
        working_dir=runtime_overrides.get("working_dir", task_spec.working_dir),
        depends_on=depends_on,
        start_depends_on=start_depends_on,
        stop_depends_on=stop_depends_on,
        enabled_in_envs=normalize_string_tuple(
            override.get("enabled_in_envs", task_spec.enabled_in_envs)
        ),
        optional=bool(override.get("optional", task_spec.optional)),
        enabled=bool(override.get("enabled", task_spec.enabled)),
        preset_params=merged_preset_params or None,
        sudo_mode=runtime_overrides.get("sudo_mode", task_spec.sudo_mode),
        command_timeout_seconds=(
            int(runtime_overrides["command_timeout_seconds"])
            if runtime_overrides.get("command_timeout_seconds") is not None
            else task_spec.command_timeout_seconds
        ),
        env_overrides=None,
    )

    return render_task_templates(resolved, tokens)


def apply_group_env_overrides(
    group_spec: WorkflowTaskGroupSpec,
    current_env: str,
    tokens: dict[str, str],
) -> WorkflowTaskGroupSpec:
    override = (group_spec.env_overrides or {}).get(current_env, {})
    depends_on, start_depends_on, stop_depends_on = dependency_fields_from_config(
        override,
        depends_on_default=group_spec.depends_on,
        start_depends_on_default=group_spec.start_depends_on,
        stop_depends_on_default=group_spec.stop_depends_on,
    )
    resolved = replace(
        group_spec,
        tooltip=override.get("tooltip", group_spec.tooltip),
        depends_on=depends_on,
        start_depends_on=start_depends_on,
        stop_depends_on=stop_depends_on,
        enabled_in_envs=normalize_string_tuple(
            override.get("enabled_in_envs", group_spec.enabled_in_envs)
        ),
        optional=bool(override.get("optional", group_spec.optional)),
        enabled=bool(override.get("enabled", group_spec.enabled)),
        env_overrides=None,
    )
    return render_group_templates(resolved, tokens)


def resolve_hosts_for_task(
    *,
    topology: dict,
    task_spec: WorkflowTaskSpec,
    current_env: str,
    tokens: dict[str, str],
) -> tuple[HostTarget, ...]:
    groups = topology.get("host_groups", {})
    if task_spec.host_group not in groups:
        return ()

    host_group_config = groups[task_spec.host_group]
    topology_host_defaults = extract_host_defaults_from_topology(topology)
    host_group_defaults: dict[str, Any] = dict(topology_host_defaults)
    if isinstance(host_group_config, dict):
        host_group_config = apply_host_group_env_overrides(
            task_spec.host_group,
            host_group_config,
            current_env,
        )
        host_entries = host_group_config.get("hosts", [])
        host_group_defaults = deep_merge_dicts(
            topology_host_defaults,
            {
                key: value
                for key, value in host_group_config.items()
                if key != "hosts"
            },
        )
    else:
        host_entries = host_group_config

    if not isinstance(host_entries, list):
        raise TypeError(
            f"Host group '{task_spec.host_group}' must be a list or an object with hosts."
        )

    if not host_entries:
        return ()

    group_tokens = dict(tokens)
    group_tokens.update(
        {
            str(k): str(render_template_value(v, group_tokens))
            for k, v in host_group_defaults.get("variables", {}).items()
        }
    )

    default_sudo_user = render_template_value(
        host_group_defaults.get("sudo_user", task_spec.sudo_user),
        group_tokens,
    )
    default_ssh_user = render_template_value(
        host_group_defaults.get("ssh_user", task_spec.ssh_user),
        group_tokens,
    )
    default_ssh_conn_id = render_template_value(
        host_group_defaults.get("ssh_conn_id", task_spec.ssh_conn_id),
        group_tokens,
    )
    default_ssh_username_env_var = host_group_defaults.get(
        "ssh_username_env_var",
        task_spec.ssh_username_env_var,
    )
    default_ssh_password_env_var = host_group_defaults.get(
        "ssh_password_env_var",
        task_spec.ssh_password_env_var,
    )
    default_enable_kerberos = host_group_defaults.get(
        "enable_kerberos",
        task_spec.enable_kerberos,
    )
    default_task_overrides = render_template_value(
        extract_task_runtime_overrides(host_group_defaults),
        group_tokens,
    )

    targets: list[HostTarget] = []
    for entry in host_entries:
        if isinstance(entry, str):
            targets.append(
                HostTarget(
                    host=render_template_value(entry, group_tokens),
                    sudo_user=default_sudo_user,
                    ssh_user=default_ssh_user,
                    ssh_conn_id=default_ssh_conn_id,
                    ssh_username_env_var=default_ssh_username_env_var,
                    ssh_password_env_var=default_ssh_password_env_var,
                    enable_kerberos=default_enable_kerberos,
                    task_overrides=default_task_overrides,
                )
            )
            continue

        if not isinstance(entry, dict):
            raise TypeError(
                f"Host group '{task_spec.host_group}' entries must be strings or objects."
            )

        if entry.get("enabled") is False:
            continue

        entry_envs = normalize_string_tuple(entry.get("enabled_in_envs", ALL_RUNTIME_ENVS))
        if current_env not in entry_envs:
            continue

        entry_tokens = dict(group_tokens)
        entry_tokens.update({str(k): str(v) for k, v in entry.get("variables", {}).items()})
        host = entry.get("host") or entry.get("hostname")
        if not host:
            raise ValueError(f"Host object in '{task_spec.host_group}' must define host.")
        task_overrides = render_template_value(
            deep_merge_dicts(default_task_overrides, extract_task_runtime_overrides(entry)),
            entry_tokens,
        )

        targets.append(
            HostTarget(
                host=render_template_value(host, entry_tokens),
                sudo_user=render_template_value(
                    entry.get("sudo_user", default_sudo_user),
                    entry_tokens,
                ),
                ssh_user=render_template_value(
                    entry.get("ssh_user", default_ssh_user),
                    entry_tokens,
                ),
                ssh_conn_id=render_template_value(
                    entry.get("ssh_conn_id", default_ssh_conn_id),
                    entry_tokens,
                ),
                ssh_username_env_var=entry.get(
                    "ssh_username_env_var",
                    default_ssh_username_env_var,
                ),
                ssh_password_env_var=entry.get(
                    "ssh_password_env_var",
                    default_ssh_password_env_var,
                ),
                enable_kerberos=entry.get("enable_kerberos", default_enable_kerberos),
                task_overrides=task_overrides,
            )
        )

    return tuple(targets)


def validate_task_spec(
    task_spec: WorkflowTaskSpec,
    *,
    require_runtime_config: bool = True,
    action: str | None = None,
) -> None:
    if task_spec.task_type not in SUPPORTED_TASK_TYPES:
        raise ValueError(
            f"Task '{task_spec.task_id}' has unsupported task_type '{task_spec.task_type}'."
        )

    validate_sudo_mode(task_spec.sudo_mode)

    if task_spec.task_type == SYSTEMD_TASK_TYPE:
        if action and action not in SUPPORTED_SYSTEMD_ACTIONS:
            raise ValueError(
                f"Task '{task_spec.task_id}' has task_type systemd, which does not "
                f"support action '{action}'."
            )
        systemd_cfg = task_spec.systemd or {}
        platform = systemd_cfg.get("platform", task_spec.platform)
        if platform not in SUPPORTED_SYSTEMD_PLATFORMS:
            raise ValueError(
                f"Task '{task_spec.task_id}' systemd platform must be one of "
                f"{sorted(SUPPORTED_SYSTEMD_PLATFORMS)}."
            )
        if require_runtime_config and not systemd_cfg.get("service_name"):
            raise ValueError(f"Task '{task_spec.task_id}' must define systemd.service_name.")

    if task_spec.task_type in SCRIPT_TASK_TYPES:
        commands = task_spec.commands or {}
        if require_runtime_config:
            required_actions = (action,) if action else (*START_STOP_ACTIONS, STATUS_ACTION)
            for action_name in required_actions:
                if action_name not in commands:
                    raise ValueError(
                        f"Task '{task_spec.task_id}' must define commands.{action_name}."
                    )

    if (
        task_spec.task_type == WINDOWS_SCRIPT_TASK_TYPE
        and task_spec.windows_shell not in SUPPORTED_WINDOWS_SHELLS
    ):
        raise ValueError(
            f"Task '{task_spec.task_id}' windows_shell must be one of "
            f"{sorted(SUPPORTED_WINDOWS_SHELLS)}."
        )


def dependency_ids_for_action(
    *,
    depends_on: tuple[str, ...],
    start_depends_on: tuple[str, ...],
    stop_depends_on: tuple[str, ...],
    action: str,
) -> tuple[str, ...]:
    if action == RUN_ACTION:
        return depends_on
    if action == START_ACTION:
        return start_depends_on or depends_on
    if action == STOP_ACTION:
        return stop_depends_on
    if action == STATUS_ACTION:
        return ()
    raise ValueError(f"action must be one of {SUPPORTED_WORKFLOW_ACTIONS}")


def task_dependency_ids(task_spec: WorkflowTaskSpec, action: str) -> tuple[str, ...]:
    return dependency_ids_for_action(
        depends_on=task_spec.depends_on,
        start_depends_on=task_spec.start_depends_on,
        stop_depends_on=task_spec.stop_depends_on,
        action=action,
    )


def group_dependency_ids(group_spec: WorkflowTaskGroupSpec, action: str) -> tuple[str, ...]:
    return dependency_ids_for_action(
        depends_on=group_spec.depends_on,
        start_depends_on=group_spec.start_depends_on,
        stop_depends_on=group_spec.stop_depends_on,
        action=action,
    )


def has_custom_stop_dependency_graph(
    *,
    tasks: tuple[WorkflowTaskSpec, ...],
    groups: tuple[WorkflowTaskGroupSpec, ...],
) -> bool:
    return any(task_spec.stop_depends_on for task_spec in tasks) or any(
        group_spec.stop_depends_on for group_spec in groups
    )


def build_dependency_map(
    *,
    tasks: tuple[WorkflowTaskSpec, ...],
    groups: tuple[WorkflowTaskGroupSpec, ...],
    defined_task_ids: set[str],
    defined_group_ids: set[str],
    action: str,
) -> dict[str, tuple[str, ...]]:
    active_task_ids = {task_spec.task_id for task_spec in tasks}
    active_groups = {group_spec.group_id: group_spec for group_spec in groups}
    tasks_by_group: dict[str, list[str]] = {}
    task_group_lookup: dict[str, str | None] = {}
    direct_internal_deps_by_task: dict[str, set[str]] = {}

    for task_spec in tasks:
        task_group_lookup[task_spec.task_id] = task_spec.group_id
        if task_spec.group_id:
            tasks_by_group.setdefault(task_spec.group_id, []).append(task_spec.task_id)

    for task_spec in tasks:
        direct_internal_deps_by_task[task_spec.task_id] = {
            dep_id
            for dep_id in task_dependency_ids(task_spec, action)
            if dep_id in active_task_ids and task_group_lookup.get(dep_id) == task_spec.group_id
        }

    def validate_dependency_name(dep_id: str) -> None:
        if dep_id in defined_task_ids or dep_id in defined_group_ids:
            return
        raise ValueError(f"Dependency '{dep_id}' is not defined as a task or task_group.")

    def group_leaf_tasks(group_id: str) -> tuple[str, ...]:
        group_task_ids = tasks_by_group.get(group_id, [])
        upstream_ids = set()
        for task_id in group_task_ids:
            upstream_ids.update(direct_internal_deps_by_task.get(task_id, set()))
        return tuple(task_id for task_id in group_task_ids if task_id not in upstream_ids)

    def expand_dependency(dep_id: str) -> tuple[str, ...]:
        validate_dependency_name(dep_id)
        if dep_id in active_task_ids:
            return (dep_id,)
        if dep_id in active_groups:
            return group_leaf_tasks(dep_id)
        return ()

    dependency_map: dict[str, tuple[str, ...]] = {}
    group_lookup = {group_spec.group_id: group_spec for group_spec in groups}

    for task_spec in tasks:
        dep_ids = list(task_dependency_ids(task_spec, action))
        if task_spec.group_id and not direct_internal_deps_by_task[task_spec.task_id]:
            dep_ids.extend(group_dependency_ids(group_lookup[task_spec.group_id], action))

        expanded: list[str] = []
        for dep_id in dep_ids:
            expanded.extend(expand_dependency(dep_id))

        dependency_map[task_spec.task_id] = tuple(
            dep_id
            for dep_id in unique_preserving_order(expanded)
            if dep_id != task_spec.task_id
        )

    validate_acyclic_task_graph(dependency_map)
    return dependency_map


def reverse_dependency_map(start_map: dict[str, tuple[str, ...]]) -> dict[str, tuple[str, ...]]:
    reversed_map = {task_id: [] for task_id in start_map}
    for task_id, upstream_ids in start_map.items():
        for upstream_id in upstream_ids:
            if upstream_id in reversed_map:
                reversed_map[upstream_id].append(task_id)
    return {
        task_id: unique_preserving_order(upstream_ids)
        for task_id, upstream_ids in reversed_map.items()
    }


def validate_acyclic_task_graph(upstream_map: dict[str, tuple[str, ...]]) -> None:
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(task_id: str, path: list[str]) -> None:
        if task_id in visited:
            return
        if task_id in visiting:
            cycle = " -> ".join([*path, task_id])
            raise ValueError(f"Task dependency graph contains a cycle: {cycle}")

        visiting.add(task_id)
        for upstream_id in upstream_map.get(task_id, ()):
            visit(upstream_id, [*path, task_id])
        visiting.remove(task_id)
        visited.add(task_id)

    for task_id in upstream_map:
        visit(task_id, [])


def build_action_dependency_maps(
    *,
    tasks: tuple[WorkflowTaskSpec, ...],
    groups: tuple[WorkflowTaskGroupSpec, ...],
    defined_task_ids: set[str],
    defined_group_ids: set[str],
) -> tuple[
    dict[str, tuple[str, ...]],
    dict[str, tuple[str, ...]],
    dict[str, tuple[str, ...]],
]:
    run_map = build_dependency_map(
        tasks=tasks,
        groups=groups,
        defined_task_ids=defined_task_ids,
        defined_group_ids=defined_group_ids,
        action=RUN_ACTION,
    )
    start_map = build_dependency_map(
        tasks=tasks,
        groups=groups,
        defined_task_ids=defined_task_ids,
        defined_group_ids=defined_group_ids,
        action=START_ACTION,
    )
    stop_map = (
        build_dependency_map(
            tasks=tasks,
            groups=groups,
            defined_task_ids=defined_task_ids,
            defined_group_ids=defined_group_ids,
            action=STOP_ACTION,
        )
        if has_custom_stop_dependency_graph(tasks=tasks, groups=groups)
        else reverse_dependency_map(start_map)
    )
    return run_map, start_map, stop_map


def infer_systemd_scope(platform: str) -> str:
    if platform == "rhel7":
        return "system"
    if platform == "rhel8":
        return "user"
    raise ValueError(f"Unsupported systemd platform '{platform}'.")


def prepare_workflow_plan(
    *,
    workflow: RemoteWorkflowDefinition,
    topology: dict,
    current_env: str,
) -> WorkflowPlan:
    tokens = build_env_tokens(topology, current_env)
    defined_task_ids = {task_spec.task_id for task_spec in workflow.tasks}
    defined_group_ids = {group_spec.group_id for group_spec in workflow.task_groups}

    for group_spec in workflow.task_groups:
        for task_spec in group_spec.tasks:
            if task_spec.task_id in defined_task_ids:
                raise ValueError(f"Duplicate task_id detected: {task_spec.task_id}")
            defined_task_ids.add(task_spec.task_id)

    active_groups: list[WorkflowTaskGroupSpec] = []
    active_tasks: list[WorkflowTaskSpec] = []

    for task_spec in workflow.tasks:
        resolved_task = apply_task_env_overrides(task_spec, current_env, tokens)
        if not is_enabled_for_env(
            enabled=resolved_task.enabled,
            enabled_in_envs=resolved_task.enabled_in_envs,
            current_env=current_env,
        ):
            continue
        validate_task_spec(resolved_task, require_runtime_config=False)
        active_tasks.append(resolved_task)

    for group_spec in workflow.task_groups:
        resolved_group = apply_group_env_overrides(group_spec, current_env, tokens)
        if not is_enabled_for_env(
            enabled=resolved_group.enabled,
            enabled_in_envs=resolved_group.enabled_in_envs,
            current_env=current_env,
        ):
            continue

        group_tasks: list[WorkflowTaskSpec] = []
        for task_spec in group_spec.tasks:
            resolved_task = apply_task_env_overrides(task_spec, current_env, tokens)
            resolved_task = replace(resolved_task, group_id=resolved_group.group_id)
            if not is_enabled_for_env(
                enabled=resolved_task.enabled,
                enabled_in_envs=resolved_task.enabled_in_envs,
                current_env=current_env,
            ):
                continue
            validate_task_spec(resolved_task, require_runtime_config=False)
            group_tasks.append(resolved_task)

        if not group_tasks:
            continue

        active_groups.append(replace(resolved_group, tasks=tuple(group_tasks)))
        active_tasks.extend(group_tasks)

    hosts_by_task_id: dict[str, tuple[HostTarget, ...]] = {}
    task_ids_with_hosts: set[str] = set()
    for task_spec in active_tasks:
        hosts = resolve_hosts_for_task(
            topology=topology,
            task_spec=task_spec,
            current_env=current_env,
            tokens=tokens,
        )
        if hosts:
            hosts_by_task_id[task_spec.task_id] = hosts
            task_ids_with_hosts.add(task_spec.task_id)

    active_tasks = [task_spec for task_spec in active_tasks if task_spec.task_id in task_ids_with_hosts]
    active_group_task_ids = {task_spec.task_id for task_spec in active_tasks}
    active_groups = [
        replace(
            group_spec,
            tasks=tuple(
                task_spec
                for task_spec in group_spec.tasks
                if task_spec.task_id in active_group_task_ids
            ),
        )
        for group_spec in active_groups
        if any(task_spec.task_id in active_group_task_ids for task_spec in group_spec.tasks)
    ]

    active_tasks_tuple = tuple(active_tasks)
    active_groups_tuple = tuple(active_groups)
    run_map, start_map, stop_map = build_action_dependency_maps(
        tasks=active_tasks_tuple,
        groups=active_groups_tuple,
        defined_task_ids=defined_task_ids,
        defined_group_ids=defined_group_ids,
    )

    return WorkflowPlan(
        tasks=active_tasks_tuple,
        groups=active_groups_tuple,
        hosts_by_task_id=hosts_by_task_id,
        run_upstream_task_ids=run_map,
        start_upstream_task_ids=start_map,
        stop_upstream_task_ids=stop_map,
    )


def build_systemd_service_command(
    *,
    task_spec: WorkflowTaskSpec,
    host_target: HostTarget,
    action: str,
) -> str:
    systemd_cfg = task_spec.systemd or {}
    platform = systemd_cfg.get("platform", task_spec.platform)
    scope = infer_systemd_scope(platform)
    service_name = systemd_cfg["service_name"]
    sudo_user = task_spec.sudo_user or host_target.sudo_user

    if platform not in SUPPORTED_SYSTEMD_PLATFORMS:
        raise ValueError(f"Unsupported systemd platform '{platform}'.")

    if action == STATUS_ACTION:
        systemctl_args = [STATUS_ACTION, service_name, "--no-pager"]
    elif action in START_STOP_ACTIONS:
        systemctl_args = [action, service_name]
    else:
        raise ValueError(f"Systemd tasks do not support action '{action}'.")

    command_parts = ["systemctl"]
    if scope == "user":
        command_parts.append("--user")
    command_parts.extend(systemctl_args)

    inner_command = shell_join(command_parts)
    if scope == "system":
        inner_command = shell_join(["sudo", "-n", *command_parts])

    if sudo_user:
        return build_sudo_bash_command(
            sudo_user=sudo_user,
            inner_command=inner_command,
            sudo_mode=task_spec.sudo_mode,
        )

    return inner_command


def command_text_with_args(
    *,
    raw_command: Any,
    app_args: list[str] | None,
    quote_args: bool,
) -> str:
    command_args = app_args or []
    if isinstance(raw_command, list):
        command_parts = [*(str(part) for part in raw_command), *command_args]
        return shell_join(command_parts) if quote_args else " ".join(command_parts)

    joined_args = shell_join(command_args) if quote_args else " ".join(command_args)
    return " ".join(part for part in (str(raw_command), joined_args) if part)


def build_linux_shell_command(
    *,
    raw_command: Any,
    app_args: list[str] | None,
    sudo_user: str | None,
    working_dir: str | None,
    remote_env_vars: dict[str, Any] | None,
    sudo_mode: str,
) -> str:
    command_text = command_text_with_args(
        raw_command=raw_command,
        app_args=app_args,
        quote_args=True,
    )
    prefix_parts: list[str] = []
    if working_dir:
        prefix_parts.append(f"cd {shlex.quote(working_dir)}")

    env_exports = build_env_exports(remote_env_vars or {})
    if env_exports:
        command_text = f"{env_exports} {command_text}"

    prefix_parts.append(command_text)
    inner_command = " && ".join(prefix_parts)

    if sudo_user:
        return build_sudo_bash_command(
            sudo_user=sudo_user,
            inner_command=inner_command,
            sudo_mode=sudo_mode,
        )
    return shell_join(["bash", "-lc", inner_command])


def powershell_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def build_windows_command(
    *,
    raw_command: Any,
    app_args: list[str] | None,
    shell: str,
    working_dir: str | None,
    remote_env_vars: dict[str, Any] | None,
) -> str:
    command_text = command_text_with_args(
        raw_command=raw_command,
        app_args=app_args,
        quote_args=False,
    )

    if shell == "raw":
        return command_text

    if shell == "cmd":
        parts: list[str] = []
        if working_dir:
            parts.append(f'cd /d "{working_dir}"')
        for key, value in (remote_env_vars or {}).items():
            if value is None:
                continue
            parts.append(f"set {key}={value}")
        parts.append(command_text)
        return "cmd.exe /C " + " && ".join(parts)

    if shell != "powershell":
        raise ValueError(f"Unsupported windows_shell '{shell}'.")

    script_parts = ["$ErrorActionPreference = 'Stop'"]
    if working_dir:
        script_parts.append(f"Set-Location {powershell_quote(working_dir)}")
    for key, value in (remote_env_vars or {}).items():
        if value is None:
            continue
        script_parts.append(f"$env:{key} = {powershell_quote(str(value))}")
    script_parts.append(command_text)
    return (
        "powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "
        + powershell_quote("; ".join(script_parts))
    )


def build_script_command_inputs(
    *,
    task_spec: WorkflowTaskSpec,
    validated_params: dict[str, Any] | None,
) -> tuple[list[str], dict[str, Any]]:
    script_params, script_fields = resolve_script_runtime_params(
        validated_params=validated_params,
        task_spec=task_spec,
    )
    app_args = [
        *build_args_from_fields(script_params, script_fields),
        *split_extra_args(script_params.get("extra_args")),
    ]
    remote_env_vars = deep_merge_dicts(
        task_spec.remote_env_vars,
        build_env_vars_from_fields(script_params, script_fields),
    )
    return app_args, remote_env_vars


def build_remote_task_command(
    *,
    task_spec: WorkflowTaskSpec,
    host_target: HostTarget,
    action: str,
    validated_params: dict[str, Any] | None = None,
) -> str:
    if task_spec.task_type == SYSTEMD_TASK_TYPE:
        return build_systemd_service_command(
            task_spec=task_spec,
            host_target=host_target,
            action=action,
        )

    commands = task_spec.commands or {}
    if action not in commands:
        raise ValueError(f"Task '{task_spec.task_id}' has no command for action '{action}'.")

    app_args, remote_env_vars = build_script_command_inputs(
        task_spec=task_spec,
        validated_params=validated_params,
    )
    sudo_user = task_spec.sudo_user or host_target.sudo_user
    if task_spec.task_type == LINUX_SCRIPT_TASK_TYPE:
        return build_linux_shell_command(
            raw_command=commands[action],
            app_args=app_args,
            sudo_user=sudo_user,
            working_dir=task_spec.working_dir,
            remote_env_vars=remote_env_vars,
            sudo_mode=task_spec.sudo_mode,
        )

    if task_spec.task_type == WINDOWS_SCRIPT_TASK_TYPE:
        return build_windows_command(
            raw_command=commands[action],
            app_args=app_args,
            shell=task_spec.windows_shell,
            working_dir=task_spec.working_dir,
            remote_env_vars=remote_env_vars,
        )

    raise ValueError(f"Unsupported task_type '{task_spec.task_type}'.")


def task_spec_to_payload(task_spec: WorkflowTaskSpec) -> dict[str, Any]:
    return {
        "task_id": task_spec.task_id,
        "task_type": task_spec.task_type,
        "sudo_user": task_spec.sudo_user,
        "ssh_user": task_spec.ssh_user,
        "ssh_conn_id": task_spec.ssh_conn_id,
        "ssh_username_env_var": task_spec.ssh_username_env_var,
        "ssh_password_env_var": task_spec.ssh_password_env_var,
        "enable_kerberos": task_spec.enable_kerberos,
        "platform": task_spec.platform,
        "systemd": task_spec.systemd,
        "commands": task_spec.commands,
        "fields": task_spec.fields,
        "working_dir": task_spec.working_dir,
        "remote_env_vars": task_spec.remote_env_vars,
        "preset_params": task_spec.preset_params,
        "sudo_mode": task_spec.sudo_mode,
        "command_timeout_seconds": task_spec.command_timeout_seconds,
        "windows_shell": task_spec.windows_shell,
        "group_id": task_spec.group_id,
    }


def task_spec_from_payload(payload: dict[str, Any]) -> WorkflowTaskSpec:
    return WorkflowTaskSpec(
        task_id=payload["task_id"],
        task_type=payload["task_type"],
        host_group="",
        sudo_user=payload.get("sudo_user"),
        ssh_user=payload.get("ssh_user"),
        ssh_conn_id=payload.get("ssh_conn_id"),
        ssh_username_env_var=payload.get("ssh_username_env_var", "SSH_USERNAME"),
        ssh_password_env_var=payload.get("ssh_password_env_var", "SSH_PASSWORD"),
        enable_kerberos=payload.get("enable_kerberos"),
        platform=payload.get("platform", "rhel8"),
        systemd=payload.get("systemd"),
        commands=payload.get("commands"),
        fields=payload.get("fields"),
        working_dir=payload.get("working_dir"),
        remote_env_vars=payload.get("remote_env_vars"),
        preset_params=payload.get("preset_params"),
        sudo_mode=payload.get("sudo_mode", "login"),
        command_timeout_seconds=payload.get("command_timeout_seconds"),
        windows_shell=payload.get("windows_shell", "powershell"),
        group_id=payload.get("group_id"),
    )


def host_target_to_payload(host_target: HostTarget) -> dict[str, Any]:
    return {
        "host": host_target.host,
        "sudo_user": host_target.sudo_user,
        "ssh_user": host_target.ssh_user,
        "ssh_conn_id": host_target.ssh_conn_id,
        "ssh_username_env_var": host_target.ssh_username_env_var,
        "ssh_password_env_var": host_target.ssh_password_env_var,
        "enable_kerberos": host_target.enable_kerberos,
        "task_overrides": host_target.task_overrides,
    }


def host_target_from_payload(payload: dict[str, Any]) -> HostTarget:
    return HostTarget(
        host=payload["host"],
        sudo_user=payload.get("sudo_user"),
        ssh_user=payload.get("ssh_user"),
        ssh_conn_id=payload.get("ssh_conn_id"),
        ssh_username_env_var=payload.get("ssh_username_env_var", "SSH_USERNAME"),
        ssh_password_env_var=payload.get("ssh_password_env_var", "SSH_PASSWORD"),
        enable_kerberos=payload.get("enable_kerberos"),
        task_overrides=payload.get("task_overrides"),
    )


def get_taskflow_task_id(task_obj) -> str:
    operator = getattr(task_obj, "operator", None)
    if operator is not None:
        return operator.task_id
    return task_obj.task_id


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


def filter_enabled_dependencies(
    dependencies: tuple[ExternalDagDependency, ...],
    current_env: str,
) -> tuple[ExternalDagDependency, ...]:
    return tuple(dep for dep in dependencies if current_env in dep.enabled_in_envs)


def build_action_description(workflow: RemoteWorkflowDefinition, action: str) -> str:
    action_description = workflow_action_value(
        action,
        run=workflow.run_description,
        start=workflow.start_description,
        stop=workflow.stop_description,
        status=workflow.status_description,
    )
    if not action_description:
        return workflow.description
    if not workflow.description:
        return action_description
    return f"{workflow.description.rstrip()}\n\n{action_description.lstrip()}"


def build_action_tags(workflow: RemoteWorkflowDefinition, action: str) -> tuple[str, ...]:
    action_tags = workflow_action_value(
        action,
        run=workflow.run_tags,
        start=workflow.start_tags,
        stop=workflow.stop_tags,
        status=workflow.status_tags,
    )
    return unique_preserving_order((*workflow.tags, *action_tags))


def workflow_upstream_dags_for_action(
    workflow: RemoteWorkflowDefinition,
    action: str,
) -> tuple[ExternalDagDependency, ...]:
    return workflow_action_value(
        action,
        run=workflow.upstream_dags_for_run,
        start=workflow.upstream_dags_for_start,
        stop=workflow.upstream_dags_for_stop,
        status=workflow.upstream_dags_for_status,
    )


def plan_dependency_map_for_action(
    plan: WorkflowPlan,
    action: str,
) -> dict[str, tuple[str, ...]]:
    return workflow_action_value(
        action,
        run=plan.run_upstream_task_ids,
        start=plan.start_upstream_task_ids,
        stop=plan.stop_upstream_task_ids,
        status={task_spec.task_id: () for task_spec in plan.tasks},
    )


def task_ids_for_logical_dependencies(
    *,
    logical_upstream_ids: tuple[str, ...],
    operation_ids_by_task_id: dict[str, tuple[str, ...]],
) -> tuple[str, ...]:
    task_ids: list[str] = []
    for upstream_id in logical_upstream_ids:
        task_ids.extend(operation_ids_by_task_id.get(upstream_id, ()))
    return unique_preserving_order(task_ids)


def create_workflow_dag(
    *,
    workflow: RemoteWorkflowDefinition,
    dag_id: str,
    action: str,
    schedule: str | list[str] | tuple[str, ...] | None,
    target_env: str | None = None,
    source_file: str | Path | None = None,
    runtime_env_file: str | Path = DEFAULT_RUNTIME_ENV_FILE,
):
    require_workflow_action(action)

    runtime_context = build_runtime_context(
        owner=workflow.owner or workflow.workflow_id,
        config_file=runtime_env_file,
    )
    current_env = target_env or get_current_env_name()
    topology = resolve_topology_for_env(workflow, current_env)
    plan = prepare_workflow_plan(
        workflow=workflow,
        topology=topology,
        current_env=current_env,
    )

    task_ids = tuple(task_spec.task_id for task_spec in plan.tasks)
    group_ids = tuple(group_spec.group_id for group_spec in plan.groups)
    extra_fields = merge_field_definitions(
        workflow.fields,
        build_script_airflow_fields(plan),
    )
    airflow_fields = build_workflow_airflow_fields(
        extra_fields=extra_fields,
        task_ids=task_ids,
        task_group_ids=group_ids,
        include_control_action=is_start_stop_action(action),
    )
    airflow_params = build_airflow_params_from_fields(airflow_fields)
    executor_config = build_minimal_tenant_executor_config(runtime_context)

    raw_upstream_dependencies = workflow_upstream_dags_for_action(workflow, action)
    upstream_dependencies = filter_enabled_dependencies(raw_upstream_dependencies, current_env)

    @dag_decorator(
        dag_id=dag_id,
        description=build_action_description(workflow, action),
        schedule=schedule,
        tags=list(build_action_tags(workflow, action)),
        timezone=runtime_context["timezone"],
        params=airflow_params,
        owner=runtime_context["owner"],
    )
    def _dag():
        @task(task_id="validate_inputs")
        def validate_inputs() -> dict:
            context = get_current_context()
            raw_params = dict(context["params"])
            validated = validate_fields(raw_params, airflow_fields)
            selected_action = resolve_requested_action(validated, action)

            allowed_runtime_actions = allowed_runtime_actions_for_dag(action)
            if selected_action not in allowed_runtime_actions:
                raise ValueError(
                    f"DAG '{dag_id}' supports actions {allowed_runtime_actions}. "
                    f"Use the {selected_action} DAG for '{selected_action}'."
                )

            if validated["target_scope"] == "task" and not selected_target_tasks(validated):
                raise ValueError(
                    "target_tasks is required when target_scope=task. "
                    "target_task is still accepted for backward compatibility."
                )

            if (
                validated["target_scope"] == "task_group"
                and not selected_target_task_groups(validated)
            ):
                raise ValueError(
                    "target_task_groups is required when target_scope=task_group. "
                    "target_task_group is still accepted for backward compatibility."
                )

            return validated

        @task(task_id="allow_external_dependencies")
        def allow_external_dependencies(validated: dict) -> str:
            selected_action = resolve_requested_action(validated, action)
            if validated["target_scope"] == "workflow" and selected_action != STATUS_ACTION:
                return "run"
            raise AirflowSkipException(
                "External DAG dependencies are skipped for individual targets and status checks."
            )

        @task(task_id="gate_remote_task", trigger_rule="all_done")
        def gate_remote_task(
            validated: dict,
            task_id: str,
            group_id: str | None,
            workflow_upstream_task_ids: list[str],
            group_upstream_task_ids: list[str],
            dag_action: str,
        ) -> str:
            if not target_matches(validated, task_id, group_id):
                raise AirflowSkipException(
                    f"Skipping '{task_id}' because it is outside the selected target."
                )

            selected_action = resolve_requested_action(validated, dag_action)
            if selected_action == STATUS_ACTION:
                return "run"

            target_scope = validated["target_scope"]
            if target_scope == "workflow":
                required_upstream_ids = workflow_upstream_task_ids
            elif target_scope == "task_group":
                if selected_target_tasks(validated):
                    required_upstream_ids = []
                else:
                    required_upstream_ids = group_upstream_task_ids
            else:
                required_upstream_ids = []

            if not required_upstream_ids:
                return "run"

            context = get_current_context()
            dag_run = context["dag_run"]
            not_success: list[str] = []
            for upstream_task_id in required_upstream_ids:
                task_instance = dag_run.get_task_instance(upstream_task_id)
                state = getattr(task_instance, "state", None)
                if state != "success":
                    not_success.append(f"{upstream_task_id}={state or 'missing'}")

            if not_success:
                raise AirflowSkipException(
                    f"Upstream dependencies for '{task_id}' are not successful: "
                    f"{', '.join(not_success)}"
                )

            return "run"

        @task(task_id="run_remote_task")
        def run_remote_task(
            validated: dict,
            task_payload: dict,
            host_payload: dict,
            dag_action: str,
        ) -> str:
            selected_action = resolve_requested_action(validated, dag_action)
            host_target = host_target_from_payload(host_payload)
            task_spec = apply_task_runtime_overrides(
                task_spec_from_payload(task_payload),
                host_target.task_overrides,
            )
            validate_task_spec(task_spec, action=selected_action)
            command = build_remote_task_command(
                task_spec=task_spec,
                host_target=host_target,
                action=selected_action,
                validated_params=validated,
            )
            cmd_timeout = (
                task_spec.command_timeout_seconds
                or workflow.command_timeout_seconds
            )
            ssh_hook = MSSSHHook(
                ssh_conn_id=host_target.ssh_conn_id,
                remote_host=host_target.host,
                username=host_target.ssh_user,
                env_username_var=host_target.ssh_username_env_var,
                env_password_var=host_target.ssh_password_env_var,
                enable_kerberos=host_target.enable_kerberos,
            )
            return execute_ssh_command(
                task_id=task_spec.task_id,
                ssh_hook=ssh_hook,
                command=command,
                cmd_timeout=cmd_timeout,
            )

        validated_task = validate_inputs.override(
            executor_config=executor_config
        )()
        start_node = EmptyOperator(
            task_id="start",
            trigger_rule="none_failed_min_one_success",
            executor_config=executor_config,
        )
        end_node = EmptyOperator(
            task_id="end",
            trigger_rule="none_failed_min_one_success",
            executor_config=executor_config,
        )

        wait_sensors = create_external_dependency_sensors(
            dependencies=upstream_dependencies,
            executor_config=executor_config,
        )
        if wait_sensors:
            external_gate = allow_external_dependencies.override(
                executor_config=executor_config
            )(validated_task)
            validated_task >> start_node
            for sensor in wait_sensors:
                external_gate >> sensor >> start_node
        else:
            validated_task >> start_node

        group_contexts: dict[str, TaskGroup] = {
            group_spec.group_id: TaskGroup(
                group_id=sanitize_task_id(group_spec.group_id),
                tooltip=group_spec.tooltip,
            )
            for group_spec in plan.groups
        }

        task_lookup = {task_spec.task_id: task_spec for task_spec in plan.tasks}
        operation_refs_by_task_id: dict[str, list] = {task_id: [] for task_id in task_lookup}
        operation_ids_by_task_id: dict[str, list[str]] = {task_id: [] for task_id in task_lookup}

        def create_operation(task_spec: WorkflowTaskSpec, host_target: HostTarget):
            host_id = sanitize_task_id(host_target.host)
            task_id = f"run__{sanitize_task_id(task_spec.task_id)}__{host_id}"
            effective_task_spec = apply_task_runtime_overrides(
                task_spec,
                host_target.task_overrides,
            )
            validation_action = (
                action
                if action in (RUN_ACTION, STATUS_ACTION)
                else None
            )
            validate_task_spec(
                effective_task_spec,
                action=validation_action,
            )
            return run_remote_task.override(
                task_id=task_id,
                executor_config=executor_config,
            )(
                validated_task,
                task_spec_to_payload(task_spec),
                host_target_to_payload(host_target),
                action,
            )

        for task_spec in plan.tasks:
            task_context = group_contexts.get(task_spec.group_id or "")
            hosts = plan.hosts_by_task_id[task_spec.task_id]
            if task_context:
                with task_context:
                    for host_target in hosts:
                        operation = create_operation(task_spec, host_target)
                        operation_refs_by_task_id[task_spec.task_id].append(operation)
                        operation_ids_by_task_id[task_spec.task_id].append(
                            get_taskflow_task_id(operation)
                        )
            else:
                for host_target in hosts:
                    operation = create_operation(task_spec, host_target)
                    operation_refs_by_task_id[task_spec.task_id].append(operation)
                    operation_ids_by_task_id[task_spec.task_id].append(
                        get_taskflow_task_id(operation)
                    )

        dependency_map = plan_dependency_map_for_action(plan, action)
        operation_ids_by_task_id_tuple = {
            task_id: tuple(task_ids)
            for task_id, task_ids in operation_ids_by_task_id.items()
        }
        operation_ref_by_taskflow_id = {
            get_taskflow_task_id(ref): ref
            for refs in operation_refs_by_task_id.values()
            for ref in refs
        }

        def create_gate(
            task_spec: WorkflowTaskSpec,
            host_target: HostTarget,
            operation_ref,
            workflow_upstream_ids: tuple[str, ...],
            group_upstream_ids: tuple[str, ...],
        ):
            host_id = sanitize_task_id(host_target.host)
            gate_task = gate_remote_task.override(
                task_id=f"gate__{sanitize_task_id(task_spec.task_id)}__{host_id}",
                executor_config=executor_config,
            )(
                validated_task,
                task_spec.task_id,
                task_spec.group_id,
                list(workflow_upstream_ids),
                list(group_upstream_ids),
                action,
            )

            start_node >> gate_task
            for upstream_task_id in workflow_upstream_ids:
                upstream_ref = operation_ref_by_taskflow_id.get(upstream_task_id)
                if upstream_ref is not None:
                    upstream_ref >> gate_task

            gate_task >> operation_ref >> end_node

        for task_spec in plan.tasks:
            task_context = group_contexts.get(task_spec.group_id or "")
            logical_upstream_ids = dependency_map.get(task_spec.task_id, ())
            workflow_upstream_ids = task_ids_for_logical_dependencies(
                logical_upstream_ids=logical_upstream_ids,
                operation_ids_by_task_id=operation_ids_by_task_id_tuple,
            )
            if not workflow_upstream_ids:
                workflow_upstream_ids = (start_node.task_id,)

            group_logical_upstream_ids = tuple(
                upstream_id
                for upstream_id in logical_upstream_ids
                if task_lookup.get(upstream_id)
                and task_lookup[upstream_id].group_id == task_spec.group_id
            )
            group_upstream_ids = task_ids_for_logical_dependencies(
                logical_upstream_ids=group_logical_upstream_ids,
                operation_ids_by_task_id=operation_ids_by_task_id_tuple,
            )

            hosts = plan.hosts_by_task_id[task_spec.task_id]
            operations = operation_refs_by_task_id[task_spec.task_id]
            if task_context:
                with task_context:
                    for host_target, operation_ref in zip(hosts, operations):
                        create_gate(
                            task_spec,
                            host_target,
                            operation_ref,
                            workflow_upstream_ids,
                            group_upstream_ids,
                        )
            else:
                for host_target, operation_ref in zip(hosts, operations):
                    create_gate(
                        task_spec,
                        host_target,
                        operation_ref,
                        workflow_upstream_ids,
                        group_upstream_ids,
                    )

        if not plan.tasks:
            start_node >> end_node

    return _dag()


def build_external_dependency_from_config(data: dict) -> ExternalDagDependency:
    return ExternalDagDependency(
        dag_id=data["dag_id"],
        task_id=data.get("task_id", "end"),
        allowed_states=normalize_string_tuple(data.get("allowed_states", ("success",))),
        failed_states=normalize_string_tuple(data.get("failed_states", ("failed", "skipped"))),
        timeout_seconds=int(data.get("timeout_seconds", 4 * 60 * 60)),
        poke_interval_seconds=int(data.get("poke_interval_seconds", 60)),
        enabled_in_envs=normalize_string_tuple(data.get("enabled_in_envs", ALL_RUNTIME_ENVS)),
    )


def build_task_spec_from_config(
    data: dict,
    *,
    group_id: str | None = None,
    allow_host_group_runtime_keys: bool = False,
) -> WorkflowTaskSpec:
    if not allow_host_group_runtime_keys:
        reject_host_group_only_runtime_keys(
            data,
            scope=f"Task '{data.get('task_id', '<unknown>')}'",
        )
    task_type = data.get("task_type", data.get("type", SYSTEMD_TASK_TYPE))
    depends_on, start_depends_on, stop_depends_on = dependency_fields_from_config(data)
    return WorkflowTaskSpec(
        task_id=data["task_id"],
        task_type=task_type,
        host_group=data["host_group"],
        sudo_user=data.get("sudo_user"),
        ssh_user=data.get("ssh_user"),
        ssh_conn_id=data.get("ssh_conn_id"),
        ssh_username_env_var=data.get("ssh_username_env_var", "SSH_USERNAME"),
        ssh_password_env_var=data.get("ssh_password_env_var", "SSH_PASSWORD"),
        enable_kerberos=data.get("enable_kerberos"),
        platform=data.get("platform", "rhel8"),
        systemd=extract_systemd_overrides(data) or None,
        commands=data.get("commands"),
        fields=data.get("fields"),
        working_dir=data.get("working_dir"),
        preset_params=data.get("preset_params"),
        depends_on=depends_on,
        start_depends_on=start_depends_on,
        stop_depends_on=stop_depends_on,
        enabled_in_envs=normalize_string_tuple(data.get("enabled_in_envs", ALL_RUNTIME_ENVS)),
        optional=bool(data.get("optional", False)),
        enabled=bool(data.get("enabled", True)),
        sudo_mode=data.get("sudo_mode", "login"),
        command_timeout_seconds=(
            int(data["command_timeout_seconds"])
            if data.get("command_timeout_seconds") is not None
            else None
        ),
        env_overrides=data.get("env_overrides"),
        group_id=group_id,
    )


def task_target_selectors_from_config(data: dict[str, Any]) -> tuple[str, ...]:
    raw_targets = first_config_value(data, TASK_TARGET_KEYS)
    return normalize_string_tuple(raw_targets)


def resolve_task_target_ids(
    selectors: tuple[str, ...],
    target_task_configs: dict[str, dict[str, Any]],
) -> tuple[str, ...]:
    resolved: list[str] = []
    target_ids = tuple(target_task_configs)
    for selector in selectors:
        if any(char in selector for char in "*?["):
            matches = [target_id for target_id in target_ids if fnmatchcase(target_id, selector)]
            if not matches:
                raise ValueError(f"target selector '{selector}' did not match any host target.")
            resolved.extend(matches)
            continue
        if selector not in target_task_configs:
            raise ValueError(f"target '{selector}' is not defined in host_groups.")
        resolved.append(selector)
    return unique_preserving_order(resolved)


def task_id_for_target_reference(
    *,
    raw_task_id: str | None,
    target_id: str,
    multiple_targets: bool,
) -> str:
    if not raw_task_id:
        return target_id
    if "{target_id}" in raw_task_id or "{target}" in raw_task_id:
        return str(
            render_template_value(
                raw_task_id,
                {
                    "target_id": target_id,
                    "target": target_id,
                },
            )
        )
    if multiple_targets:
        return f"{raw_task_id}_{target_id}"
    return raw_task_id


def expand_task_specs_from_config(
    data: dict[str, Any],
    *,
    target_task_configs: dict[str, dict[str, Any]],
    group_id: str | None = None,
) -> tuple[WorkflowTaskSpec, ...]:
    selectors = task_target_selectors_from_config(data)
    if not selectors:
        return (build_task_spec_from_config(data, group_id=group_id),)

    target_ids = resolve_task_target_ids(selectors, target_task_configs)
    overlay_config = {
        key: value
        for key, value in data.items()
        if key not in TASK_TARGET_KEYS and key != "host_group"
    }
    task_specs: list[WorkflowTaskSpec] = []
    multiple_targets = len(target_ids) > 1
    for target_id in target_ids:
        task_config = deep_merge_dicts(target_task_configs[target_id], overlay_config)
        task_config["task_id"] = task_id_for_target_reference(
            raw_task_id=overlay_config.get("task_id"),
            target_id=target_id,
            multiple_targets=multiple_targets,
        )
        task_specs.append(
            build_task_spec_from_config(
                task_config,
                group_id=group_id,
                allow_host_group_runtime_keys=True,
            )
        )
    return tuple(task_specs)


def build_task_specs_from_config(
    raw_tasks: list[dict[str, Any]],
    *,
    target_task_configs: dict[str, dict[str, Any]],
    group_id: str | None = None,
) -> tuple[WorkflowTaskSpec, ...]:
    specs: list[WorkflowTaskSpec] = []
    for task_data in raw_tasks:
        specs.extend(
            expand_task_specs_from_config(
                task_data,
                target_task_configs=target_task_configs,
                group_id=group_id,
            )
        )
    return tuple(specs)


def build_task_group_spec_from_config(
    data: dict,
    *,
    target_task_configs: dict[str, dict[str, Any]] | None = None,
) -> WorkflowTaskGroupSpec:
    reject_host_group_only_runtime_keys(
        data,
        scope=f"Task group '{data.get('group_id', '<unknown>')}'",
    )
    target_task_configs = target_task_configs or {}
    group_id = data["group_id"]
    depends_on, start_depends_on, stop_depends_on = dependency_fields_from_config(data)
    return WorkflowTaskGroupSpec(
        group_id=group_id,
        tooltip=data.get("tooltip"),
        depends_on=depends_on,
        start_depends_on=start_depends_on,
        stop_depends_on=stop_depends_on,
        enabled_in_envs=normalize_string_tuple(data.get("enabled_in_envs", ALL_RUNTIME_ENVS)),
        optional=bool(data.get("optional", False)),
        enabled=bool(data.get("enabled", True)),
        env_overrides=data.get("env_overrides"),
        tasks=build_task_specs_from_config(
            data.get("tasks", []),
            target_task_configs=target_task_configs,
            group_id=group_id,
        ),
    )


def build_schedule_pair_from_config(data: dict, *, schedule_id: str) -> WorkflowSchedulePair:
    dag_ids = data.get("dag_ids", {})
    return WorkflowSchedulePair(
        schedule_id=schedule_id,
        run=normalize_schedule_value(data.get("run", data.get("schedule_run"))),
        start=normalize_schedule_value(data.get(START_ACTION, data.get("schedule_start"))),
        stop=normalize_schedule_value(data.get(STOP_ACTION, data.get("schedule_stop"))),
        status=normalize_schedule_value(data.get(STATUS_ACTION, data.get("schedule_status"))),
        dag_id_prefix=data.get("dag_id_prefix"),
        run_dag_id=data.get("run_dag_id") or dag_ids.get("run"),
        start_dag_id=data.get("start_dag_id") or dag_ids.get(START_ACTION),
        stop_dag_id=data.get("stop_dag_id") or dag_ids.get(STOP_ACTION),
        status_dag_id=data.get("status_dag_id") or dag_ids.get(STATUS_ACTION),
        enabled_in_envs=normalize_string_tuple(data.get("enabled_in_envs", ALL_RUNTIME_ENVS)),
    )


def normalize_schedules_config(data: dict) -> dict[str, Any]:
    schedules = data.get("schedules", {})
    if schedules is None:
        return {}
    if isinstance(schedules, str):
        return {RUN_ACTION: schedules}
    if not isinstance(schedules, dict):
        raise TypeError("schedules must be a JSON object, string, or null.")
    return schedules


def build_schedule_pairs_from_config(data: dict) -> tuple[WorkflowSchedulePair, ...]:
    schedules = normalize_schedules_config(data)
    raw_pairs = data.get("schedule_pairs") or schedules.get("pairs")

    if raw_pairs:
        if not isinstance(raw_pairs, list):
            raise TypeError("schedule_pairs must be a list of schedule pair objects.")
        return tuple(
            build_schedule_pair_from_config(
                pair_data,
                schedule_id="default" if len(raw_pairs) == 1 else f"schedule_{index + 1}",
            )
            for index, pair_data in enumerate(raw_pairs)
        )

    return (
        WorkflowSchedulePair(
            schedule_id="default",
            run=normalize_schedule_value(
                schedules.get(RUN_ACTION, data.get("schedule", data.get("schedule_run")))
            ),
            start=normalize_schedule_value(
                schedules.get(START_ACTION, data.get("schedule_start"))
            ),
            stop=normalize_schedule_value(
                schedules.get(STOP_ACTION, data.get("schedule_stop"))
            ),
            status=normalize_schedule_value(
                schedules.get(STATUS_ACTION, data.get("schedule_status"))
            ),
        ),
    )


def iter_runtime_config_blocks(data: dict) -> tuple[dict[str, Any], ...]:
    blocks: list[dict[str, Any]] = []
    host_groups = data.get("host_groups") or {}
    if isinstance(host_groups, dict):
        for value in host_groups.values():
            if not isinstance(value, dict):
                continue
            blocks.append(value)
            blocks.extend(host_group_bulk_items(value))
            for host_entry in expand_host_entries(value.get("hosts")):
                blocks.append(host_entry)
                blocks.extend(host_group_bulk_items(host_entry))
                blocks.extend(host_group_bulk_items(value))
            for env_config in optional_json_object(
                value.get("environments"),
                name="host_group.environments",
            ).values():
                if not isinstance(env_config, dict):
                    continue
                blocks.append(env_config)
                blocks.extend(host_group_bulk_items(env_config))
                for host_entry in expand_host_entries(env_config.get("hosts")):
                    blocks.append(host_entry)
                    blocks.extend(host_group_bulk_items(host_entry))
                    blocks.extend(host_group_bulk_items(env_config))
    blocks.extend(
        task_data for task_data in data.get("tasks", []) if isinstance(task_data, dict)
    )
    for group_data in data.get("task_groups", []):
        if not isinstance(group_data, dict):
            continue
        blocks.extend(
            task_data
            for task_data in group_data.get("tasks", [])
            if isinstance(task_data, dict)
        )
    return tuple(blocks)


def config_uses_start_stop_action(
    data: dict,
    schedule_pairs: tuple[WorkflowSchedulePair, ...],
) -> bool:
    schedules = normalize_schedules_config(data)
    upstream_dags = data.get("upstream_dags") or {}
    dag_metadata = data.get("dag_metadata") or data.get("dag_overrides") or {}
    return (
        data.get("schedule_start") is not None
        or data.get("schedule_stop") is not None
        or START_ACTION in schedules
        or STOP_ACTION in schedules
        or any(pair.start is not None or pair.stop is not None for pair in schedule_pairs)
        or data.get("upstream_dags_for_start") is not None
        or data.get("upstream_dags_for_stop") is not None
        or (
            isinstance(upstream_dags, dict)
            and (START_ACTION in upstream_dags or STOP_ACTION in upstream_dags)
        )
        or (
            isinstance(dag_metadata, dict)
            and (START_ACTION in dag_metadata or STOP_ACTION in dag_metadata)
        )
    )


def config_uses_status_action(
    data: dict,
    schedule_pairs: tuple[WorkflowSchedulePair, ...],
) -> bool:
    schedules = normalize_schedules_config(data)
    upstream_dags = data.get("upstream_dags") or {}
    dag_metadata = data.get("dag_metadata") or data.get("dag_overrides") or {}
    return (
        data.get("schedule_status") is not None
        or STATUS_ACTION in schedules
        or any(pair.status is not None for pair in schedule_pairs)
        or data.get("upstream_dags_for_status") is not None
        or (
            isinstance(upstream_dags, dict)
            and STATUS_ACTION in upstream_dags
        )
        or (
            isinstance(dag_metadata, dict)
            and STATUS_ACTION in dag_metadata
        )
    )


def config_uses_run_action(data: dict, schedule_pairs: tuple[WorkflowSchedulePair, ...]) -> bool:
    schedules = normalize_schedules_config(data)
    upstream_dags = data.get("upstream_dags", {})
    dag_metadata = data.get("dag_metadata") or data.get("dag_overrides") or {}
    if (
        data.get("schedule") is not None
        or data.get("schedule_run") is not None
        or RUN_ACTION in schedules
        or any(pair.run is not None for pair in schedule_pairs)
        or data.get("upstream_dags_for_run") is not None
        or (isinstance(upstream_dags, dict) and RUN_ACTION in upstream_dags)
        or isinstance(upstream_dags, list)
        or (isinstance(dag_metadata, dict) and RUN_ACTION in dag_metadata)
    ):
        return True

    for block in iter_runtime_config_blocks(data):
        commands = block.get("commands")
        if isinstance(commands, dict) and RUN_ACTION in commands:
            return True
    return False


def normalize_workflow_actions(
    data: dict,
    schedule_pairs: tuple[WorkflowSchedulePair, ...],
) -> tuple[str, ...]:
    raw_actions = data.get("actions", data.get("workflow_actions"))
    if raw_actions is None:
        uses_start_stop = config_uses_start_stop_action(data, schedule_pairs)
        uses_status = config_uses_status_action(data, schedule_pairs)
        if (
            config_uses_run_action(data, schedule_pairs)
            and not uses_start_stop
            and not uses_status
        ):
            return (RUN_ACTION,)
        if uses_start_stop:
            return (*START_STOP_ACTIONS, STATUS_ACTION)
        if uses_status:
            return (STATUS_ACTION,)
        return (*START_STOP_ACTIONS, STATUS_ACTION)

    actions = normalize_string_tuple(raw_actions)
    if not actions:
        raise ValueError("actions must define at least one workflow action.")

    unsupported_actions = [
        action for action in actions if action not in SUPPORTED_WORKFLOW_ACTIONS
    ]
    if unsupported_actions:
        raise ValueError(
            f"actions contains unsupported values {unsupported_actions}; "
            f"supported actions are {SUPPORTED_WORKFLOW_ACTIONS}."
        )
    return unique_preserving_order(actions)


def configured_target_runtime_envs(data: dict) -> tuple[str, ...]:
    raw_envs = first_config_value(
        data,
        ("runtime_envs", "target_runtime_envs", "dag_runtime_envs"),
    )
    if raw_envs is None:
        return ()
    envs = normalize_string_tuple(raw_envs)
    if not envs:
        raise ValueError("runtime_envs must define at least one environment when provided.")
    return envs


def build_workflow_environments_from_config(data: dict) -> dict[str, Any]:
    environments = optional_json_object(
        data.get("environments") or data.get("topologies"),
        name="Workflow environments",
    )
    flat_topology = {
        key: data[key]
        for key in FLAT_TOPOLOGY_KEYS
        if key in data
    }
    if not flat_topology:
        return environments
    return deep_merge_dicts({"defaults": flat_topology}, environments)


def action_metadata_from_config(data: dict, action: str) -> dict[str, Any]:
    metadata = optional_json_object(
        data.get("dag_metadata") or data.get("dag_overrides"),
        name="dag_metadata",
    )
    return optional_json_object(
        metadata.get(action),
        name=f"dag_metadata.{action}",
    )


def upstream_dags_for_action(data: dict, action: str) -> tuple[ExternalDagDependency, ...]:
    upstream_dags = data.get("upstream_dags") or {}
    legacy_key = f"upstream_dags_for_{action}"
    if isinstance(upstream_dags, list):
        raw_dependencies = upstream_dags if action == RUN_ACTION else []
    elif isinstance(upstream_dags, dict):
        raw_dependencies = upstream_dags.get(action, data.get(legacy_key, []))
    else:
        raise TypeError("upstream_dags must be a JSON object, list, or null.")

    return tuple(
        build_external_dependency_from_config(dep_data)
        for dep_data in raw_dependencies
    )


def build_workflow_definition_from_config(
    data: dict,
    *,
    default_workflow_id: str | None = None,
) -> RemoteWorkflowDefinition:
    schedule_pairs = build_schedule_pairs_from_config(data)
    actions = normalize_workflow_actions(data, schedule_pairs)
    environments = build_workflow_environments_from_config(data)
    target_task_configs = build_remote_target_task_configs(environments)
    explicit_task_groups = tuple(
        build_task_group_spec_from_config(
            group_data,
            target_task_configs=target_task_configs,
        )
        for group_data in data.get("task_groups", [])
    )
    run_metadata = action_metadata_from_config(data, RUN_ACTION)
    start_metadata = action_metadata_from_config(data, START_ACTION)
    stop_metadata = action_metadata_from_config(data, STOP_ACTION)
    status_metadata = action_metadata_from_config(data, STATUS_ACTION)
    workflow_id = data.get("workflow_id") or default_workflow_id
    if not workflow_id:
        raise ValueError("Workflow config must define workflow_id or be loaded from a JSON file.")

    return RemoteWorkflowDefinition(
        workflow_id=workflow_id,
        description=data.get("description", ""),
        schedule_run=schedule_pairs[0].run if schedule_pairs else None,
        schedule_start=schedule_pairs[0].start if schedule_pairs else None,
        schedule_stop=schedule_pairs[0].stop if schedule_pairs else None,
        fields=data.get("fields") or {},
        tasks=build_task_specs_from_config(
            data.get("tasks", []),
            target_task_configs=target_task_configs,
        ),
        task_groups=explicit_task_groups,
        upstream_dags_for_run=upstream_dags_for_action(data, RUN_ACTION),
        upstream_dags_for_start=upstream_dags_for_action(data, START_ACTION),
        upstream_dags_for_stop=upstream_dags_for_action(data, STOP_ACTION),
        upstream_dags_for_status=upstream_dags_for_action(data, STATUS_ACTION),
        schedule_pairs=schedule_pairs,
        actions=actions,
        target_runtime_envs=configured_target_runtime_envs(data),
        enabled_in_envs=normalize_string_tuple(data.get("enabled_in_envs", ALL_RUNTIME_ENVS)),
        environments=environments,
        tags=tuple(data.get("tags") or ("remote-workflow", "ssh")),
        run_description=run_metadata.get(
            "description",
            data.get("run_description", ""),
        ),
        start_description=start_metadata.get(
            "description",
            data.get("start_description", ""),
        ),
        stop_description=stop_metadata.get(
            "description",
            data.get("stop_description", ""),
        ),
        status_description=status_metadata.get(
            "description",
            data.get("status_description", ""),
        ),
        run_tags=normalize_string_tuple(
            run_metadata.get("tags", data.get("run_tags", ()))
        ),
        start_tags=normalize_string_tuple(
            start_metadata.get("tags", data.get("start_tags", ()))
        ),
        stop_tags=normalize_string_tuple(
            stop_metadata.get("tags", data.get("stop_tags", ()))
        ),
        status_tags=normalize_string_tuple(
            status_metadata.get("tags", data.get("status_tags", ()))
        ),
        owner=data.get("owner"),
        command_timeout_seconds=int(data.get("command_timeout_seconds", 1800)),
    )


def load_workflow_definition_from_json(config_file: str | Path) -> RemoteWorkflowDefinition:
    config_path = Path(config_file)
    return build_workflow_definition_from_config(
        load_json_file(config_path),
        default_workflow_id=config_path.stem,
    )


def default_dag_id_prefix(workflow_id: str) -> str:
    return workflow_id.replace("_", "-")


def default_workflow_id_from_path(config_file: Path, config_root: Path | None = None) -> str:
    if config_root is None:
        return config_file.stem
    relative_path = config_file.resolve().relative_to(config_root.resolve()).with_suffix("")
    raw_id = "_".join(relative_path.parts)
    safe_id = re.sub(r"[^A-Za-z0-9_]+", "_", raw_id).strip("_").lower()
    return safe_id or config_file.stem


def schedule_values(schedule: str | tuple[str, ...] | list[str] | None) -> tuple[str, ...]:
    if schedule is None:
        return ()
    if isinstance(schedule, str):
        return (schedule,)
    return tuple(schedule)


def schedule_for_action(
    pair: WorkflowSchedulePair,
    action: str,
) -> str | tuple[str, ...] | list[str] | None:
    return workflow_action_value(
        action,
        run=pair.run,
        start=pair.start,
        stop=pair.stop,
        status=pair.status,
    )


def explicit_dag_id_for_action(pair: WorkflowSchedulePair, action: str) -> str | None:
    return workflow_action_value(
        action,
        run=pair.run_dag_id,
        start=pair.start_dag_id,
        stop=pair.stop_dag_id,
        status=pair.status_dag_id,
    )


def default_dag_id_for_action(workflow_prefix: str, action: str) -> str:
    require_workflow_action(action)
    if action == RUN_ACTION:
        return workflow_prefix
    return f"{workflow_prefix}-{action}"


def default_env_dag_id_for_action(workflow_prefix: str, target_env: str, action: str) -> str:
    require_workflow_action(action)
    if action == RUN_ACTION:
        return f"{workflow_prefix}-{target_env}"
    return f"{workflow_prefix}-{target_env}-{action}"


def collect_schedule_values(
    pairs: tuple[WorkflowSchedulePair, ...],
    action: str,
) -> str | tuple[str, ...] | None:
    values = unique_preserving_order(
        [
            value
            for pair in pairs
            for value in schedule_values(schedule_for_action(pair, action))
        ]
    )
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    return tuple(values)


def build_workflow_action_dag_id(
    *,
    action: str,
    workflow_prefix: str,
    enabled_pairs: tuple[WorkflowSchedulePair, ...],
    top_level_dag_ids: dict,
    target_env: str | None = None,
    include_env_in_dag_id: bool = False,
) -> str:
    if include_env_in_dag_id and target_env:
        env_dag_ids = top_level_dag_ids.get(target_env)
        if isinstance(env_dag_ids, dict) and env_dag_ids.get(action):
            return env_dag_ids[action]
        return default_env_dag_id_for_action(workflow_prefix, target_env, action)

    top_level_dag_id = top_level_dag_ids.get(action)
    if top_level_dag_id:
        return top_level_dag_id

    if len(enabled_pairs) == 1:
        pair = enabled_pairs[0]
        explicit_dag_id = explicit_dag_id_for_action(pair, action)
        if explicit_dag_id:
            return explicit_dag_id

    return default_dag_id_for_action(workflow_prefix, action)


def workflow_targets_runtime_envs(
    workflow: RemoteWorkflowDefinition,
    current_env: str,
) -> bool:
    return bool(workflow.target_runtime_envs) and workflow.target_runtime_envs[0] == current_env


def effective_target_runtime_envs(
    workflow: RemoteWorkflowDefinition,
    current_env: str,
) -> tuple[str, ...]:
    if workflow_targets_runtime_envs(workflow, current_env):
        return workflow.target_runtime_envs
    return (current_env,)


def workflow_namespace_key(
    *,
    workflow_id: str,
    action: str,
    target_env: str,
    include_env: bool,
) -> str:
    parts = [workflow_id]
    if include_env:
        parts.append(target_env)
    parts.append(action)
    return "_".join(sanitize_task_id(part) for part in parts)


def register_workflow_dags_from_json(
    *,
    config_file: str | Path,
    global_namespace: dict,
    config_root: str | Path | None = None,
) -> None:
    config_path = Path(config_file)

    root_path = Path(config_root) if config_root is not None else None
    config = load_json_file(config_path)
    workflow = build_workflow_definition_from_config(
        config,
        default_workflow_id=default_workflow_id_from_path(config_path, root_path),
    )
    current_env = get_current_env_name()
    if current_env not in workflow.enabled_in_envs:
        return

    dag_id_prefix = config.get("dag_id_prefix", default_dag_id_prefix(workflow.workflow_id))
    dag_ids = dict(config.get("dag_ids", {}))
    if config.get("dag_id"):
        dag_ids.setdefault(RUN_ACTION, config["dag_id"])
    include_env_in_dag_id = workflow_targets_runtime_envs(workflow, current_env)
    target_envs = effective_target_runtime_envs(workflow, current_env)
    for target_env in target_envs:
        enabled_pairs = tuple(
            pair for pair in workflow.schedule_pairs if target_env in pair.enabled_in_envs
        )
        if not enabled_pairs:
            continue

        for action in workflow.actions:
            action_dag = create_workflow_dag(
                workflow=workflow,
                dag_id=build_workflow_action_dag_id(
                    action=action,
                    workflow_prefix=dag_id_prefix,
                    enabled_pairs=enabled_pairs,
                    top_level_dag_ids=dag_ids,
                    target_env=target_env,
                    include_env_in_dag_id=include_env_in_dag_id,
                ),
                action=action,
                schedule=collect_schedule_values(enabled_pairs, action),
                target_env=target_env,
                source_file=config_file,
            )
            global_namespace[
                workflow_namespace_key(
                    workflow_id=workflow.workflow_id,
                    action=action,
                    target_env=target_env,
                    include_env=include_env_in_dag_id,
                )
            ] = action_dag


def register_workflow_dags_from_json_dir(
    *,
    config_dir: str | Path,
    global_namespace: dict,
) -> None:
    config_root = Path(config_dir)
    for config_file in sorted(config_root.rglob("*.json")):
        register_workflow_dags_from_json(
            config_file=config_file,
            global_namespace=global_namespace,
            config_root=config_root,
        )
