from __future__ import annotations

from kubernetes.client import models as k8s


def build_kerberos_executor_config(
    *,
    namespace: str,
    kerberos_principal: str,
    kerberos_realm: str,
    keytab_secret_name: str,
    keytab_filename: str,
    kerberos_cache_filename: str,
    kerberos_init_image: str,
    main_container_name: str = "base",
    egress_label_value: str = "true",
) -> dict:
    shared_volume_name = "krb5-shared-cache"
    keytab_volume_name = "krb5-keytab-secret"

    return {
        "pod_override": k8s.V1Pod(
            metadata=k8s.V1ObjectMeta(
                namespace=namespace,
                labels={"egress": egress_label_value},
            ),
            spec=k8s.V1PodSpec(
                containers=[
                    k8s.V1Container(
                        name=main_container_name,
                        volume_mounts=[
                            k8s.V1VolumeMount(
                                mount_path="/krb5",
                                name=shared_volume_name,
                            )
                        ],
                        env=[
                            k8s.V1EnvVar(
                                name="KRB5CCNAME",
                                value=f"FILE:/krb5/{kerberos_cache_filename}",
                            )
                        ],
                    )
                ],
                volumes=[
                    k8s.V1Volume(
                        name=shared_volume_name,
                        empty_dir=k8s.V1EmptyDirVolumeSource(),
                    ),
                    k8s.V1Volume(
                        name=keytab_volume_name,
                        secret=k8s.V1SecretVolumeSource(
                            secret_name=keytab_secret_name,
                        ),
                    ),
                ],
                init_containers=[
                    k8s.V1Container(
                        name="krb5-init",
                        image=kerberos_init_image,
                        command=["/bin/bash", "-c"],
                        args=[
                            " ".join(
                                [
                                    "/krb5/krb5_init.sh",
                                    kerberos_principal,
                                    kerberos_realm,
                                    "ticket",
                                    "/emptydir/",
                                    f"/keytab/{keytab_filename}",
                                ]
                            )
                        ],
                        volume_mounts=[
                            k8s.V1VolumeMount(
                                mount_path="/emptydir",
                                name=shared_volume_name,
                            ),
                            k8s.V1VolumeMount(
                                mount_path="/keytab",
                                name=keytab_volume_name,
                            ),
                        ],
                    )
                ],
            ),
        )
    }