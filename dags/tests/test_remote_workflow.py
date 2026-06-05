from __future__ import annotations

import sys
import types
import unittest
from pathlib import Path


def install_airflow_stubs() -> None:
    airflow = types.ModuleType("airflow")
    airflow_exceptions = types.ModuleType("airflow.exceptions")

    class AirflowSkipException(Exception):
        pass

    airflow_exceptions.AirflowSkipException = AirflowSkipException
    sys.modules.setdefault("airflow", airflow)
    sys.modules.setdefault("airflow.exceptions", airflow_exceptions)

    param_module = types.ModuleType("airflow.models.param")

    class Param:
        def __init__(self, *args, **kwargs):
            self.args = args
            self.kwargs = kwargs

    param_module.Param = Param
    sys.modules.setdefault("airflow.models", types.ModuleType("airflow.models"))
    sys.modules.setdefault("airflow.models.param", param_module)

    empty_module = types.ModuleType("airflow.providers.standard.operators.empty")

    class EmptyOperator:
        pass

    empty_module.EmptyOperator = EmptyOperator
    sys.modules.setdefault("airflow.providers", types.ModuleType("airflow.providers"))
    sys.modules.setdefault("airflow.providers.standard", types.ModuleType("airflow.providers.standard"))
    sys.modules.setdefault(
        "airflow.providers.standard.operators",
        types.ModuleType("airflow.providers.standard.operators"),
    )
    sys.modules.setdefault("airflow.providers.standard.operators.empty", empty_module)

    sensor_module = types.ModuleType("airflow.providers.standard.sensors.external_task")

    class ExternalTaskSensor:
        pass

    sensor_module.ExternalTaskSensor = ExternalTaskSensor
    sys.modules.setdefault(
        "airflow.providers.standard.sensors",
        types.ModuleType("airflow.providers.standard.sensors"),
    )
    sys.modules.setdefault("airflow.providers.standard.sensors.external_task", sensor_module)

    sdk_module = types.ModuleType("airflow.sdk")

    def get_current_context():
        return {}

    def task(*args, **kwargs):
        def decorator(fn):
            return fn

        return decorator

    class TaskGroup:
        pass

    sdk_module.get_current_context = get_current_context
    sdk_module.task = task
    sdk_module.TaskGroup = TaskGroup
    sys.modules.setdefault("airflow.sdk", sdk_module)

    task_group_module = types.ModuleType("airflow.utils.task_group")
    task_group_module.TaskGroup = TaskGroup
    sys.modules.setdefault("airflow.utils", types.ModuleType("airflow.utils"))
    sys.modules.setdefault("airflow.utils.task_group", task_group_module)


def install_dag_factory_stub() -> None:
    dag_factory_module = types.ModuleType("common.dag_factory")
    dag_factory_module.DEFAULT_RUNTIME_ENV_FILE = Path("dags/configs/runtime_envs.json")
    dag_factory_module.build_minimal_tenant_executor_config = lambda runtime_context: {}
    dag_factory_module.build_runtime_context = lambda owner=None, config_file=None: {
        "owner": owner or "airflow",
        "timezone": "Asia/Shanghai",
    }
    dag_factory_module.dag_decorator = lambda **kwargs: (lambda fn: fn)
    sys.modules.setdefault("common.dag_factory", dag_factory_module)


def install_ssh_hook_stub() -> None:
    ssh_hook_module = types.ModuleType("common.ssh_hook")

    class MSSSHHook:
        pass

    ssh_hook_module.MSSSHHook = MSSSHHook
    ssh_hook_module.execute_ssh_command = lambda **kwargs: "ok"
    sys.modules.setdefault("common.ssh_hook", ssh_hook_module)


install_airflow_stubs()
install_dag_factory_stub()
install_ssh_hook_stub()

from workflow.remote_workflow import (  # noqa: E402
    apply_task_env_overrides,
    apply_task_runtime_overrides,
    build_remote_task_command,
    build_workflow_definition_from_config,
    default_workflow_id_from_path,
    prepare_workflow_plan,
    register_workflow_dags_from_json_dir,
    resolve_hosts_for_task,
    resolve_topology_for_env,
)


def workflow_plan_for_env(workflow, env: str):
    return prepare_workflow_plan(
        workflow=workflow,
        topology=resolve_topology_for_env(workflow, env),
        current_env=env,
    )


