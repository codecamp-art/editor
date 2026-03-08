from common.systemd_workflow import (
    ExternalDagDependency,
    SystemdProcessSpec,
    SystemdWorkflowDefinition,
)


MARKET_DATA_WORKFLOW = SystemdWorkflowDefinition(
    workflow_id="market_data",
    title="Market Data",
    description="""
# Market Data Start/Stop

This workflow controls market data systemd services.
""".strip(),
    schedule_start="30 6 * * 1-5",
    schedule_stop="30 18 * * 1-5",
    fields={},
    processes=(
        SystemdProcessSpec(
            process_id="mds_collector",
            service_name="mds-collector.service",
            service_user="mduser",
            host_group="mds",
            platform="rhel8",
        ),
        SystemdProcessSpec(
            process_id="mds_gateway",
            service_name="mds-gateway.service",
            service_user="mduser",
            host_group="gateway",
            platform="rhel7",
            start_after=("mds_collector",),
        ),
    ),
    upstream_dags_for_start=(),
    upstream_dags_for_stop=(
        ExternalDagDependency(
            dag_id="trading-platform-stop",
            task_id="end",
            enabled_in_envs=("qa", "prod", "dr"),
        ),
    ),
    tags=("systemd", "market-data", "ssh", "kerberos"),
    command_timeout_seconds=1800,
)