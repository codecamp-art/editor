from __future__ import annotations

from datetime import datetime
from pathlib import Path

from airflow.decorators import dag
from airflow.timetables.trigger import CronTriggerTimetable

from common.config_loader import load_runtime_env_config
from common.kerberos_pod import build_kerberos_executor_config


DEFAULT_RUNTIME_ENV_FILE = Path(__file__).resolve().parents[1] / "configs" / "runtime_envs.json"


def build_runtime_context(config_file: str | Path = DEFAULT_RUNTIME_ENV_FILE) -> dict:
    runtime_cfg = load_runtime_env_config(config_file)

    return {
        "target_host": runtime_cfg.get("target_host"),
        "namespace": runtime_cfg["namespace"],
        "kerberos_principal": runtime_cfg["kerberos_principal"],
        "kerberos_realm": runtime_cfg["kerberos_realm"],
        "keytab_secret_name": runtime_cfg["keytab_secret_name"],
        "keytab_filename": runtime_cfg.get("keytab_filename", "proid.keytab"),
        "kerberos_cache_filename": runtime_cfg.get("kerberos_cache_filename", "krb5cc_airflow"),
        "kerberos_init_image": runtime_cfg["kerberos_init_image"],
        "main_container_name": runtime_cfg.get("main_container_name", "base"),
        "timezone": runtime_cfg.get("timezone", "Asia/Shanghai"),
    }


def build_executor_config(runtime_context: dict) -> dict:
    return build_kerberos_executor_config(
        namespace=runtime_context["namespace"],
        kerberos_principal=runtime_context["kerberos_principal"],
        kerberos_realm=runtime_context["kerberos_realm"],
        keytab_secret_name=runtime_context["keytab_secret_name"],
        keytab_filename=runtime_context["keytab_filename"],
        kerberos_cache_filename=runtime_context["kerberos_cache_filename"],
        kerberos_init_image=runtime_context["kerberos_init_image"],
        main_container_name=runtime_context["main_container_name"],
    )


def dag_decorator(*, dag_id: str, description: str, schedule: str, tags: list[str], timezone: str, params: dict):
    return dag(
        dag_id=dag_id,
        start_date=datetime(2024, 12, 26),
        schedule=CronTriggerTimetable(schedule, timezone=timezone),
        catchup=False,
        doc_md=description,
        tags=tags,
        params=params,
    )