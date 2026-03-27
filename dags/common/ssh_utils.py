from __future__ import annotations

import os

from airflow.exceptions import AirflowException
from airflow.providers.ssh.hooks.ssh import SSHHook
from airflow.sdk import get_current_context


def build_default_ssh_hook(remote_host: str) -> SSHHook:
    username = os.getenv("SSH_USERNAME") or None
    password = os.getenv("SSH_PASSWORD") or None
    return SSHHook(
        remote_host=remote_host,
        username=username,
        password=password,
    )


def execute_ssh_command(
    *,
    task_id: str,
    remote_host: str,
    command: str,
    cmd_timeout: int,
) -> str:
    context = get_current_context()
    ssh_hook = build_default_ssh_hook(remote_host)
    get_pty = command.startswith("sudo")

    with ssh_hook.get_conn() as ssh_client:
        exit_status, agg_stdout, agg_stderr = ssh_hook.exec_ssh_client_command(
            ssh_client,
            command,
            get_pty=get_pty,
            environment=None,
            timeout=cmd_timeout,
        )

    if exit_status != 0:
        raise AirflowException(
            f"SSH operator error in task '{task_id}': exit status = {exit_status}, "
            f"stderr = {agg_stderr.decode('utf-8', errors='replace')}"
        )

    task_instance = context.get("task_instance")
    if task_instance is not None:
        task_instance.xcom_push(key="ssh_exit", value=exit_status)

    return agg_stdout.decode("utf-8", errors="replace")
