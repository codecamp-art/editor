from __future__ import annotations

import re
import shlex
from typing import Iterable

SYSTEMD_UNIT_NAME_MAX_LENGTH = 180


def normalize_command_parts(
    parts: Iterable[str] | str | None,
    *,
    field_name: str = "command parts",
) -> list[str]:
    if parts is None:
        return []

    raw_parts = [parts] if isinstance(parts, str) else parts

    normalized: list[str] = []
    for part in raw_parts:
        if part is None or part == "":
            continue
        if not isinstance(part, str):
            raise TypeError(f"{field_name} must contain strings, got {type(part).__name__}.")

        try:
            normalized.extend(shlex.split(part))
        except ValueError as exc:
            raise ValueError(f"{field_name} contains invalid shell fragment: {part!r}") from exc

    return normalized


def shell_join(parts: Iterable[str]) -> str:
    return " ".join(shlex.quote(part) for part in parts if part is not None and part != "")


def split_extra_args(extra_args: str | None) -> list[str]:
    if not extra_args:
        return []
    return shlex.split(extra_args)


def build_env_exports(env_vars: dict[str, str]) -> str:
    if not env_vars:
        return ""
    return " ".join(f"{key}={shlex.quote(str(value))}" for key, value in env_vars.items())


def build_inner_command(
    *,
    command_prefix: list[str],
    app_args: list[str],
    working_dir: str | None = None,
    env_vars: dict[str, str] | None = None,
) -> str:
    command_str = shell_join([*command_prefix, *app_args])

    prefix_parts: list[str] = []
    if working_dir:
        prefix_parts.append(f"cd {shlex.quote(working_dir)}")
    env_export_str = build_env_exports(env_vars or {})
    if env_export_str:
        prefix_parts.append(env_export_str)

    if not prefix_parts:
        return command_str

    if working_dir and env_export_str:
        return f"{prefix_parts[0]} && {prefix_parts[1]} {command_str}"
    if working_dir:
        return f"{prefix_parts[0]} && {command_str}"
    return f"{env_export_str} {command_str}"


def build_sudo_bash_command(
    *,
    sudo_user: str,
    inner_command: str,
) -> str:
    return shell_join(
        [
            "sudo",
            "-iu",
            sudo_user,
            "bash",
            "-lc",
            inner_command,
        ]
    )


def build_systemd_unit_name(
    *,
    prefix: str,
    dag_id: str,
    run_id: str,
) -> str:
    raw_name = f"{prefix}-{dag_id}-{run_id}"
    safe_name = re.sub(r"[^A-Za-z0-9_.@-]", "_", raw_name).strip("._-")
    if not safe_name:
        raise ValueError("systemd unit name cannot be empty.")
    if len(safe_name) > SYSTEMD_UNIT_NAME_MAX_LENGTH:
        safe_name = safe_name[:SYSTEMD_UNIT_NAME_MAX_LENGTH].rstrip("._-")
    return f"{safe_name}.service"


def build_systemd_command(
    *,
    scope: str,
    sudo_user: str,
    executable: str,
    args: Iterable[str],
) -> str:
    if scope not in {"system", "user"}:
        raise ValueError("scope must be either 'system' or 'user'.")

    command_parts = [executable]
    if scope == "user":
        command_parts.append("--user")
    command_parts.extend(args)

    if scope == "system":
        return shell_join(["sudo", *command_parts])

    return build_sudo_bash_command(
        sudo_user=sudo_user,
        inner_command=shell_join(command_parts),
    )


