from __future__ import annotations

import os
from functools import cached_property
from typing import Any

import paramiko
from airflow.exceptions import AirflowException
from airflow.providers.ssh.hooks.ssh import SSHHook, SSH_PORT
from airflow.providers.ssh.operators.ssh import SSHOperator
from airflow.sdk import get_current_context
from tenacity import Retrying, stop_after_attempt, wait_fixed, wait_random

try:
    from airflow.sdk.definitions._internal.types import NOTSET, ArgNotSet
except ImportError:
    from airflow.utils.types import NOTSET, ArgNotSet  # type: ignore[attr-defined,no-redef]


def _coerce_bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() == "true"


class MSSSHHook(SSHHook):
    """
    Custom SSH hook with optional Kerberos settings and env-var fallback
    for username/password.

    Typical usage for your case:
    - host/port come from Airflow SSH connection or remote_host
    - username/password come from pod env vars injected from K8s Secret
    - Kerberos stays disabled unless explicitly enabled
    """

    def __init__(
        self,
        ssh_conn_id: str | None = None,
        remote_host: str = "",
        username: str | None = None,
        password: str | None = None,
        key_file: str | None = None,
        port: int | None = None,
        conn_timeout: int | None = None,
        cmd_timeout: float | ArgNotSet | None = NOTSET,
        keepalive_interval: int = 30,
        banner_timeout: float = 30.0,
        disabled_algorithms: dict | None = None,
        ciphers: list[str] | None = None,
        auth_timeout: int | None = None,
        host_proxy_cmd: str | None = None,
        enable_kerberos: bool | None = None,
        gss_auth: bool | None = None,
        gss_kex: bool | None = None,
        gss_deleg_creds: bool | None = None,
        gss_host: str | None = None,
        gss_trust_dns: bool | None = None,
        env_username_var: str = "SSH_USERNAME",
        env_password_var: str = "SSH_PASSWORD",
    ) -> None:
        super().__init__(
            ssh_conn_id=ssh_conn_id,
            remote_host=remote_host,
            username=username,
            password=password,
            key_file=key_file,
            port=port,
            conn_timeout=conn_timeout,
            cmd_timeout=cmd_timeout,
            keepalive_interval=keepalive_interval,
            banner_timeout=banner_timeout,
            disabled_algorithms=disabled_algorithms,
            ciphers=ciphers,
            auth_timeout=auth_timeout,
            host_proxy_cmd=host_proxy_cmd,
        )

        extra_options: dict[str, Any] = {}
        if self.ssh_conn_id is not None:
            conn = self.get_connection(self.ssh_conn_id)
            extra_options = conn.extra_dejson if conn.extra else {}

        self.enable_kerberos = _coerce_bool(
            enable_kerberos if enable_kerberos is not None else extra_options.get("enable_kerberos"),
            False,
        )
        self.gss_auth = _coerce_bool(
            gss_auth if gss_auth is not None else extra_options.get("gss_auth"),
            self.enable_kerberos,
        )
        self.gss_kex = _coerce_bool(
            gss_kex if gss_kex is not None else extra_options.get("gss_kex"),
            False,
        )
        self.gss_deleg_creds = _coerce_bool(
            gss_deleg_creds if gss_deleg_creds is not None else extra_options.get("gss_deleg_creds"),
            True,
        )
        self.gss_host = gss_host or extra_options.get("gss_host")
        self.gss_trust_dns = _coerce_bool(
            gss_trust_dns if gss_trust_dns is not None else extra_options.get("gss_trust_dns"),
            True,
        )

        self.env_username_var = env_username_var
        self.env_password_var = env_password_var

    def get_conn(self) -> paramiko.SSHClient:
        """
        Establish an SSH connection to the remote host, with optional Kerberos auth.
        Username/password can come from:
        1. explicit hook args
        2. Airflow connection
        3. environment variables
        """
        if self.client:
            transport = self.client.get_transport()
            if transport and transport.is_active():
                return self.client

        self.log.debug("Creating SSH client for conn_id: %s", self.ssh_conn_id)

        client = paramiko.SSHClient()

        if self.allow_host_key_change:
            self.log.warning(
                "Remote Identification Change is not verified. "
                "This won't protect against Man-In-The-Middle attacks"
            )
            client.set_missing_host_key_policy(paramiko.MissingHostKeyPolicy)
        else:
            client.load_system_host_keys()

        if self.no_host_key_check:
            self.log.warning(
                "No Host Key Verification. This won't protect against Man-In-The-Middle attacks"
            )
            client.set_missing_host_key_policy(paramiko.AutoAddPolicy())  # nosec B507
            known_hosts = os.path.expanduser("~/.ssh/known_hosts")
            if not self.allow_host_key_change and os.path.isfile(known_hosts):
                client.load_host_keys(known_hosts)
        elif self.host_key is not None:
            client_host_keys = client.get_host_keys()
            if self.port == SSH_PORT:
                client_host_keys.add(self.remote_host, self.host_key.get_name(), self.host_key)
            else:
                client_host_keys.add(
                    f"[{self.remote_host}]:{self.port}",
                    self.host_key.get_name(),
                    self.host_key,
                )

        resolved_username = self.username or os.getenv(self.env_username_var)
        resolved_password = self.password or os.getenv(self.env_password_var)

        if not self.remote_host:
            raise AirflowException(
                "SSH remote_host is not set. Provide it via ssh_conn_id or remote_host."
            )

        if not resolved_username:
            raise AirflowException(
                f"SSH username is not set. Checked Airflow connection and env var "
                f"{self.env_username_var}."
            )

        self.log.info(
            "Preparing SSH connection: conn_id=%s, host=%s, port=%s, username=%s, kerberos=%s",
            self.ssh_conn_id,
            self.remote_host,
            self.port,
            resolved_username,
            self.enable_kerberos,
        )
        self.log.info(
            "SSH password source present: %s",
            "yes" if bool(resolved_password) else "no",
        )

        connect_kwargs: dict[str, Any] = {
            "hostname": self.remote_host,
            "username": resolved_username,
            "timeout": self.conn_timeout,
            "compress": self.compress,
            "port": self.port,
            "sock": self.host_proxy,
            "look_for_keys": self.look_for_keys,
            "banner_timeout": self.banner_timeout,
            "auth_timeout": self.auth_timeout,
        }

        if resolved_password:
            connect_kwargs["password"] = resolved_password.strip()

        if self.pkey:
            connect_kwargs["pkey"] = self.pkey
        if self.key_file:
            connect_kwargs["key_filename"] = self.key_file
        if self.disabled_algorithms:
            connect_kwargs["disabled_algorithms"] = self.disabled_algorithms

        if self.enable_kerberos:
            connect_kwargs.update(
                gss_auth=self.gss_auth,
                gss_kex=self.gss_kex,
                gss_deleg_creds=self.gss_deleg_creds,
                gss_host=self.gss_host or self.remote_host,
                gss_trust_dns=self.gss_trust_dns,
            )

        def log_before_sleep(retry_state) -> None:
            self.log.info(
                "Failed to connect. Sleeping before retry attempt %d",
                retry_state.attempt_number,
            )

        for attempt in Retrying(
            reraise=True,
            wait=wait_fixed(3) + wait_random(0, 2),
            stop=stop_after_attempt(3),
            before_sleep=log_before_sleep,
        ):
            with attempt:
                try:
                    self.log.info(
                        "SSH connect attempt %s to host=%s port=%s username=%s",
                        attempt.retry_state.attempt_number,
                        self.remote_host,
                        self.port,
                        resolved_username,
                    )
                    client.connect(**connect_kwargs)
                except Exception:
                    self.log.exception(
                        "SSH connect failed: host=%s port=%s username=%s kerberos=%s",
                        self.remote_host,
                        self.port,
                        resolved_username,
                        self.enable_kerberos,
                    )
                    raise

        if self.keepalive_interval:
            client.get_transport().set_keepalive(self.keepalive_interval)  # type: ignore[union-attr]

        if self.ciphers:
            client.get_transport().get_security_options().ciphers = self.ciphers  # type: ignore[union-attr]

        self.client = client
        return client


