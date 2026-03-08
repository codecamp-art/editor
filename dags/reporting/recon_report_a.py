from common.reporting_workflow import ReportingDefinition, create_reporting_dag


REPORT_A = ReportingDefinition(
    report_id="recon_report_a",
    dag_id="recon-report-a",
    title="Recon Report A",
    description="""
# Recon Report A

This DAG runs Report A on the remote reporting server.
""".strip(),
    schedule="10 9 * * 1-5",
    remote_script="/opt/reporting/bin/run_recon_report_a.sh",
    sudo_user="reportuser",
    fields={
        "business_date": {
            "type": "string",
            "default": "",
            "description": "Business date in YYYY-MM-DD format",
            "cli_name": "businessDate",
        },
        "region": {
            "type": "enum",
            "default": "APAC",
            "values": ["APAC", "EMEA", "AMER"],
            "description": "Region for Report A",
            "cli_name": "region",
        },
        "legal_entity": {
            "type": "string",
            "default": "",
            "description": "Optional legal entity code",
            "cli_name": "legalEntity",
        },
        "output_format": {
            "type": "enum",
            "default": "csv",
            "values": ["csv", "xlsx"],
            "description": "Output format",
            "cli_name": "outputFormat",
        }
    },
    adhoc_rules={"required": ["business_date"]},
    command_timeout_seconds=3600,
    tags=("reporting", "recon", "report-a", "ssh", "kerberos"),
)

dag = create_reporting_dag(definition=REPORT_A)