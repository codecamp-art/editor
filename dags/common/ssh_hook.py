from __future__ import annotations

from airflow.sdk import get_current_context
from airflow.providers.ssh.operators.ssh import SSHOperator

try:
    # Replace this with your real Kerberos-enabled SSH hook
    from my_company.airflow.hooks.ms_ssh import MSSSHHook  # type: ignore
except ImportError as exc:
    raise ImportError(
        "Unable to import MSSSHHook. Update dags/common/ssh_hook.py "
        "to point to your actual Kerberos-enabled SSH hook implementation."
    ) from exc


def execute_ssh_command(
    *,
    task_id: str,
    ssh_hook: MSSSHHook,
    command: str,
    cmd_timeout: int | None = None,
) -> str:
    operator = SSHOperator(
        task_id=task_id,
        ssh_hook=ssh_hook,
        command=command,
        cmd_timeout=cmd_timeout,
    )
    result = operator.execute(context=get_current_context())

    if result is None:
        return ""
    if isinstance(result, bytes):
        return result.decode("utf-8")
    return str(result)


__all__ = ["MSSSHHook", "execute_ssh_command"]
