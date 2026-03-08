from __future__ import annotations

try:
    # Replace this with your real Kerberos-enabled SSH hook
    from my_company.airflow.hooks.ms_ssh import MSSSHHook  # type: ignore
except ImportError as exc:
    raise ImportError(
        "Unable to import MSSSHHook. Update dags/common/ssh_hook.py "
        "to point to your actual Kerberos-enabled SSH hook implementation."
    ) from exc


__all__ = ["MSSSHHook"]