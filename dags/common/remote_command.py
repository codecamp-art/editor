from __future__ import annotations

import shlex
from typing import Iterable


def shell_join(parts: Iterable[str]) -> str:
    return " ".join(shlex.quote(part) for part in parts if part is not None and part != "")


def split_extra_args(extra_args: str | None) -> list[str]:
    if not extra_args:
        return []
    return shlex.split(extra_args)


def build_sudo_bash_command(
    *,
    sudo_user: str,
    script_and_args: list[str],
) -> str:
    script_command = shell_join(script_and_args)

    return shell_join(
        [
            "sudo",
            "-iu",
            sudo_user,
            "bash",
            "-lc",
            script_command,
        ]
    )