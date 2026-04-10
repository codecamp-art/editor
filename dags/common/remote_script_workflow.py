from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from airflow.sdk import get_current_context, task

from common.config_loader import get_current_env_name
from common.config_loader import load_json_file
from common.config_loader import load_runtime_env_config

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
    build_inner_command,
    build_sudo_bash_command,
    split_extra_args,
)
from common.ssh_utils import execute_ssh_command
from common.trading_calendar import TradingDayCheckDefinition
from common.trading_day_tasks import (
    build_trading_day_check_task,
)

ALL_RUNTIME_ENVS = ("dev", "qa", "prod", "dr")
DEFAULT_REMOTE_SCRIPT_TARGET_HOST_FILE = Path(__file__).resolve().parents[1] / "reporting" / "target_hosts.json"
REPORTING_DAGS_DIR = Path(__file__).resolve().parents[1] / "reporting"




def normalize_enabled_in_envs(enabled_in_envs: tuple[str, ...] | list[str] | str | None) -> tuple[str, ...]:
    """Normalize enabled envs to a tuple, allowing strings and comma-delimited values."""
    if enabled_in_envs is None:
        return ()

    if isinstance(enabled_in_envs, str):
        if "," in enabled_in_envs:
            return tuple(env.strip() for env in enabled_in_envs.split(",") if env.strip())
        stripped = enabled_in_envs.strip()
        return (stripped,) if stripped else ()

    return tuple(str(env).strip() for env in enabled_in_envs if str(env).strip())

@dataclass(frozen=True)
class RemoteScriptDefinition:
    report_id: str
    dag_id: str
    title: str
    description: str
    schedule: str | None
    remote_script: str | None
    remote_command_prefix: list[str] | None
    sudo_user: str
    working_dir: str | None
    fields: dict
    adhoc_rules: dict
    trading_day_check: TradingDayCheckDefinition | None = None
    preset_params: dict | None = None
    command_timeout_seconds: int = 3600
    tags: tuple[str, ...] = ("reporting", "ssh")
    enabled_in_envs: tuple[str, ...] = ALL_RUNTIME_ENVS
    target_host_options: tuple[str, ...] | list[str] | None = None
    default_target_host: str | None = None
    target_host_config_file: str | Path | None = None


@dataclass(frozen=True)
class RemoteScriptScheduleVariant:
    dag_id: str
    title_suffix: str
    description_suffix: str = ""
    schedule: str | None = None
    preset_params: dict | None = None
    fields_override: dict | None = None
    adhoc_rules_override: dict | None = None
    tags_additional: tuple[str, ...] = ()
    env_overrides: dict | None = None
    enabled_in_envs: tuple[str, ...] | None = None
    target_host_options: tuple[str, ...] | list[str] | None = None
    default_target_host: str | None = None
    target_host_config_file: str | Path | None = None


def apply_adhoc_rules(validated: dict, adhoc_rules: dict) -> None:
    run_mode = validated.get("run_mode", "normal")
    if run_mode != "adhoc":
        return

    required = adhoc_rules.get("required", [])
    for field_name in required:
        if validated.get(field_name) in (None, ""):
            raise ValueError(f"{field_name} is required in adhoc mode.")

    required_together = adhoc_rules.get("required_together", [])
    for group in required_together:
        if not all(validated.get(field) not in (None, "") for field in group):
            raise ValueError(f"Fields {group} must all be provided together in adhoc mode.")


def merge_params_with_priority(*param_maps: dict | None) -> dict:
    merged: dict = {}
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


def resolve_command_prefix(definition: RemoteScriptDefinition) -> list[str]:
    if definition.remote_command_prefix:
        return definition.remote_command_prefix
    if definition.remote_script:
        return [definition.remote_script]
    raise ValueError(
        f"RemoteScriptDefinition '{definition.dag_id}' must define either "
        f"remote_script or remote_command_prefix."
    )