class MSSSHOperator(SSHOperator):
    def __init__(
        self,
        *,
        ssh_hook: MSSSHHook | None = None,
        ssh_conn_id: str | None = None,
        remote_host: str | None = None,
        command: str | None = None,
        conn_timeout: int | None = None,
        cmd_timeout: int | ArgNotSet | None = NOTSET,
        environment: dict | None = None,
        get_pty: bool = False,
        banner_timeout: float = 30.0,
        skip_on_exit_code=None,
        enable_kerberos: bool | None = None,
        gss_auth: bool | None = None,
        gss_kex: bool | None = None,
        gss_deleg_creds: bool | None = None,
        gss_host: str | None = None,
        gss_trust_dns: bool | None = None,
        env_username_var: str = "SSH_USERNAME",
        env_password_var: str = "SSH_PASSWORD",
        **kwargs,
    ) -> None:
        self._ms_enable_kerberos = enable_kerberos
        self._ms_gss_auth = gss_auth
        self._ms_gss_kex = gss_kex
        self._ms_gss_deleg_creds = gss_deleg_creds
        self._ms_gss_host = gss_host
        self._ms_gss_trust_dns = gss_trust_dns
        self._ms_env_username_var = env_username_var
        self._ms_env_password_var = env_password_var

        super().__init__(
            ssh_hook=ssh_hook,
            ssh_conn_id=ssh_conn_id,
            remote_host=remote_host,
            command=command,
            conn_timeout=conn_timeout,
            cmd_timeout=cmd_timeout,
            environment=environment,
            get_pty=get_pty,
            banner_timeout=banner_timeout,
            skip_on_exit_code=skip_on_exit_code,
            **kwargs,
        )

    @cached_property
    def ssh_hook(self) -> MSSSHHook:
        if self.ssh_conn_id:
            if self.remote_host is not None:
                self.log.info(
                    "remote_host is provided explicitly. "
                    "It will replace the remote_host defined in the connection."
                )

            return MSSSHHook(
                ssh_conn_id=self.ssh_conn_id,
                remote_host=self.remote_host or "",
                conn_timeout=self.conn_timeout,
                cmd_timeout=self.cmd_timeout,
                banner_timeout=self.banner_timeout,
                enable_kerberos=self._ms_enable_kerberos,
                gss_auth=self._ms_gss_auth,
                gss_kex=self._ms_gss_kex,
                gss_deleg_creds=self._ms_gss_deleg_creds,
                gss_host=self._ms_gss_host,
                gss_trust_dns=self._ms_gss_trust_dns,
                env_username_var=self._ms_env_username_var,
                env_password_var=self._ms_env_password_var,
            )

        raise AirflowException("Cannot operate without ssh_hook or ssh_conn_id.")


def execute_ssh_command(
    *,
    task_id: str,
    ssh_hook: MSSSHHook,
    command: str,
    cmd_timeout: float | ArgNotSet | None = NOTSET,
) -> str:
    context = get_current_context()
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
            f"SSH operator error: exit status = {exit_status}, "
            f"stderr = {agg_stderr.decode('utf-8', errors='replace')}"
        )

    task_instance = context.get("task_instance")
    if task_instance is not None:
        task_instance.xcom_push(key="ssh_exit", value=exit_status)

    return agg_stdout.decode("utf-8", errors="replace")


__all__ = ["MSSSHHook", "MSSSHOperator", "execute_ssh_command"]