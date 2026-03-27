from __future__ import annotations

from datetime import datetime
from pathlib import Path

from airflow.sdk import dag
from airflow.timetables.trigger import CronTriggerTimetable, MultipleCronTriggerTimetable

from common.config_loader import load_runtime_env_config


DEFAULT_RUNTIME_ENV_FILE = Path(__file__).resolve().parents[1] / "configs" / "runtime_envs.json"


def build_runtime_context(
    *,
    config_file: str | Path = DEFAULT_RUNTIME_ENV_FILE,
) -> dict:
    runtime_cfg = load_runtime_env_config(config_file)

    if not runtime_cfg.get("owner"):
        raise ValueError("Missing required config key: owner")
    if not runtime_cfg.get("namespace"):
        raise ValueError("Missing required config key: namespace")

    return {
        "owner": runtime_cfg["owner"],
        "target_host": runtime_cfg.get("target_host"),
        "namespace": runtime_cfg["namespace"],
        "kerberos_principal": runtime_cfg["kerberos_principal"],
        "kerberos_realm": runtime_cfg["kerberos_realm"],
        "keytab_secret_name": runtime_cfg["keytab_secret_name"],
        "keytab_filename": runtime_cfg.get("keytab_filename", "proid.keytab"),
        "kerberos_cache_filename": runtime_cfg.get("kerberos_cache_filename", "krb5cc_airflow"),
        "kerberos_init_image": runtime_cfg["kerberos_init_image"],
        "ssh_secret_name": runtime_cfg.get("ssh_secret_name", runtime_cfg.get("keytab_secret_name")),
        "main_container_name": runtime_cfg.get("main_container_name", "base"),
        "timezone": runtime_cfg.get("timezone", "Asia/Shanghai"),
    }


def build_minimal_tenant_executor_config(runtime_context: dict) -> dict:
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
                        env_from=[
                            k8s.V1EnvFromSource(
                                secret_ref=k8s.V1SecretEnvSource(
                                    name=runtime_context["ssh_secret_name"],
                                )
                            )
                        ] if runtime_context.get("ssh_secret_name") else None,
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
    schedule: str | list[str] | tuple[str, ...] | None,
    tags: list[str],
    timezone: str,
    params: dict,
    owner: str,
):
    if schedule is None:
        dag_schedule = None
    elif isinstance(schedule, str):
        dag_schedule = CronTriggerTimetable(schedule, timezone=timezone)
    else:
        dag_schedule = MultipleCronTriggerTimetable(*schedule, timezone=timezone)

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