def build_airflow_params_from_preset_keys(
    *,
    all_fields: dict,
    preset_params: dict | None,
    always_visible_fields: tuple[str, ...] = (),
) -> dict:
    visible_fields = {
        field_name: {
            **all_fields[field_name],
            "default": preset_params[field_name],
        }
        for field_name in preset_params.keys()
        if field_name in all_fields
    } if preset_params else {}

    for field_name in always_visible_fields:
        if field_name in all_fields:
            visible_fields[field_name] = all_fields[field_name]

    return build_airflow_params_from_fields(visible_fields)


def load_remote_script_target_host_settings(
    config_file: str | Path = DEFAULT_REMOTE_SCRIPT_TARGET_HOST_FILE,
) -> tuple[str, list[str]]:
    cfg = load_runtime_env_config(config_file)
    target_host_options = cfg.get("target_host_options") or []

    if not isinstance(target_host_options, list) or not target_host_options:
        raise ValueError(
            "Reporting target host config must contain a non-empty list in 'target_host_options'."
        )

    default_target_host = cfg.get("default_target_host") or target_host_options[0]
    if default_target_host not in target_host_options:
        raise ValueError(
            "Reporting target host config 'default_target_host' must be in 'target_host_options'."
        )

    return default_target_host, target_host_options


def _is_reporting_dag_source(source_file: str | Path) -> bool:
    source_path = Path(source_file).resolve()
    try:
        source_path.relative_to(REPORTING_DAGS_DIR)
        return True
    except ValueError:
        return False


def resolve_target_host_settings(
    definition: RemoteScriptDefinition,
    *,
    source_file: str | Path,
) -> tuple[str, list[str]]:
    configured_options = definition.target_host_options

    if configured_options is not None:
        target_host_options = [str(host).strip() for host in configured_options if str(host).strip()]
        if not target_host_options:
            raise ValueError(
                "RemoteScriptDefinition 'target_host_options' must contain at least one host."
            )

        default_target_host = definition.default_target_host or target_host_options[0]
        if default_target_host not in target_host_options:
            raise ValueError(
                "RemoteScriptDefinition 'default_target_host' must be included in "
                "'target_host_options'."
            )

        return default_target_host, target_host_options

    if definition.target_host_config_file:
        config_path = Path(definition.target_host_config_file)
        if not config_path.is_absolute():
            config_path = Path(source_file).resolve().parent / config_path
        return load_remote_script_target_host_settings(config_file=config_path)

    if _is_reporting_dag_source(source_file):
        return load_remote_script_target_host_settings()

    raise ValueError(
        "RemoteScriptDefinition must define 'target_host_options' or 'target_host_config_file' "
        "for non-reporting DAGs."
    )


def create_remote_script_dag(
    *,
    definition: RemoteScriptDefinition,
    source_file: str | Path,
    runtime_env_file: str | Path = DEFAULT_RUNTIME_ENV_FILE,
):
    if not should_register_remote_script_dag(definition):
        return None

    runtime_context = build_runtime_context(
        config_file=runtime_env_file,
    )
    default_target_host, target_host_options = resolve_target_host_settings(
        definition,
        source_file=source_file,
    )
    owner = runtime_context["owner"]

    merged_fields = merge_field_definitions(
        COMMON_FIELDS,
        {
            "target_host": {
                "type": "enum",
                "default": default_target_host,
                "values": target_host_options,
                "description": "Remote reporting host to run this DAG command on",
                "include_in_cli": False,
            }
        },
        definition.fields,
    )
    airflow_params = build_airflow_params_from_preset_keys(
        all_fields=merged_fields,
        preset_params=definition.preset_params,
        always_visible_fields=("target_host",),
    )

    executor_config = build_minimal_tenant_executor_config(runtime_context)
    command_prefix = resolve_command_prefix(definition)

    @dag_decorator(
        dag_id=definition.dag_id,
        description=definition.description,
        schedule=definition.schedule,
        tags=list(definition.tags),
        timezone=runtime_context["timezone"],
        params=airflow_params,
        owner=owner,
    )
    def _dag():
        @task(task_id="run_report", multiple_outputs=False)
        def run_report() -> str:
            context = get_current_context()
            user_params = dict(context["params"])

            effective_input = merge_params_with_priority(
                definition.preset_params,
                user_params,
            )

            validated = validate_fields(effective_input, merged_fields)
            apply_adhoc_rules(validated, definition.adhoc_rules)

            app_args = [
                *build_args_from_fields(validated, merged_fields),
                *split_extra_args(validated.get("extra_args")),
            ]

            env_vars = build_env_vars_from_fields(validated, merged_fields)

            inner_command = build_inner_command(
                command_prefix=command_prefix,
                app_args=app_args,
                working_dir=definition.working_dir,
                env_vars=env_vars,
            )

            command = build_sudo_bash_command(
                sudo_user=definition.sudo_user,
                inner_command=inner_command,
            )

            return execute_ssh_command(
                task_id="run_report__ssh",
                remote_host=validated["target_host"],
                command=command,
                cmd_timeout=definition.command_timeout_seconds,
            )

        run_report_task = run_report.override(
            executor_config=executor_config
        )()

        if definition.trading_day_check is not None:
            trading_day_check_task = build_trading_day_check_task(
                task_id="trading_day_check",
                trading_day_check=definition.trading_day_check,
            )
            trading_day_check_result = trading_day_check_task.override(
                executor_config=executor_config
            )()

            trading_day_check_result >> run_report_task

    return _dag()


