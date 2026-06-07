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
    build_script_airflow_fields,
    build_workflow_definition_from_config,
    default_workflow_id_from_path,
    prepare_workflow_plan,
    register_workflow_dags_from_json,
    register_workflow_dags_from_json_dir,
    resolved_remote_execution_mode,
    resolve_hosts_for_task,
    resolve_topology_for_env,
    task_retry_kwargs,
    trigger_rule_satisfied,
    validate_task_spec,
)
try:
    from .remote_workflow_graph import (  # noqa: E402
        workflow_graph_svgs,
        workflow_plan_to_svg,
    )
    from .remote_workflow_validation import validate_workflow_json_file  # noqa: E402
except ImportError:  # pragma: no cover - supports direct test file execution.
    from remote_workflow_graph import (  # noqa: E402
        workflow_graph_svgs,
        workflow_plan_to_svg,
    )
    from remote_workflow_validation import validate_workflow_json_file  # noqa: E402


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

    def test_host_group_targets_can_be_composed_into_task_group(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "shared_server": {
                        "platform": "rhel8",
                        "services": [
                            {
                                "id": "gateway",
                                "service_name": "gateway.service",
                                "sudo_user": "gateway_base",
                            },
                            {
                                "id": "pricing",
                                "service_name": "pricing.service",
                                "start_depends_on": ["gateway"],
                            },
                        ],
                        "scripts": [
                            {
                                "id": "cache_warmup",
                                "type": "linux_script",
                                "commands": {
                                    "start": "./cache start",
                                    "stop": "./cache stop",
                                    "status": "./cache status",
                                },
                            },
                        ],
                        "environments": {
                            "prod": {
                                "sudo_user": "shared_prod",
                                "commands": {
                                    "start": "./cache start --prod",
                                    "stop": "./cache stop --prod",
                                    "status": "./cache status --prod",
                                },
                                "hosts": [
                                    {
                                        "host": "prod-shared-01.company.net",
                                        "host_id": "shared_prod_01",
                                    }
                                ],
                                "services": [
                                    {
                                        "id": "pricing",
                                        "sudo_user": "pricing_prod",
                                    }
                                ],
                                "scripts": [
                                    {
                                        "id": "cache_warmup",
                                        "working_dir": "/opt/cache/prod/current",
                                    }
                                ],
                            },
                            "dr": {
                                "sudo_user": "shared_dr",
                                "hosts": [
                                    {
                                        "host": "dr-shared-01.company.net",
                                        "host_id": "shared_dr_01",
                                    }
                                ],
                                "services": [
                                    {
                                        "id": "pricing",
                                        "sudo_user": "pricing_dr",
                                    },
                                    {
                                        "id": "dr_only",
                                        "service_name": "dr-only.service",
                                        "sudo_user": "drsvc",
                                    },
                                ],
                                "scripts": [
                                    {
                                        "id": "cache_warmup",
                                        "working_dir": "/opt/cache/dr/current",
                                    },
                                    {
                                        "id": "dr_replay_check",
                                        "type": "linux_script",
                                        "sudo_user": "replayops",
                                        "working_dir": "/opt/replay/dr/current",
                                        "commands": {
                                            "start": "./replay-check start",
                                            "stop": "./replay-check stop",
                                            "status": "./replay-check status",
                                        },
                                    }
                                ],
                            },
                        },
                    },
                },
                "task_groups": [
                    {
                        "group_id": "shared_server_services",
                        "tooltip": "Services and scripts on one server",
                        "tasks": [
                            {
                                "targets": [
                                    "gateway",
                                    "pricing",
                                    "cache_warmup",
                                    "dr_only",
                                    "dr_replay_check",
                                ],
                            }
                        ],
                    }
                ],
            },
            default_workflow_id="shared",
        )

        self.assertEqual(len(workflow.task_groups), 1)
        group = workflow.task_groups[0]
        self.assertEqual(group.group_id, "shared_server_services")
        self.assertEqual(
            tuple(task.task_id for task in group.tasks),
            ("gateway", "pricing", "cache_warmup", "dr_only", "dr_replay_check"),
        )

        plan = workflow_plan_for_env(workflow, "prod")
        self.assertEqual(
            plan.start_upstream_task_ids,
            {
                "gateway": (),
                "pricing": ("gateway",),
                "cache_warmup": (),
            },
        )
        self.assertNotIn("dr_only", plan.hosts_by_task_id)

        gateway_task = next(task for task in plan.tasks if task.task_id == "gateway")
        gateway_host = plan.hosts_by_task_id["gateway"][0]
        gateway_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                gateway_task,
                gateway_host.task_overrides,
            ),
            host_target=gateway_host,
            action="start",
        )
        self.assertIn("gateway.service", gateway_command)
        self.assertIn("start gateway.service", gateway_command)
        self.assertIn("is-active --quiet gateway.service", gateway_command)
        self.assertIn("shared_prod", gateway_command)
        self.assertNotIn("gateway_base", gateway_command)
        gateway_stop_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                gateway_task,
                gateway_host.task_overrides,
            ),
            host_target=gateway_host,
            action="stop",
        )
        self.assertIn("stop gateway.service", gateway_stop_command)
        self.assertIn("! systemctl --user is-active --quiet gateway.service", gateway_stop_command)

        pricing_task = next(task for task in plan.tasks if task.task_id == "pricing")
        pricing_host = plan.hosts_by_task_id["pricing"][0]
        pricing_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                pricing_task,
                pricing_host.task_overrides,
            ),
            host_target=pricing_host,
            action="start",
        )
        self.assertIn("pricing.service", pricing_command)
        self.assertIn("pricing_prod", pricing_command)

        cache_task = next(task for task in plan.tasks if task.task_id == "cache_warmup")
        cache_host = plan.hosts_by_task_id["cache_warmup"][0]
        cache_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                cache_task,
                cache_host.task_overrides,
            ),
            host_target=cache_host,
            action="start",
        )
        self.assertIn("./cache start --prod", cache_command)
        self.assertIn("shared_prod", cache_command)
        self.assertIn("/opt/cache/prod/current", cache_command)

        dr_plan = workflow_plan_for_env(workflow, "dr")
        self.assertIn("dr_only", dr_plan.hosts_by_task_id)
        self.assertIn("dr_replay_check", dr_plan.hosts_by_task_id)
        dr_only_task = next(task for task in dr_plan.tasks if task.task_id == "dr_only")
        dr_only_host = dr_plan.hosts_by_task_id["dr_only"][0]
        dr_only_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                dr_only_task,
                dr_only_host.task_overrides,
            ),
            host_target=dr_only_host,
            action="start",
        )
        self.assertIn("dr-only.service", dr_only_command)
        self.assertIn("drsvc", dr_only_command)

        dr_cache_task = next(task for task in dr_plan.tasks if task.task_id == "cache_warmup")
        dr_cache_host = dr_plan.hosts_by_task_id["cache_warmup"][0]
        dr_cache_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                dr_cache_task,
                dr_cache_host.task_overrides,
            ),
            host_target=dr_cache_host,
            action="start",
        )
        self.assertIn("/opt/cache/dr/current", dr_cache_command)
        self.assertIn("shared_dr", dr_cache_command)

        dr_replay_task = next(task for task in dr_plan.tasks if task.task_id == "dr_replay_check")
        dr_replay_host = dr_plan.hosts_by_task_id["dr_replay_check"][0]
        dr_replay_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                dr_replay_task,
                dr_replay_host.task_overrides,
            ),
            host_target=dr_replay_host,
            action="start",
        )
        self.assertIn("./replay-check start", dr_replay_command)
        self.assertIn("replayops", dr_replay_command)

    def test_host_group_targets_render_host_variables_and_layered_overrides(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "sybase_drtp": {
                        "sudo_user": "top_{host_id}",
                        "platform": "rhel8",
                        "working_dir": "/opt/base",
                        "remote_env_vars": {"LEVEL": "base"},
                        "services": [
                            {
                                "id": "sybase_drtp",
                                "service_name": "sybase_drtp.service",
                            },
                            {
                                "id": "sybase_dms",
                                "service_name": "sybase_dms.service",
                            },
                            {
                                "id": "risksvr",
                                "service_name": "risksvr.service",
                            },
                            {
                                "id": "monsvr",
                                "service_name": "monsvr.service",
                            },
                            {
                                "id": "zk_{host_id}_{zk_name}",
                                "sudo_user": "zk_{host_id}",
                                "service_name": "zk_{host_id}_{zk_name}.service",
                            },
                        ],
                        "environments": {
                            "qa": {
                                "sudo_user": "qa_{host_id}",
                                "platform": "rhel7",
                                "working_dir": "/opt/qa",
                                "remote_env_vars": {"LEVEL": "qa"},
                                "hosts": [
                                    {
                                        "host": "qa-db-01.company.net",
                                        "host_id": "1",
                                        "variables": {"zk_name": "alpha"},
                                    },
                                    {
                                        "host": "qa-db-02.company.net",
                                        "host_id": "2",
                                        "variables": {"zk_name": "beta"},
                                        "sudo_user": "host_{host_id}",
                                        "platform": "rhel8",
                                        "working_dir": "/opt/host_{host_id}",
                                        "remote_env_vars": {"HOST": "host_{host_id}"},
                                    },
                                ],
                                "services": [
                                    {
                                        "id": "zk_{host_id}_{zk_name}",
                                        "service_name": "zk_{host_id}_{zk_name}_qa.service",
                                    },
                                ],
                            },
                        },
                    },
                },
                "tasks": [
                    {
                        "targets": [
                            "sybase_drtp",
                            "sybase_dms",
                            "risksvr",
                            "monsvr",
                            "zk_1_alpha",
                            "zk_2_beta",
                        ],
                    }
                ],
            },
            default_workflow_id="sybase",
        )

        plan = workflow_plan_for_env(workflow, "qa")

        self.assertEqual(
            tuple(task.task_id for task in plan.tasks),
            (
                "sybase_drtp",
                "sybase_dms",
                "risksvr",
                "monsvr",
                "zk_1_alpha",
                "zk_2_beta",
            ),
        )
        self.assertEqual(
            sum(len(hosts) for hosts in plan.hosts_by_task_id.values()),
            10,
        )

        sybase_task = next(task for task in plan.tasks if task.task_id == "sybase_drtp")
        sybase_hosts = plan.hosts_by_task_id["sybase_drtp"]
        sybase_host_one = apply_task_runtime_overrides(
            sybase_task,
            sybase_hosts[0].task_overrides,
        )
        sybase_host_two = apply_task_runtime_overrides(
            sybase_task,
            sybase_hosts[1].task_overrides,
        )

        self.assertEqual(sybase_host_one.sudo_user, "qa_1")
        self.assertEqual(sybase_host_one.platform, "rhel7")
        self.assertEqual(sybase_host_one.working_dir, "/opt/qa")
        self.assertEqual(sybase_host_two.sudo_user, "host_2")
        self.assertEqual(sybase_host_two.platform, "rhel8")
        self.assertEqual(sybase_host_two.working_dir, "/opt/host_2")
        self.assertEqual(sybase_host_two.remote_env_vars["HOST"], "host_2")

        sybase_host_one_command = build_remote_task_command(
            task_spec=sybase_host_one,
            host_target=sybase_hosts[0],
            action="start",
        )
        sybase_host_two_command = build_remote_task_command(
            task_spec=sybase_host_two,
            host_target=sybase_hosts[1],
            action="start",
        )

        self.assertIn("qa_1", sybase_host_one_command)
        self.assertIn("host_2", sybase_host_two_command)

        zk_two_task = next(task for task in plan.tasks if task.task_id == "zk_2_beta")
        zk_two_host = plan.hosts_by_task_id["zk_2_beta"][0]
        zk_two_effective = apply_task_runtime_overrides(
            zk_two_task,
            zk_two_host.task_overrides,
        )

        self.assertEqual(zk_two_effective.sudo_user, "host_2")
        self.assertEqual(zk_two_effective.systemd["service_name"], "zk_2_beta_qa.service")
        self.assertEqual(zk_two_effective.platform, "rhel8")
        self.assertEqual(zk_two_effective.working_dir, "/opt/host_2")

    def test_targets_expand_to_selectable_task_ids_in_tasks_and_task_groups(self) -> None:
        task_group_workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "zookeeper": {
                        "services": [
                            {"id": "zk_1", "service_name": "zk-1.service"},
                            {"id": "zk_2", "service_name": "zk-2.service"},
                            {"id": "zk_3", "service_name": "zk-3.service"},
                        ],
                        "environments": {
                            "qa": {"hosts": ["qa-zk-01.company.net"]},
                        },
                    },
                },
                "task_groups": [
                    {
                        "group_id": "zk",
                        "tasks": [
                            {
                                "task_id": "zk",
                                "targets": ["zk_1", "zk_2", "zk_3"],
                            }
                        ],
                    }
                ],
            },
            default_workflow_id="zk",
        )

        self.assertEqual(
            tuple(task.task_id for task in task_group_workflow.task_groups[0].tasks),
            ("zk_1", "zk_2", "zk_3"),
        )

        top_level_workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "risk": {
                        "services": [
                            {"id": "risk_a", "service_name": "risk-a.service"},
                            {"id": "risk_b", "service_name": "risk-b.service"},
                        ],
                        "environments": {
                            "qa": {"hosts": ["qa-risk-01.company.net"]},
                        },
                    },
                },
                "tasks": [
                    {
                        "task_id": "risk",
                        "targets": ["risk_a", "risk_b"],
                    }
                ],
            },
            default_workflow_id="risk",
        )

        self.assertEqual(
            tuple(task.task_id for task in top_level_workflow.tasks),
            ("risk_a", "risk_b"),
        )

        same_name_workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "risk": {
                        "services": [
                            {"id": "risk_a", "service_name": "risk-a.service"},
                        ],
                        "environments": {
                            "qa": {"hosts": ["qa-risk-01.company.net"]},
                        },
                    },
                },
                "tasks": [
                    {
                        "task_id": "risk_a",
                        "target": "risk_a",
                    }
                ],
            },
            default_workflow_id="risk_single",
        )

        self.assertEqual(same_name_workflow.tasks[0].task_id, "risk_a")

    def test_missing_target_error_includes_scope_and_candidates(self) -> None:
        with self.assertRaises(ValueError) as raised:
            build_workflow_definition_from_config(
                {
                    "host_groups": {
                        "trading": {
                            "services": [
                                {
                                    "id": "tr_mng",
                                    "service_name": "tr-mng.service",
                                },
                            ],
                            "environments": {
                                "qa": {"hosts": ["qa-trading-01.company.net"]},
                            },
                        },
                    },
                    "task_groups": [
                        {
                            "group_id": "grp_trading1",
                            "tasks": [
                                {
                                    "target": "tr_mng1",
                                },
                            ],
                        },
                    ],
                },
                default_workflow_id="bad_target",
            )

        message = str(raised.exception)
        self.assertIn("task_groups[0]('grp_trading1').tasks[0]", message)
        self.assertIn("target 'tr_mng1'", message)
        self.assertIn("Did you mean: tr_mng", message)
        self.assertIn("Available target ids include: tr_mng", message)

    def test_duplicate_group_id_error_includes_locations(self) -> None:
        with self.assertRaises(ValueError) as raised:
            build_workflow_definition_from_config(
                {
                    "host_groups": {
                        "zookeeper": {
                            "services": [
                                {"id": "zk_1", "service_name": "zk-1.service"},
                            ],
                            "environments": {
                                "qa": {"hosts": ["qa-zk-01.company.net"]},
                            },
                        },
                    },
                    "task_groups": [
                        {"group_id": "zk", "tasks": [{"target": "zk_1"}]},
                        {"group_id": "zk", "tasks": [{"target": "zk_1"}]},
                    ],
                },
                default_workflow_id="duplicate_group",
            )

        message = str(raised.exception)
        self.assertIn("Duplicate task_group group_id 'zk'", message)
        self.assertIn("task_groups[1]", message)
        self.assertIn("task_groups[0]", message)

    def test_duplicate_task_id_error_includes_locations(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "shared": {
                        "services": [
                            {"id": "shared_service", "service_name": "shared.service"},
                        ],
                        "environments": {
                            "qa": {"hosts": ["qa-shared-01.company.net"]},
                        },
                    },
                },
                "task_groups": [
                    {
                        "group_id": "first",
                        "tasks": [{"target": "shared_service"}],
                    },
                    {
                        "group_id": "second",
                        "tasks": [{"target": "shared_service"}],
                    },
                ],
            },
            default_workflow_id="duplicate_task",
        )

        with self.assertRaises(ValueError) as raised:
            workflow_plan_for_env(workflow, "qa")

        message = str(raised.exception)
        self.assertIn("Duplicate task_id 'shared_service'", message)
        self.assertIn("task_groups[1]('second').tasks[0]", message)
        self.assertIn("task_groups[0]('first').tasks[0]", message)

    def test_missing_dependency_error_includes_task_and_candidates(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "risk": {
                        "services": [
                            {"id": "risk_a", "service_name": "risk-a.service"},
                        ],
                        "environments": {
                            "qa": {"hosts": ["qa-risk-01.company.net"]},
                        },
                    },
                },
                "tasks": [
                    {
                        "target": "risk_a",
                        "depends_on": ["grp_dbx"],
                    },
                ],
                "task_groups": [
                    {
                        "group_id": "grp_db",
                        "tasks": [],
                    }
                ],
            },
            default_workflow_id="missing_dependency",
        )

        with self.assertRaises(ValueError) as raised:
            workflow_plan_for_env(workflow, "qa")

        message = str(raised.exception)
        self.assertIn("Task 'risk_a' depends on 'grp_dbx'", message)
        self.assertIn("Did you mean: grp_db", message)

    def test_trigger_rule_can_be_configured_and_overridden(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./run.sh",
                        },
                    },
                },
                "tasks": [
                    {"task_id": "extract", "host_group": "batch"},
                    {
                        "task_id": "publish",
                        "host_group": "batch",
                        "depends_on": ["extract"],
                        "trigger_rule": "none_failed",
                        "env_overrides": {
                            "qa": {
                                "trigger_rule": "all_done",
                            },
                        },
                    },
                ],
                "actions": ["run"],
            },
            default_workflow_id="trigger_rules",
        )

        plan = workflow_plan_for_env(workflow, "qa")
        publish = next(task for task in plan.tasks if task.task_id == "publish")

        self.assertEqual(publish.trigger_rule, "all_done")
        self.assertTrue(trigger_rule_satisfied("none_failed", ("success", "skipped")))
        self.assertFalse(trigger_rule_satisfied("all_success", ("success", "skipped")))
        self.assertTrue(trigger_rule_satisfied("all_done", ("success", "failed")))

    def test_default_runtime_uses_non_interactive_systemd_run_for_linux(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "actions": ["run"],
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "sudo_user": "batch",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./run.sh",
                        },
                    },
                },
                "tasks": [
                    {"task_id": "extract", "host_group": "batch"},
                ],
            },
            default_workflow_id="runtime_defaults",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        task = apply_task_runtime_overrides(
            plan.tasks[0],
            plan.hosts_by_task_id["extract"][0].task_overrides,
        )

        command = build_remote_task_command(
            task_spec=task,
            host_target=plan.hosts_by_task_id["extract"][0],
            action="run",
            dag_id="daily-batch",
            run_id="manual__2026-06-07T10:00:00",
            default_timeout_seconds=1800,
        )

        self.assertEqual(task.sudo_mode, "non_interactive")
        self.assertEqual(task.retry_count, 2)
        self.assertEqual(task.retry_delay_seconds, 10)
        self.assertEqual(resolved_remote_execution_mode(task), "systemd_run")
        self.assertIn("systemd-run --user", command)
        self.assertIn("sudo -n -H -u batch", command)
        self.assertNotIn("--property=User=batch", command)
        self.assertIn("--property=RuntimeMaxSec=1800", command)
        self.assertIn("./run.sh", command)

    def test_missing_runtime_defaults_uses_builtin_defaults(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "actions": ["run"],
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "sudo_user": "batch",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./run.sh",
                        },
                    },
                },
                "tasks": [
                    {"task_id": "extract", "host_group": "batch"},
                ],
            },
            default_workflow_id="missing_runtime_defaults",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        task = apply_task_runtime_overrides(
            plan.tasks[0],
            plan.hosts_by_task_id["extract"][0].task_overrides,
        )
        command = build_remote_task_command(
            task_spec=task,
            host_target=plan.hosts_by_task_id["extract"][0],
            action="run",
            dag_id="missing-runtime-defaults",
            run_id="manual__1",
        )

        self.assertEqual(task.sudo_mode, "non_interactive")
        self.assertEqual(task.remote_execution_mode, "auto")
        self.assertIsNone(task.systemd_unit_prefix)
        self.assertEqual(task.retry_count, 2)
        self.assertEqual(task.retry_delay_seconds, 10)
        self.assertEqual(resolved_remote_execution_mode(task), "systemd_run")
        self.assertIn("remote-workflow-missing-runtime-defaults-run-extract", command)

    def test_auto_runtime_uses_foreground_for_rhel7_linux_script(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "actions": ["run"],
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "platform": "rhel7",
                        "sudo_user": "batch",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./run.sh",
                        },
                    },
                },
                "tasks": [
                    {"task_id": "extract", "host_group": "batch"},
                ],
            },
            default_workflow_id="rhel7_script_auto",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        task = apply_task_runtime_overrides(
            plan.tasks[0],
            plan.hosts_by_task_id["extract"][0].task_overrides,
        )
        command = build_remote_task_command(
            task_spec=task,
            host_target=plan.hosts_by_task_id["extract"][0],
            action="run",
        )

        self.assertEqual(task.platform, "rhel7")
        self.assertEqual(resolved_remote_execution_mode(task), "foreground")
        self.assertNotIn("systemd-run", command)
        self.assertIn("sudo -n -H -u batch bash -lc ./run.sh", command)

    def test_rhel7_linux_script_rejects_explicit_systemd_run(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "actions": ["run"],
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "platform": "rhel7",
                        "remote_execution_mode": "systemd_run",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./run.sh",
                        },
                    },
                },
                "tasks": [
                    {"task_id": "extract", "host_group": "batch"},
                ],
            },
            default_workflow_id="rhel7_script_invalid",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        task = apply_task_runtime_overrides(
            plan.tasks[0],
            plan.hosts_by_task_id["extract"][0].task_overrides,
        )

        with self.assertRaisesRegex(ValueError, "linux_script on RHEL7"):
            validate_task_spec(task, action="run")

    def test_auto_runtime_uses_system_scope_for_rhel7_systemd_service(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "gateway": {
                        "type": "systemd",
                        "platform": "rhel7",
                        "sudo_user": "gateway",
                        "service_name": "gateway.service",
                        "hosts": ["qa-gw-01.company.net"],
                    },
                },
                "tasks": [
                    {"task_id": "gateway", "host_group": "gateway"},
                ],
            },
            default_workflow_id="rhel7_systemd_auto",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        task = apply_task_runtime_overrides(
            plan.tasks[0],
            plan.hosts_by_task_id["gateway"][0].task_overrides,
        )
        command = build_remote_task_command(
            task_spec=task,
            host_target=plan.hosts_by_task_id["gateway"][0],
            action="start",
        )

        self.assertEqual(task.systemd["platform"], "rhel7")
        self.assertEqual(resolved_remote_execution_mode(task), "systemd_run")
        self.assertIn("sudo -n systemd-run", command)
        self.assertIn("--property=User=gateway", command)
        self.assertIn("sudo -n systemctl start gateway.service", command)

    def test_systemd_run_scope_config_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "systemd_run_scope"):
            build_workflow_definition_from_config(
                {
                    "runtime_defaults": {
                        "systemd_run_scope": "system",
                    },
                    "host_groups": {
                        "batch": {
                            "type": "linux_script",
                            "hosts": ["qa-batch-01.company.net"],
                            "commands": {
                                "run": "./run.sh",
                            },
                        },
                    },
                    "tasks": [
                        {"task_id": "extract", "host_group": "batch"},
                    ],
                    "actions": ["run"],
                },
                default_workflow_id="removed_scope",
            )

    def test_runtime_defaults_are_overridden_by_service_env_and_host(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "actions": ["run"],
                "runtime_defaults": {
                    "sudo_mode": "non_interactive",
                    "remote_execution_mode": "foreground",
                    "systemd_unit_prefix": "global",
                    "retry_count": 2,
                    "retry_delay_seconds": 10,
                },
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "scripts": [
                            {
                                "id": "job_{host_id}",
                                "commands": {
                                    "run": "./job.sh",
                                },
                                "remote_execution_mode": "systemd_run",
                                "systemd_unit_prefix": "service",
                                "retry_count": 3,
                                "retry_delay_seconds": 30,
                            }
                        ],
                        "environments": {
                            "qa": {
                                "sudo_user": "batch_qa",
                                "systemd_unit_prefix": "env",
                                "retry_count": 4,
                                "hosts": [
                                    {
                                        "host": "qa-batch-01.company.net",
                                        "host_id": "one",
                                        "sudo_mode": "login",
                                        "systemd_unit_prefix": "host",
                                        "retry_delay_seconds": 50,
                                    }
                                ],
                            }
                        },
                    },
                },
                "tasks": [
                    {"target": "job_one"},
                ],
            },
            default_workflow_id="runtime_layers",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        task = apply_task_runtime_overrides(
            plan.tasks[0],
            plan.hosts_by_task_id["job_one"][0].task_overrides,
        )

        command = build_remote_task_command(
            task_spec=task,
            host_target=plan.hosts_by_task_id["job_one"][0],
            action="run",
            dag_id="runtime-layers",
            run_id="manual__1",
        )

        self.assertEqual(task.sudo_mode, "login")
        self.assertEqual(task.remote_execution_mode, "systemd_run")
        self.assertEqual(task.systemd_unit_prefix, "host")
        self.assertEqual(task.retry_count, 4)
        self.assertEqual(task.retry_delay_seconds, 50)
        self.assertEqual(task_retry_kwargs(task)["retries"], 4)
        self.assertEqual(task_retry_kwargs(task)["retry_delay"].total_seconds(), 50)
        self.assertIn("sudo -iu batch_qa", command)
        self.assertIn("systemd-run --user", command)
        self.assertNotIn("--property=User=batch_qa", command)
        self.assertIn("host-runtime-layers-run-job_one", command)

    def test_script_targets_inherit_host_group_task_type(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "actions": ["run"],
                "host_groups": {
                    "windows_hosts": {
                        "type": "windows_script",
                        "windows_shell": "powershell",
                        "scripts": [
                            {
                                "id": "publish",
                                "commands": {
                                    "run": ".\\publish.ps1",
                                },
                            }
                        ],
                        "environments": {
                            "qa": {
                                "hosts": ["qa-win-01.company.net"],
                            }
                        },
                    },
                },
                "tasks": [
                    {"target": "publish"},
                ],
            },
            default_workflow_id="windows_targets",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        task = apply_task_runtime_overrides(
            plan.tasks[0],
            plan.hosts_by_task_id["publish"][0].task_overrides,
        )

        self.assertEqual(task.task_type, "windows_script")
        self.assertEqual(resolved_remote_execution_mode(task), "foreground")

    def test_workflow_graph_svg_renders_dependencies(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "batch": {
                        "type": "linux_script",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./run.sh",
                        },
                    },
                },
                "task_groups": [
                    {
                        "group_id": "grp_batch",
                        "tasks": [
                            {"task_id": "extract", "host_group": "batch"},
                            {
                                "task_id": "publish",
                                "host_group": "batch",
                                "depends_on": ["extract"],
                            },
                        ],
                    },
                ],
                "actions": ["run"],
            },
            default_workflow_id="graph",
        )
        plan = workflow_plan_for_env(workflow, "qa")

        graph = workflow_plan_to_svg(
            plan,
            action="run",
            workflow_id="graph",
            env_name="qa",
        )

        self.assertIn("<svg", graph)
        self.assertIn("graph - RUN DAG", graph)
        self.assertIn("grp_batch", graph)
        self.assertIn("extract", graph)
        self.assertIn("publish", graph)
        self.assertIn('marker-end="url(#arrow)"', graph)

    def test_validate_workflow_json_file_and_svg_graph(self) -> None:
        import json
        import tempfile

        config = {
            "actions": ["run"],
            "host_groups": {
                "batch": {
                    "type": "linux_script",
                    "hosts": ["qa-batch-01.company.net"],
                    "commands": {
                        "run": "./run.sh",
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
        }

        with tempfile.TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "daily_batch.json"
            config_file.write_text(json.dumps(config), encoding="utf-8")

            workflow, plans = validate_workflow_json_file(config_file, envs=("qa",))
            graph_documents = workflow_graph_svgs(
                config_file,
                envs=("qa",),
                actions=("run",),
            )

        self.assertEqual(workflow.workflow_id, "daily_batch")
        self.assertEqual(tuple(plans), ("qa",))
        self.assertEqual(len(graph_documents), 1)
        self.assertEqual(graph_documents[0][:3], ("daily_batch", "qa", "run"))
        self.assertIn("daily_batch - RUN DAG", graph_documents[0][3])
        self.assertIn("extract", graph_documents[0][3])
        self.assertIn("publish", graph_documents[0][3])

    def test_validation_envs_use_runtime_envs_not_enabled_in_envs(self) -> None:
        import json
        import tempfile

        config = {
            "actions": ["run"],
            "enabled_in_envs": ["prod"],
            "runtime_envs": ["prod", "dr"],
            "host_groups": {
                "batch": {
                    "type": "linux_script",
                    "hosts": ["{env}-batch-01.company.net"],
                    "commands": {
                        "run": "./run.sh",
                    },
                },
            },
            "tasks": [
                {"task_id": "extract", "host_group": "batch"},
            ],
        }

        with tempfile.TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "daily_batch.json"
            config_file.write_text(json.dumps(config), encoding="utf-8")

            _, plans = validate_workflow_json_file(config_file)

        self.assertEqual(tuple(plans), ("prod", "dr"))

    def test_missing_env_target_and_dependency_are_skipped(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "host_groups": {
                    "app_servers": {
                        "platform": "rhel8",
                        "services": [
                            {
                                "id": "app_{host_id}",
                                "service_name": "app.service",
                            },
                        ],
                        "environments": {
                            "dev": {
                                "hosts": [
                                    {
                                        "host": "dev-app-01.company.net",
                                        "host_id": "one",
                                    },
                                ],
                            },
                            "qa": {
                                "hosts": [
                                    {
                                        "host": "qa-app-01.company.net",
                                        "host_id": "one",
                                    },
                                    {
                                        "host": "qa-app-02.company.net",
                                        "host_id": "two",
                                    },
                                ],
                                "services": [
                                    {
                                        "id": "qa_only_{host_id}",
                                        "service_name": "qa-only.service",
                                    },
                                ],
                            },
                        },
                    },
                },
                "task_groups": [
                    {
                        "group_id": "app_services",
                        "tasks": [
                            {
                                "target": "app_one",
                                "start_depends_on": ["app_two"],
                            },
                            {
                                "target": "app_two",
                            },
                        ],
                    },
                    {
                        "group_id": "qa_only_services",
                        "tasks": [
                            {
                                "target": "qa_only_two",
                            },
                        ],
                    },
                ],
            },
            default_workflow_id="app",
        )

        dev_plan = workflow_plan_for_env(workflow, "dev")
        self.assertEqual(tuple(task.task_id for task in dev_plan.tasks), ("app_one",))
        self.assertEqual(tuple(group.group_id for group in dev_plan.groups), ("app_services",))
        self.assertEqual(dev_plan.start_upstream_task_ids, {"app_one": ()})

        qa_plan = workflow_plan_for_env(workflow, "qa")
        self.assertEqual(
            tuple(task.task_id for task in qa_plan.tasks),
            ("app_one", "app_two", "qa_only_two"),
        )
        self.assertEqual(
            tuple(group.group_id for group in qa_plan.groups),
            ("app_services", "qa_only_services"),
        )
        self.assertEqual(
            qa_plan.start_upstream_task_ids,
            {
                "app_one": ("app_two",),
                "app_two": (),
                "qa_only_two": (),
            },
        )

    def test_legacy_host_group_task_group_field_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "no longer supports task_group"):
            build_workflow_definition_from_config(
                {
                    "host_groups": {
                        "shared_server": {
                            "hosts": ["prod-shared-01.company.net"],
                            "services": ["gateway.service"],
                            "task_group": {
                                "group_id": "shared_server_services",
                            },
                        },
                    },
                },
                default_workflow_id="shared",
            )

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

        self.assertIn("systemd-run", command)
        self.assertIn("./bin/run-job --env qa", command)
        self.assertIn("remote-workflow-remote_workflow-run-extract", command)

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

    def test_register_workflow_reports_config_path_on_parse_error(self) -> None:
        import json
        import tempfile

        config = {
            "actions": ["run"],
            "runtime_defaults": {
                "systemd_run_scope": "user",
            },
            "host_groups": {},
        }

        with tempfile.TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "bad.json"
            config_file.write_text(json.dumps(config), encoding="utf-8")

            with self.assertRaises(ValueError) as raised:
                register_workflow_dags_from_json(
                    config_file=config_file,
                    global_namespace={},
                    config_root=Path(tmp),
                )
            message = str(raised.exception)
            self.assertIn("Failed to register remote workflow JSON", message)
            self.assertIn("bad.json", message)
            self.assertIn("systemd_run_scope", message)

    def test_register_start_stop_workflow_can_target_multiple_runtime_envs(self) -> None:
        import json
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        config = {
            "runtime_envs": ["prod", "dr"],
            "schedules": {
                "start": "0 7 * * 1-5",
                "stop": "0 19 * * 1-5",
            },
            "host_groups": {
                "gateway": {
                    "type": "systemd",
                    "service_name": "gateway.service",
                    "environments": {
                        "prod": {
                            "hosts": ["prod-gw-01.company.net"],
                        },
                        "dr": {
                            "hosts": ["dr-gw-01.company.net"],
                        },
                    },
                },
            },
            "tasks": [
                {"task_id": "gateway", "host_group": "gateway"},
            ],
        }

        with tempfile.TemporaryDirectory() as tmp:
            config_path = Path(tmp) / "platform.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            registered = {}

            with patch(
                "workflow.remote_workflow.create_workflow_dag",
                side_effect=lambda **kwargs: {
                    "dag_id": kwargs["dag_id"],
                    "action": kwargs["action"],
                    "schedule": kwargs["schedule"],
                    "target_env": kwargs["target_env"],
                },
            ):
                with patch("workflow.remote_workflow.get_current_env_name", return_value="prod"):
                    register_workflow_dags_from_json(
                        config_file=config_path,
                        global_namespace=registered,
                        config_root=Path(tmp),
                    )

        self.assertEqual(
            sorted(registered),
            [
                "platform_dr_start",
                "platform_dr_status",
                "platform_dr_stop",
                "platform_prod_start",
                "platform_prod_status",
                "platform_prod_stop",
            ],
        )
        self.assertEqual(registered["platform_prod_start"]["dag_id"], "platform-prod-start")
        self.assertEqual(registered["platform_prod_start"]["target_env"], "prod")
        self.assertEqual(registered["platform_prod_status"]["dag_id"], "platform-prod-status")
        self.assertEqual(registered["platform_dr_stop"]["dag_id"], "platform-dr-stop")
        self.assertEqual(registered["platform_dr_stop"]["target_env"], "dr")

    def test_runtime_envs_fallback_to_current_env_when_not_primary_env(self) -> None:
        import json
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        config = {
            "runtime_envs": ["prod", "dr"],
            "schedules": {
                "start": "0 7 * * 1-5",
                "stop": "0 19 * * 1-5",
            },
            "host_groups": {
                "gateway": {
                    "type": "systemd",
                    "service_name": "gateway.service",
                    "environments": {
                        "qa": {
                            "hosts": ["qa-gw-01.company.net"],
                        },
                    },
                },
            },
            "tasks": [
                {"task_id": "gateway", "host_group": "gateway"},
            ],
        }

        with tempfile.TemporaryDirectory() as tmp:
            config_path = Path(tmp) / "platform.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            registered = {}

            with patch(
                "workflow.remote_workflow.create_workflow_dag",
                side_effect=lambda **kwargs: {
                    "dag_id": kwargs["dag_id"],
                    "action": kwargs["action"],
                    "target_env": kwargs["target_env"],
                },
            ):
                with patch("workflow.remote_workflow.get_current_env_name", return_value="qa"):
                    register_workflow_dags_from_json(
                        config_file=config_path,
                        global_namespace=registered,
                        config_root=Path(tmp),
                    )

        self.assertEqual(sorted(registered), ["platform_start", "platform_status", "platform_stop"])
        self.assertEqual(registered["platform_start"]["dag_id"], "platform-start")
        self.assertEqual(registered["platform_start"]["target_env"], "qa")

    def test_enabled_in_envs_skips_workflow_file_for_current_env(self) -> None:
        import json
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        config = {
            "enabled_in_envs": ["dev", "qa"],
            "actions": ["run"],
            "schedules": {
                "run": "15 9 * * 1-5",
            },
            "host_groups": {
                "batch": {
                    "type": "linux_script",
                    "hosts": ["prod-batch-01.company.net"],
                    "commands": {
                        "run": "./bin/run-job",
                    },
                },
            },
            "tasks": [
                {"task_id": "batch", "host_group": "batch"},
            ],
        }

        with tempfile.TemporaryDirectory() as tmp:
            config_path = Path(tmp) / "daily.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            registered = {}

            with patch("workflow.remote_workflow.get_current_env_name", return_value="prod"):
                register_workflow_dags_from_json(
                    config_file=config_path,
                    global_namespace=registered,
                    config_root=Path(tmp),
                )

        self.assertEqual(registered, {})

    def test_register_run_workflow_can_target_multiple_runtime_envs(self) -> None:
        import json
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        config = {
            "runtime_envs": ["prod", "dr"],
            "actions": ["run"],
            "schedules": {
                "run": "15 9 * * 1-5",
            },
            "host_groups": {
                "batch": {
                    "type": "linux_script",
                    "commands": {
                        "run": "./bin/run-job --env {env}",
                    },
                    "environments": {
                        "prod": {
                            "hosts": ["prod-batch-01.company.net"],
                        },
                        "dr": {
                            "hosts": ["dr-batch-01.company.net"],
                        },
                    },
                },
            },
            "tasks": [
                {"task_id": "batch", "host_group": "batch"},
            ],
        }

        with tempfile.TemporaryDirectory() as tmp:
            config_path = Path(tmp) / "daily.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            registered = {}

            with patch(
                "workflow.remote_workflow.create_workflow_dag",
                side_effect=lambda **kwargs: {
                    "dag_id": kwargs["dag_id"],
                    "action": kwargs["action"],
                    "target_env": kwargs["target_env"],
                },
            ):
                with patch("workflow.remote_workflow.get_current_env_name", return_value="prod"):
                    register_workflow_dags_from_json(
                        config_file=config_path,
                        global_namespace=registered,
                        config_root=Path(tmp),
                    )

        self.assertEqual(sorted(registered), ["daily_dr_run", "daily_prod_run"])
        self.assertEqual(registered["daily_prod_run"]["dag_id"], "daily-prod")
        self.assertEqual(registered["daily_dr_run"]["dag_id"], "daily-dr")

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

        self.assertEqual(workflow.actions, ("start", "stop", "status"))

    def test_script_fields_are_env_overridable_and_render_as_args(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "actions": ["run"],
                "host_groups": {
                    "linux_batch": {
                        "type": "linux_script",
                        "hosts": ["qa-batch-01.company.net"],
                        "commands": {
                            "run": "./bin/run-report",
                        },
                        "fields": {
                            "business_date": {
                                "type": "string",
                                "default": "",
                                "description": "Business date",
                            },
                            "dry_run": {
                                "type": "boolean",
                                "default": False,
                                "cli_name": "dry-run",
                                "transform": "lower_bool",
                                "description": "Run without publishing output",
                            },
                            "region": {
                                "type": "enum",
                                "default": "us",
                                "values": ["us", "emea"],
                                "export_to_env": True,
                                "env_name": "REGION",
                                "description": "Region",
                            },
                            "desks": {
                                "type": "multi_enum",
                                "default": [],
                                "values": ["fx", "rates", "credit"],
                                "cli_joiner": "|",
                                "description": "Desks",
                            },
                        },
                        "environments": {
                            "qa": {
                                "fields": {
                                    "business_date": {
                                        "default": "2026-06-05",
                                    },
                                    "region": {
                                        "default": "emea",
                                        "values": ["emea", "apac"],
                                    },
                                },
                                "preset_params": {
                                    "dry_run": True,
                                    "desks": ["fx", "rates"],
                                },
                                "hosts": ["qa-batch-01.company.net"],
                            },
                        },
                    },
                    "windows_batch": {
                        "type": "windows_script",
                        "windows_shell": "raw",
                        "hosts": ["qa-win-batch-01.company.net"],
                        "commands": {
                            "run": ".\\publish.ps1",
                        },
                        "fields": {
                            "publish_mode": {
                                "type": "enum",
                                "default": "fast",
                                "values": ["fast", "full"],
                                "description": "Publish mode",
                            },
                        },
                    },
                },
                "tasks": [
                    {"task_id": "run_report", "host_group": "linux_batch"},
                    {"task_id": "publish", "host_group": "windows_batch"},
                ],
            },
            default_workflow_id="batch",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        airflow_fields = build_script_airflow_fields(plan)

        self.assertEqual(airflow_fields["business_date"]["default"], "2026-06-05")
        self.assertEqual(airflow_fields["dry_run"]["default"], True)
        self.assertEqual(airflow_fields["region"]["values"], ["emea", "apac"])
        self.assertEqual(airflow_fields["desks"]["default"], ["fx", "rates"])
        self.assertEqual(airflow_fields["publish_mode"]["values"], ["fast", "full"])

        linux_task = next(task for task in plan.tasks if task.task_id == "run_report")
        linux_host = plan.hosts_by_task_id["run_report"][0]
        linux_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                linux_task,
                linux_host.task_overrides,
            ),
            host_target=linux_host,
            action="run",
            validated_params={
                "business_date": "2026-06-06",
                "dry_run": True,
                "region": "emea",
                "desks": ["fx", "rates"],
                "extra_args": "--limit 10",
            },
        )

        self.assertIn("REGION=emea ./bin/run-report", linux_command)
        self.assertIn("--business_date=2026-06-06", linux_command)
        self.assertIn("--dry-run=true", linux_command)
        self.assertIn("--region=emea", linux_command)
        self.assertIn("--desks=fx|rates", linux_command)
        self.assertIn("--limit 10", linux_command)

        windows_task = next(task for task in plan.tasks if task.task_id == "publish")
        windows_host = plan.hosts_by_task_id["publish"][0]
        windows_command = build_remote_task_command(
            task_spec=apply_task_runtime_overrides(
                windows_task,
                windows_host.task_overrides,
            ),
            host_target=windows_host,
            action="run",
            validated_params={
                "publish_mode": "full",
            },
        )

        self.assertEqual(windows_command, ".\\publish.ps1 --publish_mode=full")

    def test_start_stop_script_fields_render_as_args(self) -> None:
        workflow = build_workflow_definition_from_config(
            {
                "schedules": {
                    "start": "0 7 * * 1-5",
                    "stop": "0 19 * * 1-5",
                },
                "host_groups": {
                    "risk": {
                        "type": "linux_script",
                        "hosts": ["qa-risk-01.company.net"],
                        "commands": {
                            "start": "./bin/risk-control start",
                            "stop": "./bin/risk-control stop",
                            "status": "./bin/risk-control status",
                        },
                        "fields": {
                            "change_ticket": {
                                "type": "string",
                                "default": "",
                                "cli_name": "ticket",
                                "export_to_env": True,
                                "env_name": "CHANGE_TICKET",
                                "description": "Change ticket",
                            },
                            "force": {
                                "type": "boolean",
                                "default": False,
                                "cli_name": "force",
                                "transform": "lower_bool",
                                "description": "Force operation",
                            },
                            "phase": {
                                "type": "enum",
                                "default": "regular",
                                "values": ["regular", "maintenance"],
                                "description": "Operation phase",
                            },
                            "components": {
                                "type": "multi_enum",
                                "default": [],
                                "values": ["engine", "scheduler", "api"],
                                "description": "Components",
                            },
                        },
                        "environments": {
                            "qa": {
                                "fields": {
                                    "phase": {
                                        "default": "maintenance",
                                    },
                                },
                                "preset_params": {
                                    "components": ["engine", "api"],
                                },
                                "hosts": ["qa-risk-01.company.net"],
                            },
                        },
                    },
                },
                "tasks": [
                    {"task_id": "risk", "host_group": "risk"},
                ],
            },
            default_workflow_id="risk_control",
        )
        plan = workflow_plan_for_env(workflow, "qa")
        airflow_fields = build_script_airflow_fields(plan)

        self.assertEqual(workflow.actions, ("start", "stop", "status"))
        self.assertEqual(airflow_fields["phase"]["default"], "maintenance")
        self.assertEqual(airflow_fields["components"]["default"], ["engine", "api"])

        risk_task = plan.tasks[0]
        risk_host = plan.hosts_by_task_id["risk"][0]
        effective_task = apply_task_runtime_overrides(
            risk_task,
            risk_host.task_overrides,
        )
        validated_params = {
            "change_ticket": "CHG-123",
            "force": True,
            "phase": "maintenance",
            "components": ["engine", "api"],
        }

        start_command = build_remote_task_command(
            task_spec=effective_task,
            host_target=risk_host,
            action="start",
            validated_params=validated_params,
        )
        stop_command = build_remote_task_command(
            task_spec=effective_task,
            host_target=risk_host,
            action="stop",
            validated_params=validated_params,
        )

        self.assertIn("CHANGE_TICKET=CHG-123 ./bin/risk-control start", start_command)
        self.assertIn("--ticket=CHG-123", start_command)
        self.assertIn("--force=true", start_command)
        self.assertIn("--phase=maintenance", start_command)
        self.assertIn("--components=engine,api", start_command)
        self.assertIn("CHANGE_TICKET=CHG-123 ./bin/risk-control stop", stop_command)
        self.assertIn("--ticket=CHG-123", stop_command)


if __name__ == "__main__":
    unittest.main()
