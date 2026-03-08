from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any


def get_required_env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise ValueError(f"Required environment variable '{name}' is missing.")
    return value


def get_current_env_name() -> str:
    return get_required_env("AIRFLOW_ENV").lower()


def get_current_loc_name() -> str:
    return os.environ.get("AIRFLOW_LOC", "default").lower()


def load_json_file(file_path: str | Path) -> dict[str, Any]:
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"Configuration file not found: {path}")

    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def load_runtime_env_config(config_file: str | Path) -> dict[str, Any]:
    app_env = get_current_env_name()
    app_loc = get_current_loc_name()

    data = load_json_file(config_file)

    if app_env not in data:
        raise KeyError(
            f"AIRFLOW_ENV '{app_env}' not found in runtime config. "
            f"Available env keys: {list(data.keys())}"
        )

    env_block = data[app_env]

    if app_loc in env_block:
        return env_block[app_loc]

    if "default" in env_block:
        return env_block["default"]

    raise KeyError(
        f"AIRFLOW_LOC '{app_loc}' not found under env '{app_env}', "
        f"and no 'default' block exists."
    )


def load_topology_for_current_env(topology_file: str | Path) -> dict[str, Any]:
    app_env = get_current_env_name()
    data = load_json_file(topology_file)

    if app_env not in data:
        raise KeyError(
            f"AIRFLOW_ENV '{app_env}' not found in topology config. "
            f"Available env keys: {list(data.keys())}"
        )

    return data[app_env]