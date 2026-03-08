from common.systemd_workflow import create_systemd_dag
from systemd.trading_platform_common import TRADING_PLATFORM_WORKFLOW


dag = create_systemd_dag(
    workflow=TRADING_PLATFORM_WORKFLOW,
    dag_id="trading-platform-stop",
    action="stop",
    schedule=TRADING_PLATFORM_WORKFLOW.schedule_stop,
)