from pathlib import Path

from common.remote_workflow import register_workflow_dags_from_json_dir


WORKFLOW_CONFIG_DIR = Path(__file__).resolve().parent / "workflows"


register_workflow_dags_from_json_dir(
    config_dir=WORKFLOW_CONFIG_DIR,
    global_namespace=globals(),
)
