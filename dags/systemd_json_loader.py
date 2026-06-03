from pathlib import Path

from common.systemd_workflow import register_systemd_dags_from_json_dir


SYSTEMD_CONFIG_DIR = Path(__file__).resolve().parent / "systemd"


register_systemd_dags_from_json_dir(
    config_dir=SYSTEMD_CONFIG_DIR,
    global_namespace=globals(),
)