def should_register_remote_script_dag(definition: RemoteScriptDefinition) -> bool:
    return get_current_env_name() in normalize_enabled_in_envs(definition.enabled_in_envs)


def create_remote_script_definition_variant(
    *,
    base_definition: RemoteScriptDefinition,
    variant: RemoteScriptScheduleVariant,
) -> RemoteScriptDefinition:
    current_env = get_current_env_name()
    env_override = (variant.env_overrides or {}).get(current_env, {})

    fields = merge_field_definitions(
        base_definition.fields,
        variant.fields_override or {},
    )

    adhoc_rules = dict(base_definition.adhoc_rules)
    if variant.adhoc_rules_override:
        adhoc_rules.update(variant.adhoc_rules_override)

    preset_params = merge_params_with_priority(
        base_definition.preset_params,
        variant.preset_params,
        env_override.get("preset_params"),
    )

    description = base_definition.description
    if variant.description_suffix:
        description = f"{description}\n\n{variant.description_suffix}".strip()

    tags = tuple(dict.fromkeys([*base_definition.tags, *variant.tags_additional]))

    resolved_schedule = env_override.get(
        "schedule",
        variant.schedule if variant.schedule is not None else base_definition.schedule,
    )
    resolved_sudo_user = env_override.get("sudo_user", base_definition.sudo_user)
    resolved_working_dir = env_override.get("working_dir", base_definition.working_dir)
    resolved_remote_script = env_override.get("remote_script", base_definition.remote_script)
    resolved_remote_command_prefix = env_override.get(
        "remote_command_prefix",
        base_definition.remote_command_prefix,
    )
    resolved_trading_day_check = env_override.get(
        "trading_day_check",
        base_definition.trading_day_check,
    )
    resolved_timeout = env_override.get(
        "command_timeout_seconds",
        base_definition.command_timeout_seconds,
    )
    resolved_enabled_in_envs = env_override.get(
        "enabled_in_envs",
        variant.enabled_in_envs if variant.enabled_in_envs is not None else base_definition.enabled_in_envs,
    )
    resolved_target_host_options = env_override.get(
        "target_host_options",
        variant.target_host_options
        if variant.target_host_options is not None
        else base_definition.target_host_options,
    )
    resolved_default_target_host = env_override.get(
        "default_target_host",
        variant.default_target_host
        if variant.default_target_host is not None
        else base_definition.default_target_host,
    )
    resolved_target_host_config_file = env_override.get(
        "target_host_config_file",
        variant.target_host_config_file
        if variant.target_host_config_file is not None
        else base_definition.target_host_config_file,
    )

    normalized_enabled_in_envs = normalize_enabled_in_envs(resolved_enabled_in_envs)

    return RemoteScriptDefinition(
        report_id=base_definition.report_id,
        dag_id=variant.dag_id,
        title=f"{base_definition.title} {variant.title_suffix}".strip(),
        description=description,
        schedule=resolved_schedule,
        remote_script=resolved_remote_script,
        remote_command_prefix=resolved_remote_command_prefix,
        sudo_user=resolved_sudo_user,
        working_dir=resolved_working_dir,
        fields=fields,
        adhoc_rules=adhoc_rules,
        trading_day_check=resolved_trading_day_check,
        preset_params=preset_params,
        command_timeout_seconds=resolved_timeout,
        tags=tags,
        enabled_in_envs=normalized_enabled_in_envs,
        target_host_options=resolved_target_host_options,
        default_target_host=resolved_default_target_host,
        target_host_config_file=resolved_target_host_config_file,
    )


