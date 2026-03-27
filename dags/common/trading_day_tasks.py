from __future__ import annotations

from airflow.sdk import get_current_context, task
from airflow.exceptions import AirflowSkipException
from airflow.providers.ssh.operators.ssh import SSHOperator

from common.remote_command import build_sudo_bash_command
from common.ssh_utils import build_default_ssh_hook, execute_ssh_command
from common.trading_calendar import TradingDayCheckDefinition


def build_trading_day_check_task(
    *,
    task_id: str,
    trading_day_check: TradingDayCheckDefinition,
):
    @task(task_id=task_id)
    def _trading_day_check() -> str:
        context = get_current_context()
        params = dict(context["params"])

        run_mode = params.get("run_mode", "normal")
        if run_mode == "adhoc":
            return "BYPASS"

        business_date = params.get("business_date")
        if not business_date:
            logical_date = context["logical_date"]
            business_date = logical_date.strftime("%Y-%m-%d")

        remote_inner_command = trading_day_check.command_template.format(
            business_date=business_date,
            market=trading_day_check.market,
            calendar_code=trading_day_check.calendar_code,
        )

        remote_command = build_sudo_bash_command(
            sudo_user=trading_day_check.check_user,
            inner_command=remote_inner_command,
        )

        ssh_result = execute_ssh_command(
            task_id=f"{task_id}__ssh",
            remote_host=trading_day_check.check_host,
            command=remote_command,
            cmd_timeout=trading_day_check.timeout_seconds,
        )
        result = ssh_result.strip().upper()

        if result in {"Y", "YES", "TRUE", "1"}:
            return "TRADING_DAY"

        if result in {"N", "NO", "FALSE", "0"}:
            raise AirflowSkipException(
                f"Business date {business_date} is not a trading day."
            )

        raise ValueError(
            f"Unexpected trading-day check result: '{ssh_result}'. "
            f"Expected one of Y/YES/TRUE/1 or N/NO/FALSE/0."
        )

    return _trading_day_check


def build_prepare_trading_day_check_task(
    *,
    task_id: str,
    trading_day_check: TradingDayCheckDefinition,
):
    @task(task_id=task_id)
    def _prepare_trading_day_check() -> dict:
        context = get_current_context()
        params = dict(context["params"])

        run_mode = params.get("run_mode", "normal")
        if run_mode == "adhoc":
            return {
                "enabled": False,
                "reason": "adhoc mode bypasses trading-day check",
                "command": "",
            }

        business_date = params.get("business_date")
        if not business_date:
            logical_date = context["logical_date"]
            business_date = logical_date.strftime("%Y-%m-%d")

        remote_inner_command = trading_day_check.command_template.format(
            business_date=business_date,
            market=trading_day_check.market,
            calendar_code=trading_day_check.calendar_code,
        )

        remote_command = build_sudo_bash_command(
            sudo_user=trading_day_check.check_user,
            inner_command=remote_inner_command,
        )

        return {
            "enabled": True,
            "reason": "normal mode requires trading-day check",
            "business_date": business_date,
            "command": remote_command,
        }

    return _prepare_trading_day_check


def build_trading_day_ssh_task(
    *,
    task_id: str,
    trading_day_check: TradingDayCheckDefinition,
    executor_config: dict,
):
    return SSHOperator(
        task_id=task_id,
        ssh_hook=build_default_ssh_hook(trading_day_check.check_host),
        command="{{ ti.xcom_pull(task_ids='prepare_trading_day_check')['command'] }}",
        cmd_timeout=trading_day_check.timeout_seconds,
        executor_config=executor_config,
        do_xcom_push=True,
    )


def build_decide_trading_day_task(
    *,
    task_id: str,
):
    @task(task_id=task_id)
    def _decide_trading_day(prepared: dict, ssh_result: str | None = None) -> str:
        if not prepared["enabled"]:
            return "BYPASS"

        result = (ssh_result or "").strip().upper()

        if result in {"Y", "YES", "TRUE", "1"}:
            return "TRADING_DAY"

        if result in {"N", "NO", "FALSE", "0"}:
            raise AirflowSkipException(
                f"Business date {prepared.get('business_date')} is not a trading day."
            )

        raise ValueError(
            f"Unexpected trading-day check result: '{ssh_result}'. "
            f"Expected one of Y/YES/TRUE/1 or N/NO/FALSE/0."
        )

    return _decide_trading_day
