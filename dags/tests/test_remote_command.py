from __future__ import annotations

import unittest

from common.remote_command import (
    build_inner_command,
    build_sudo_bash_command,
    normalize_command_parts,
)


class RemoteCommandTest(unittest.TestCase):
    def test_normalize_command_parts_splits_shell_fragments(self) -> None:
        self.assertEqual(
            normalize_command_parts(["bin/report", "--env qa"]),
            ["bin/report", "--env", "qa"],
        )

    def test_normalize_command_parts_preserves_quoted_spaces(self) -> None:
        self.assertEqual(
            normalize_command_parts(["--subject 'Daily QA Report'"]),
            ["--subject", "Daily QA Report"],
        )

    def test_normalized_env_flag_does_not_create_nested_inner_quotes(self) -> None:
        command_prefix = normalize_command_parts(["bin/report", "--env qa"])

        inner_command = build_inner_command(
            command_prefix=command_prefix,
            app_args=["--report-time=08:30"],
            working_dir="/opt/reporting/tds-reporter",
        )
        command = build_sudo_bash_command(
            sudo_user="reportuser_qa",
            inner_command=inner_command,
        )

        self.assertEqual(
            inner_command,
            "cd /opt/reporting/tds-reporter && bin/report --env qa --report-time=08:30",
        )
        self.assertEqual(
            command,
            "sudo -iu reportuser_qa bash -lc "
            "'cd /opt/reporting/tds-reporter && bin/report --env qa --report-time=08:30'",
        )


if __name__ == "__main__":
    unittest.main()
