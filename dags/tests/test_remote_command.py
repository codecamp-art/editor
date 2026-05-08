from __future__ import annotations

import unittest

from common.remote_command import (
    build_inner_command,
    build_systemd_run_command,
    build_systemd_unit_name,
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

    def test_build_systemd_unit_name_sanitizes_airflow_run_id(self) -> None:
        self.assertEqual(
            build_systemd_unit_name(
                prefix="reporting",
                dag_id="tds-reporter-daily",
                run_id="scheduled__2026-05-07T22:00:00+08:00",
            ),
            "reporting-tds-reporter-daily-scheduled__2026-05-07T22_00_00_08_00.service",
        )

    def test_build_systemd_run_command_for_system_scope(self) -> None:
        command = build_systemd_run_command(
            unit_name="reporting-tds.service",
            sudo_user="reportuser_qa",
            inner_command="cd /opt/reporting/tds-reporter && java -jar tds-reporter.jar",
            runtime_max_seconds=7200,
            scope="system",
        )

        self.assertTrue(command.startswith("bash -lc "))
        self.assertIn(
            "sudo systemctl show reporting-tds.service --property=LoadState --value",
            command,
        )
        self.assertIn("Found existing systemd unit", command)
        self.assertIn("sudo systemctl reset-failed reporting-tds.service", command)
        self.assertIn(
            "sudo systemd-run --unit=reporting-tds.service --remain-after-exit "
            "--property=RuntimeMaxSec=7200 --property=User=reportuser_qa bash -lc",
            command,
        )
        self.assertNotIn("--wait", command)
        self.assertNotIn("--collect", command)

    def test_build_systemd_run_command_for_user_scope(self) -> None:
        command = build_systemd_run_command(
            unit_name="reporting-tds",
            sudo_user="reportuser_qa",
            inner_command="java -jar tds-reporter.jar",
            runtime_max_seconds=7200,
            scope="user",
        )

        self.assertIn(
            "systemctl --user show reporting-tds.service --property=LoadState --value",
            command,
        )
        self.assertIn(
            "systemd-run --user --unit=reporting-tds.service --remain-after-exit "
            "--property=RuntimeMaxSec=7200 bash -lc",
            command,
        )
        self.assertIn(
            "journalctl --user -u reporting-tds.service -n 200 --no-pager",
            command,
        )


if __name__ == "__main__":
    unittest.main()
