from __future__ import annotations

from pathlib import Path

from common.config_loader import load_json_file
from common.remote_script_workflow import register_remote_script_dags_from_json


REMOTE_SCRIPT_CONFIG_DIRS = (
    Path(__file__).resolve().parent / "reporting",
)


def _is_remote_script_definition_json(json_file: Path) -> bool:
    config = load_json_file(json_file)
    return isinstance(config, dict) and "base" in config


for config_dir in REMOTE_SCRIPT_CONFIG_DIRS:
    for config_file in sorted(config_dir.rglob("*.json")):
        if not _is_remote_script_definition_json(config_file):
            continue

        register_remote_script_dags_from_json(
            config_file=config_file,
            source_file=__file__,
            global_namespace=globals(),
        )
