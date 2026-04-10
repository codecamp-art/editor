from common.remote_script_workflow import register_remote_script_dags_from_json


register_remote_script_dags_from_json(
    config_file="configs/recon_report_a.json",
    source_file=__file__,
    global_namespace=globals(),
)