def build_systemd_run_command(
    *,
    unit_name: str,
    sudo_user: str,
    inner_command: str,
    runtime_max_seconds: int | None = None,
    scope: str = "system",
) -> str:
    unit_arg = unit_name if unit_name.endswith(".service") else f"{unit_name}.service"
    if scope not in {"system", "user"}:
        raise ValueError("scope must be either 'system' or 'user'.")

    def systemctl_show(property_name: str) -> str:
        return build_systemd_command(
            scope=scope,
            sudo_user=sudo_user,
            executable="systemctl",
            args=[
                "show",
                unit_arg,
                f"--property={property_name}",
                "--value",
            ],
        )

    start_args = [
        f"--unit={unit_arg}",
        "--remain-after-exit",
    ]
    if runtime_max_seconds is not None:
        if runtime_max_seconds <= 0:
            raise ValueError("runtime_max_seconds must be a positive integer.")
        start_args.append(f"--property=RuntimeMaxSec={runtime_max_seconds}")
    if scope == "system":
        start_args.append(f"--property=User={sudo_user}")
    start_args.extend(["bash", "-lc", inner_command])

    start_command = build_systemd_command(
        scope=scope,
        sudo_user=sudo_user,
        executable="systemd-run",
        args=start_args,
    )
    stop_command = build_systemd_command(
        scope=scope,
        sudo_user=sudo_user,
        executable="systemctl",
        args=["stop", unit_arg],
    )
    reset_failed_command = build_systemd_command(
        scope=scope,
        sudo_user=sudo_user,
        executable="systemctl",
        args=["reset-failed", unit_arg],
    )
    journal_command = build_systemd_command(
        scope=scope,
        sudo_user=sudo_user,
        executable="journalctl",
        args=["-u", unit_arg, "-n", "200", "--no-pager"],
    )

    script = f"""
unit={shlex.quote(unit_arg)}
poll_interval=10

get_status() {{
  load_state=$({systemctl_show("LoadState")} 2>/dev/null || true)
  active_state=$({systemctl_show("ActiveState")} 2>/dev/null || true)
  sub_state=$({systemctl_show("SubState")} 2>/dev/null || true)
  result=$({systemctl_show("Result")} 2>/dev/null || true)
  exec_status=$({systemctl_show("ExecMainStatus")} 2>/dev/null || true)
}}

is_success_status() {{
  [ "$result" = "success" ] && {{ [ -z "$exec_status" ] || [ "$exec_status" = "0" ]; }}
}}

return_unit_failure() {{
  case "$exec_status" in
    ''|0|*[!0-9]*) return 1 ;;
    *) return "$exec_status" ;;
  esac
}}

print_unit_logs() {{
  {journal_command} || true
}}

wait_for_unit() {{
  while true; do
    get_status
    echo "systemd unit $unit: LoadState=${{load_state:-unknown}} ActiveState=${{active_state:-unknown}} SubState=${{sub_state:-unknown}} Result=${{result:-unknown}} ExecMainStatus=${{exec_status:-unknown}}"

    if [ -z "$load_state" ] || [ "$load_state" = "not-found" ]; then
      echo "systemd unit $unit is not loaded"
      return 127
    fi

    if [ "$active_state" = "active" ] && [ "$sub_state" = "exited" ]; then
      if is_success_status; then
        return 0
      fi
      print_unit_logs
      return_unit_failure
      return $?
    fi

    case "$active_state" in
      active|activating|reloading|deactivating)
        sleep "$poll_interval"
        ;;
      inactive)
        if is_success_status; then
          return 0
        fi
        print_unit_logs
        return_unit_failure
        return $?
        ;;
      failed)
        print_unit_logs
        return_unit_failure
        return $?
        ;;
      *)
        sleep "$poll_interval"
        ;;
    esac
  done
}}

load_state=$({systemctl_show("LoadState")} 2>/dev/null || true)
if [ -n "$load_state" ] && [ "$load_state" != "not-found" ]; then
  get_status
  echo "Found existing systemd unit $unit for this Airflow run_id."

  if [ "$active_state" = "failed" ]; then
    echo "Existing systemd unit $unit failed; resetting it before Airflow retry starts a new attempt."
    {stop_command} >/dev/null 2>&1 || true
    {reset_failed_command} >/dev/null 2>&1 || true
  elif [ "$active_state" = "inactive" ]; then
    if is_success_status; then
      exit 0
    fi
    echo "Existing systemd unit $unit is inactive without success; resetting it before Airflow retry starts a new attempt."
    {stop_command} >/dev/null 2>&1 || true
    {reset_failed_command} >/dev/null 2>&1 || true
  elif [ "$active_state" = "active" ] && [ "$sub_state" = "exited" ]; then
    if is_success_status; then
      exit 0
    fi
    echo "Existing systemd unit $unit exited without success; resetting it before Airflow retry starts a new attempt."
    {stop_command} >/dev/null 2>&1 || true
    {reset_failed_command} >/dev/null 2>&1 || true
  else
    wait_for_unit
    exit $?
  fi
fi

echo "Starting systemd unit $unit for this Airflow run_id."
{start_command}
start_rc=$?
if [ "$start_rc" -ne 0 ]; then
  exit "$start_rc"
fi

wait_for_unit
exit $?
""".strip()

    return shell_join(["bash", "-lc", script])
