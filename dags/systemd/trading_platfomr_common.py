from common.systemd_workflow import SystemdProcessSpec, SystemdWorkflowDefinition


TRADING_PLATFORM_WORKFLOW = SystemdWorkflowDefinition(
    workflow_id="trading_platform",
    title="Trading Platform",
    description="""
# Trading Platform Start/Stop

This workflow controls trading platform related systemd services across remote RHEL7/8 servers.

## Supported trigger parameters
- target_mode: workflow | single_process
- target_process
- run_mode
- extra_args
""".strip(),
    schedule_start="0 7 * * 1-5",
    schedule_stop="0 19 * * 1-5",
    fields={},
    processes=(
        SystemdProcessSpec(
            process_id="gateway",
            service_name="gateway.service",
            service_user="gwuser",
            host_group="gateway",
            platform="rhel7",
        ),
        SystemdProcessSpec(
            process_id="mds",
            service_name="mds.service",
            service_user="mdsuser",
            host_group="mds",
            platform="rhel8",
            start_after=("gateway",),
        ),
        SystemdProcessSpec(
            process_id="app_primary",
            service_name="app-primary.service",
            service_user="appuser",
            host_group="app_primary",
            platform="rhel8",
            start_after=("mds",),
        ),
        SystemdProcessSpec(
            process_id="app_secondary",
            service_name="app-secondary.service",
            service_user="appuser",
            host_group="app_secondary",
            platform="rhel8",
            start_after=("app_primary",),
            enabled_in_envs=("qa", "prod"),
        ),
    ),
    tags=("systemd", "trading-platform", "ssh", "kerberos"),
    command_timeout_seconds=1800,
)