from common.systemd_workflow import create_systemd_dag
from systemd.market_data_common import MARKET_DATA_WORKFLOW


dag = create_systemd_dag(
    workflow=MARKET_DATA_WORKFLOW,
    dag_id="market-data-start",
    action="start",
    schedule=MARKET_DATA_WORKFLOW.schedule_start,
)