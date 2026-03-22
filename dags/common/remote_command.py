from __future__ import annotations

import shlex
from typing import Iterable


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