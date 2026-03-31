from __future__ import annotations

import importlib
import sys
import types
import unittest
from pathlib import Path


def _stub_module(name: str, **attrs) -> None:
    module = types.ModuleType(name)
    for key, value in attrs.items():
        setattr(module, key, value)
    sys.modules[name] = module


def _load_reporting_workflow():
    repo_root = Path(__file__).resolve().parents[2]
    dags_path = str(repo_root / "dags")
    if dags_path not in sys.path:
        sys.path.insert(0, dags_path)

    _stub_module("airflow")
    _stub_module(
        "airflow.sdk",
        get_current_context=lambda: {},
        task=lambda *args, **kwargs: (lambda f: f),
    )
    _stub_module("common.config_loader", get_current_env_name=lambda: "dev")
    _stub_module(
        "common.dag_factory",
        DEFAULT_RUNTIME_ENV_FILE="runtime_envs.json",
        build_minimal_tenant_executor_config=lambda *_args, **_kwargs: {},
        build_runtime_context=lambda *_args, **_kwargs: {
            "owner": "owner",
            "timezone": "UTC",
            "target_host": "localhost",
        },
        dag_decorator=lambda **_kwargs: (lambda fn: fn),
    )
    _stub_module(
        "common.field_schema",
        COMMON_FIELDS={},
        build_airflow_params_from_fields=lambda _fields: {},
        merge_field_definitions=lambda *items: {k: v for d in items for k, v in d.items()},
        validate_fields=lambda raw, _fields: raw,
    )
    _stub_module(
        "common.remote_command",
        build_inner_command=lambda **_kwargs: "",
        build_sudo_bash_command=lambda **_kwargs: "",
        split_extra_args=lambda _value: [],
    )
    _stub_module("common.ssh_utils", execute_ssh_command=lambda **_kwargs: "")
    _stub_module("common.trading_calendar", TradingDayCheckDefinition=object)
    _stub_module(
        "common.trading_day_tasks",
        build_trading_day_check_task=lambda **_kwargs: (lambda: None),
    )

    return importlib.import_module("common.reporting_workflow")


class ReportingWorkflowPrefixMergeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = _load_reporting_workflow()

    def _base_definition(self, remote_command_prefix):
        return self.module.ReportingDefinition(
            report_id="report",
            dag_id="report-dag",
            title="Report",
            description="desc",
            schedule=None,
            remote_script=None,
            remote_command_prefix=remote_command_prefix,
            sudo_user="user",
            working_dir=None,
            fields={},
            adhoc_rules={},
        )

    def test_uses_explicit_remote_command_prefix_when_present(self):
        definition = self._base_definition(["java", "-jar", "base.jar"])
        variant = self.module.ReportingScheduleVariant(dag_id="v1", title_suffix="v1")
        result = self.module.resolve_remote_command_prefix_for_variant(
            base_definition=definition,
            variant=variant,
            env_override={"remote_command_prefix": ["java", "-jar", "env.jar"]},
        )
        self.assertEqual(["java", "-jar", "env.jar"], result)

    def test_combines_base_and_append(self):
        definition = self._base_definition(None)
        variant = self.module.ReportingScheduleVariant(
            dag_id="v1",
            title_suffix="v1",
            remote_command_prefix_base=["java", "-jar", "report.jar"],
            remote_command_prefix_append=["--profile=qa"],
        )
        result = self.module.resolve_remote_command_prefix_for_variant(
            base_definition=definition,
            variant=variant,
            env_override={},
        )
        self.assertEqual(["java", "-jar", "report.jar", "--profile=qa"], result)

    def test_env_append_overrides_variant_append(self):
        definition = self._base_definition(None)
        variant = self.module.ReportingScheduleVariant(
            dag_id="v1",
            title_suffix="v1",
            remote_command_prefix_base=["java", "-jar", "report.jar"],
            remote_command_prefix_append=["--profile=qa"],
        )
        result = self.module.resolve_remote_command_prefix_for_variant(
            base_definition=definition,
            variant=variant,
            env_override={"remote_command_prefix_append": ["--profile=prod"]},
        )
        self.assertEqual(["java", "-jar", "report.jar", "--profile=prod"], result)

    def test_rejects_mixing_explicit_prefix_and_base_append(self):
        definition = self._base_definition(["java", "-jar", "base.jar"])
        variant = self.module.ReportingScheduleVariant(
            dag_id="v1",
            title_suffix="v1",
            remote_command_prefix_append=["--x=y"],
        )
        with self.assertRaises(ValueError):
            self.module.resolve_remote_command_prefix_for_variant(
                base_definition=definition,
                variant=variant,
                env_override={},
            )

    def test_rejects_append_without_base(self):
        definition = self._base_definition(None)
        variant = self.module.ReportingScheduleVariant(
            dag_id="v1",
            title_suffix="v1",
            remote_command_prefix_append=["--x=y"],
        )
        with self.assertRaises(ValueError):
            self.module.resolve_remote_command_prefix_for_variant(
                base_definition=definition,
                variant=variant,
                env_override={},
            )


if __name__ == "__main__":
    unittest.main()
