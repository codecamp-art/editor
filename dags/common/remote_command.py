from __future__ import annotations

import shlex
from typing import Iterable


def shell_join(parts: Iterable[str]) -> str:
    return " ".join(shlex.quote(part) for part in parts if part is not None and part != "")


def split_extra_args(extra_args: str | None) -> list[str]:
    if not extra_args:
        return []
    return shlex.split(extra_args)


def build_inner_command(
    *,
    command_prefix: list[str],
    app_args: list[str],
    working_dir: str | None = None,
) -> str:
    command_str = shell_join([*command_prefix, *app_args])

    if working_dir:
        return f"cd {shlex.quote(working_dir)} && {command_str}"

    return command_str


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