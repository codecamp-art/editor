from pathlib import Path

from workflow.remote_workflow import register_workflow_dags_from_json_dir


REMOTE_WORKFLOW_CONFIG_DIR = Path(__file__).resolve().parent / "remote_workflows"


register_workflow_dags_from_json_dir(
    config_dir=REMOTE_WORKFLOW_CONFIG_DIR,
    global_namespace=globals(),
)