class RemoteWorkflowTest(unittest.TestCase):
    def test_host_group_environment_overrides_runtime_fields(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "gateway": {
                        "type": "systemd",
                        "platform": "rhel8",
                        "service_name": "gateway.service",
                        "environments": {
                            "qa": {
                                "platform": "rhel7",
                                "service_name": "gateway-qa.service",
                                "hosts": ["qa-gw-01.company.net"],
                            }
                        },
                    },
                    "risk": {
                        "type": "linux_script",
                        "working_dir": "/opt/risk/current",
                        "commands": {
                            "start": "./risk start",
                            "stop": "./risk stop",
                            "status": "./risk status",
                        },
                        "environments": {
                            "qa": {
                                "working_dir": "/opt/risk/qa/current",
                                "commands": {
                                    "start": "./risk start --env qa",
                                },
                                "hosts": ["qa-risk-01.company.net"],
                            }
                        },
                    },
                },
                "tasks": [
                    {"task_id": "gateway", "host_group": "gateway"},
                    {"task_id": "risk", "host_group": "risk"},
                ],
            },
            default_workflow_id="example",
        )
        topology = resolve_topology_for_env(workflow, "qa")
        tokens = {"env": "qa", "loc": "qa"}

        gateway_hosts = resolve_hosts_for_task(
            topology=topology,
            task_spec=workflow.tasks[0],
            current_env="qa",
            tokens=tokens,
        )
        self.assertEqual(
            gateway_hosts[0].task_overrides["systemd"]["service_name"],
            "gateway-qa.service",
        )
        self.assertEqual(gateway_hosts[0].task_overrides["systemd"]["platform"], "rhel7")
        self.assertEqual(gateway_hosts[0].task_overrides["platform"], "rhel7")

        risk_hosts = resolve_hosts_for_task(
            topology=topology,
            task_spec=workflow.tasks[1],
            current_env="qa",
            tokens=tokens,
        )
        self.assertEqual(risk_hosts[0].task_overrides["working_dir"], "/opt/risk/qa/current")
        self.assertEqual(
            risk_hosts[0].task_overrides["commands"],
            {
                "start": "./risk start --env qa",
                "stop": "./risk stop",
                "status": "./risk status",
            },
        )

    def test_task_env_overrides_support_service_name_shortcut(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "gateway": {
                        "hosts": ["qa-gw-01.company.net"],
                    },
                },
                "tasks": [
                    {
                        "task_id": "gateway",
                        "host_group": "gateway",
                        "systemd": {
                            "service_name": "gateway.service",
                        },
                        "env_overrides": {
                            "qa": {
                                "platform": "rhel7",
                                "service_name": "gateway-qa.service",
                            },
                        },
                    },
                ],
            },
            default_workflow_id="example",
        )

        task_spec = apply_task_env_overrides(
            workflow.tasks[0],
            current_env="qa",
            tokens={"env": "qa", "loc": "qa"},
        )

        self.assertEqual(task_spec.platform, "rhel7")
        self.assertEqual(task_spec.systemd["platform"], "rhel7")
        self.assertEqual(task_spec.systemd["service_name"], "gateway-qa.service")

    def test_run_workflow_uses_single_run_action_and_depends_on_graph(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "actions": ["run"],
                "schedules": {
                    "run": "15 9 * * 1-5",
                },
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./bin/run-job --env qa",
                        },
                    },
                },
                "tasks": [
                    {"task_id": "extract", "host_group": "batch"},
                    {
                        "task_id": "publish",
                        "host_group": "batch",
                        "depends_on": ["extract"],
                    },
                ],
            },
            default_workflow_id="daily_batch",
        )

        self.assertEqual(workflow.actions, ("run",))
        self.assertEqual(workflow.schedule_run, "15 9 * * 1-5")

        plan = workflow_plan_for_env(workflow, "qa")

        self.assertEqual(
            plan.run_upstream_task_ids,
            {
                "extract": (),
                "publish": ("extract",),
            },
        )

        command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                plan.tasks[0],
                plan.hosts_by_task_id["extract"][0].task_overrides,
            ),
            host_target=plan.hosts_by_task_id["extract"][0],
            action="run",
        )

        self.assertEqual(command, "bash -lc './bin/run-job --env qa'")

    def test_register_run_workflow_from_nested_json_creates_one_dag(self) -> None:
        import json
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        config = {
            "actions": ["run"],
            "schedules": {
                "run": "15 9 * * 1-5",
            },
            "host_groups": {
                "batch": {
                    "type": "linux_script",
                    "hosts": ["qa-batch-01.company.net"],
                    "commands": {
                        "run": "./bin/run-job --env qa",
                    },
                },
            },
            "tasks": [
                {"task_id": "extract", "host_group": "batch"},
            ],
        }

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            nested_dir = root / "team-a" / "batch"
            nested_dir.mkdir(parents=True)
            (nested_dir / "daily.json").write_text(json.dumps(config), encoding="utf-8")
            registered = {}

            with patch(
                "workflow.remote_workflow.create_workflow_dag",
                side_effect=lambda **kwargs: {
                    "dag_id": kwargs["dag_id"],
                    "action": kwargs["action"],
                    "schedule": kwargs["schedule"],
                },
            ):
                with patch("workflow.remote_workflow.get_current_env_name", return_value="qa"):
                    register_workflow_dags_from_json_dir(
                        config_dir=root,
                        global_namespace=registered,
                    )

            self.assertEqual(sorted(registered), ["team_a_batch_daily_run"])
            self.assertEqual(
                registered["team_a_batch_daily_run"],
                {
                    "dag_id": "team-a-batch-daily",
                    "action": "run",
                    "schedule": "15 9 * * 1-5",
                },
            )
            self.assertEqual(
                default_workflow_id_from_path(nested_dir / "daily.json", root),
                "team_a_batch_daily",
            )

    def test_start_stop_schedule_keeps_start_stop_actions(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "schedules": {
                    "start": "0 7 * * 1-5",
                    "stop": "0 19 * * 1-5",
                },
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./bin/run-job --env qa",
                            "start": "./bin/start-job --env qa",
                            "stop": "./bin/stop-job --env qa",
                            "status": "./bin/status-job --env qa",
                        },
                    },
                },
                "tasks": [
                    {"task_id": "batch", "host_group": "batch"},
                ],
            },
            default_workflow_id="start_stop_batch",
        )

        self.assertEqual(workflow.actions, ("start", "stop"))


if __name__ == "__main__":
    unittest.main()
