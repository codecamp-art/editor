from pathlib import Path

from common.systemd_workflow import load_systemd_workflow_definition_from_json


MARKET_DATA_WORKFLOW = load_systemd_workflow_definition_from_json(
    Path(__file__).with_suffix(".json")
)