def build_trading_day_check_definition(
    data: dict | None,
) -> TradingDayCheckDefinition | None:
    if not data:
        return None

    return TradingDayCheckDefinition(
        check_host=data["check_host"],
        check_user=data["check_user"],
        command_template=data["command_template"],
        market=data["market"],
        calendar_code=data["calendar_code"],
        timeout_seconds=int(data.get("timeout_seconds", 300)),
    )


def build_remote_script_definition_from_config(
    base: dict,
) -> RemoteScriptDefinition:
    return RemoteScriptDefinition(
        report_id=base["report_id"],
        dag_id=base["dag_id"],
        title=base["title"],
        description=base["description"],
        schedule=base.get("schedule"),
        remote_script=base.get("remote_script"),
        remote_command_prefix=base.get("remote_command_prefix"),
        sudo_user=base["sudo_user"],
        working_dir=base.get("working_dir"),
        fields=base.get("fields") or {},
        adhoc_rules=base.get("adhoc_rules") or {},
        trading_day_check=build_trading_day_check_definition(base.get("trading_day_check")),
        preset_params=base.get("preset_params"),
        command_timeout_seconds=int(base.get("command_timeout_seconds", 3600)),
        tags=tuple(base.get("tags") or ("reporting", "ssh")),
        enabled_in_envs=tuple(base.get("enabled_in_envs", ALL_RUNTIME_ENVS)),
        target_host_options=base.get("target_host_options"),
        default_target_host=base.get("default_target_host"),
        target_host_config_file=base.get("target_host_config_file"),
    )


def build_remote_script_variant_from_config(
    variant: dict,
) -> RemoteScriptScheduleVariant:
    return RemoteScriptScheduleVariant(
        dag_id=variant["dag_id"],
        title_suffix=variant["title_suffix"],
        description_suffix=variant.get("description_suffix", ""),
        schedule=variant.get("schedule"),
        preset_params=variant.get("preset_params"),
        fields_override=variant.get("fields_override"),
        adhoc_rules_override=variant.get("adhoc_rules_override"),
        tags_additional=tuple(variant.get("tags_additional", [])),
        env_overrides=variant.get("env_overrides"),
        enabled_in_envs=tuple(variant["enabled_in_envs"]) if variant.get("enabled_in_envs") else None,
        target_host_options=variant.get("target_host_options"),
        default_target_host=variant.get("default_target_host"),
        target_host_config_file=variant.get("target_host_config_file"),
    )


def register_remote_script_dags_from_json(
    *,
    config_file: str | Path,
    source_file: str | Path,
    global_namespace: dict,
) -> None:
    config_path = Path(config_file)
    if not config_path.is_absolute():
        config_path = Path(source_file).resolve().parent / config_path

    config = load_json_file(config_path)
    base_definition = build_remote_script_definition_from_config(config["base"])

    variants_data = list(config.get("variants", []))
    if not variants_data:
        variants_data.extend(config.get("scheduled_variants", []))
        adhoc_variant_data = config.get("adhoc_variant")
        if adhoc_variant_data:
            variants_data.append(adhoc_variant_data)

    for variant_data in variants_data:
        variant = build_remote_script_variant_from_config(variant_data)
        definition = create_remote_script_definition_variant(
            base_definition=base_definition,
            variant=variant,
        )
        dag = create_remote_script_dag(
            definition=definition,
            source_file=source_file,
        )
        if dag is not None:
            global_name = variant_data.get("global_name") or definition.dag_id.replace("-", "_")
            global_namespace[global_name] = dag
