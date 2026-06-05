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
    build_workflow_definition_from_config,
    resolve_hosts_for_task,
    resolve_topology_for_env,
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


if __name__ == "__main__":
    unittest.main()
