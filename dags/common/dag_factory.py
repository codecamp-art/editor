from __future__ import annotations

import os
from datetime import datetime
from pathlib import Path

from airflow.sdk import dag
from airflow.timetables.trigger import CronTriggerTimetable

from common.config_loader import load_runtime_env_config


DEFAULT_RUNTIME_ENV_FILE = Path(__file__).resolve().parents[1] / "configs" / "runtime_envs.json"


def get_sysid_from_file(file_path: str | Path) -> str:
    path = Path(file_path)
    return path.parts[-2]


def build_tenant_namespace(*, owner: str) -> str:
    tenant_prefix = os.environ.get("AIRFLOW_TENANT_PREFIX")
    airflow_env = os.environ.get("AIRFLOW_ENV")

    if not tenant_prefix:
        raise ValueError("Required environment variable 'AIRFLOW_TENANT_PREFIX' is missing.")
    if not airflow_env:
        raise ValueError("Required environment variable 'AIRFLOW_ENV' is missing.")

    return f"{tenant_prefix}-{airflow_env.lower()}-{owner}"


def build_runtime_context(
    *,
    owner: str,
    config_file: str | Path = DEFAULT_RUNTIME_ENV_FILE,
) -> dict:
    runtime_cfg = load_runtime_env_config(config_file)

    return {
        "target_host": runtime_cfg.get("target_host"),
        "namespace": build_tenant_namespace(owner=owner),
        "kerberos_principal": runtime_cfg["kerberos_principal"],
        "kerberos_realm": runtime_cfg["kerberos_realm"],
        "keytab_secret_name": runtime_cfg["keytab_secret_name"],
        "keytab_filename": runtime_cfg.get("keytab_filename", "proid.keytab"),
        "kerberos_cache_filename": runtime_cfg.get("kerberos_cache_filename", "krb5cc_airflow"),
        "kerberos_init_image": runtime_cfg["kerberos_init_image"],
        "main_container_name": runtime_cfg.get("main_container_name", "base"),
        "timezone": runtime_cfg.get("timezone", "Asia/Shanghai"),
    }


def build_minimal_tenant_executor_config(runtime_context: dict) -> dict:
    """
    Minimal executor_config for Python/helper tasks.
    Enough to satisfy tenant scheduling policy, without Kerberos init container,
    secret mounts, or extra volumes.
    """
    try:
        from kubernetes.client import models as k8s
    except ImportError as exc:
        raise ImportError("kubernetes python package is required for executor_config.") from exc

    return {
        "pod_override": k8s.V1Pod(
            metadata=k8s.V1ObjectMeta(
                namespace=runtime_context["namespace"],
            ),
            spec=k8s.V1PodSpec(
                containers=[
                    k8s.V1Container(
                        name=runtime_context["main_container_name"],
                    )
                ],
            ),
        )
    }


def build_full_kerberos_executor_config(runtime_context: dict) -> dict:
    from common.kerberos_pod import build_kerberos_executor_config

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


def dag_decorator(
    *,
    dag_id: str,
    description: str,
    schedule: str | None,
    tags: list[str],
    timezone: str,
    params: dict,
    owner: str,
):
    dag_schedule = CronTriggerTimetable(schedule, timezone=timezone) if schedule else None

    return dag(
        dag_id=dag_id,
        start_date=datetime(2024, 12, 26),
        schedule=dag_schedule,
        catchup=False,
        doc_md=description,
        tags=tags,
        params=params,
        default_args={"owner": owner},
    )