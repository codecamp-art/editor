from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from airflow.decorators import get_current_context, task
from airflow.providers.ssh.operators.ssh import SSHOperator

from common.config_loader import get_current_env_name
from common.dag_factory import (
    DEFAULT_RUNTIME_ENV_FILE,
    build_executor_config,
    build_runtime_context,
    dag_decorator,
)
from common.field_schema import (
    COMMON_FIELDS,
    build_airflow_params_from_fields,
    merge_field_definitions,
    validate_fields,
)
from common.remote_command import build_sudo_bash_command, split_extra_args
from common.ssh_hook import MSSSHHook
from common.trading_calendar import TradingDayCheckDefinition
from common.trading_day_tasks import (
    create_trading_day_decide_task,
    create_trading_day_prepare_task,
    create_trading_day_ssh_task,
)


@dataclass(frozen=True)
class ReportingDefinition:
    report_id: str
    dag_id: str
    title: str
    description: str
    schedule: str | None
    remote_script: str
    sudo_user: str
    fields: dict
    adhoc_rules: dict
    trading_day_check: TradingDayCheckDefinition | None = None
    preset_params: dict | None = None
    command_timeout_seconds: int = 3600
    tags: tuple[str, ...] = ("reporting", "ssh", "kerberos")


@dataclass(frozen=True)
class ReportingScheduleVariant:
    dag_id: str
    title_suffix: str
    description_suffix: str = ""
    schedule: str | None = None
    preset_params: dict | None = None
    fields_override: dict | None = None
    adhoc_rules_override: dict | None = None
    tags_additional: tuple[str, ...] = ()
    env_overrides: dict | None = None
    """
    env_overrides example:
    {
        "dev": {
            "schedule": "10 9 * * 1-5",
            "sudo_user": "reportuser_dev",
            "remote_script": "/opt/reporting/dev/run_report.sh",
            "preset_params": {...},
            "trading_day_check": TradingDayCheckDefinition(...),
            "command_timeout_seconds": 3600
        },
        "qa": {
            ...
        }
    }
    """


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
        if value in (None, ""):
            continue

        cli_name = spec.get("cli_name", field_name)
        transform = spec.get("transform")

        if transform == "lower_bool":
            value = str(bool(value)).lower()

        args.append(f"--{cli_name}={value}")

    return args


def create_reporting_dag(
    *,
    definition: ReportingDefinition,
    runtime_env_file: str | Path = DEFAULT_RUNTIME_ENV_FILE,
):
    runtime_context = build_runtime_context(runtime_env_file)
    merged_fields = merge_field_definitions(COMMON_FIELDS, definition.fields)
    airflow_params = build_airflow_params_from_fields(merged_fields)
    executor_config = build_executor_config(runtime_context)

    @dag_decorator(
        dag_id=definition.dag_id,
        description=definition.description,
        schedule=definition.schedule,
        tags=list(definition.tags),
        timezone=runtime_context["timezone"],
        params=airflow_params,
    )
    def _dag():
        @task(task_id="validate_and_prepare")
        def validate_and_prepare() -> dict:
            context = get_current_context()
            user_params = dict(context["params"])

            effective_input = merge_params_with_priority(
                definition.preset_params,
                user_params,
            )

            validated = validate_fields(effective_input, merged_fields)
            apply_adhoc_rules(validated, definition.adhoc_rules)

            arg_list = build_args_from_fields(validated, merged_fields)

            command = build_sudo_bash_command(
                sudo_user=definition.sudo_user,
                script_and_args=[
                    definition.remote_script,
                    *arg_list,
                    *split_extra_args(validated.get("extra_args")),
                ],
            )
            return {
                "command": command,
                "validated_params": validated,
            }

        prepared = validate_and_prepare()

        if definition.trading_day_check is not None:
            prepare_trading_day_check = create_trading_day_prepare_task(
                task_id="prepare_trading_day_check",
                trading_day_check=definition.trading_day_check,
            )

            run_trading_day_check = create_trading_day_ssh_task(
                task_id="run_trading_day_check",
                trading_day_check=definition.trading_day_check,
                kerberos_principal=runtime_context["kerberos_principal"],
                executor_config=executor_config,
            )

            decide_trading_day = create_trading_day_decide_task(
                task_id="decide_trading_day",
            )

            trading_day_result = decide_trading_day(
                prepare_trading_day_check,
                run_trading_day_check.output,
            )

            prepared >> prepare_trading_day_check >> run_trading_day_check >> trading_day_result
        else:
            trading_day_result = None

        run_report = SSHOperator(
            task_id="run_report",
            ssh_hook=MSSSHHook(
                remote_host=runtime_context["target_host"],
                username=runtime_context["kerberos_principal"],
                enable_kerberos=True,
            ),
            command="{{ ti.xcom_pull(task_ids='validate_and_prepare')['command'] }}",
            cmd_timeout=definition.command_timeout_seconds,
            executor_config=executor_config,
        )

        if trading_day_result is not None:
            trading_day_result >> run_report
        else:
            prepared >> run_report

    return _dag()


def create_reporting_definition_variant(
    *,
    base_definition: ReportingDefinition,
    variant: ReportingScheduleVariant,
) -> ReportingDefinition:
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

    resolved_schedule = env_override.get("schedule", variant.schedule if variant.schedule is not None else base_definition.schedule)
    resolved_sudo_user = env_override.get("sudo_user", base_definition.sudo_user)
    resolved_remote_script = env_override.get("remote_script", base_definition.remote_script)
    resolved_trading_day_check = env_override.get("trading_day_check", base_definition.trading_day_check)
    resolved_timeout = env_override.get("command_timeout_seconds", base_definition.command_timeout_seconds)

    return ReportingDefinition(
        report_id=base_definition.report_id,
        dag_id=variant.dag_id,
        title=f"{base_definition.title} {variant.title_suffix}".strip(),
        description=description,
        schedule=resolved_schedule,
        remote_script=resolved_remote_script,
        sudo_user=resolved_sudo_user,
        fields=fields,
        adhoc_rules=adhoc_rules,
        trading_day_check=resolved_trading_day_check,
        preset_params=preset_params,
        command_timeout_seconds=resolved_timeout,
        tags=tags,
    )