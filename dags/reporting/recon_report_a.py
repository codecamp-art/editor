from common.reporting_workflow import (
    ReportingDefinition,
    ReportingScheduleVariant,
    create_reporting_dag,
    create_reporting_definition_variant,
)
from common.trading_calendar import TradingDayCheckDefinition


REPORT_A_BASE = ReportingDefinition(
    report_id="recon_report_a",
    dag_id="recon-report-a-base-not-used-directly",
    title="Recon Report A",
    description="""
# Recon Report A

This report supports multiple scheduled DAG variants and one dedicated adhoc DAG.
""".strip(),
    schedule=None,
    remote_script=None,
    remote_command_prefix=["java", "-jar", "recon-report-a.jar"],
    sudo_user="reportuser_default",
    working_dir="/opt/reporting/recon-report-a",
    fields={
        "spring_profile": {
            "type": "string",
            "default": "",
            "description": "Spring profile",
            "cli_name": "spring.profiles.active",
        },
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
        },
        "rerun_failed_only": {
            "type": "boolean",
            "default": False,
            "description": "Only rerun failed records",
            "cli_name": "rerunFailedOnly",
            "transform": "lower_bool",
        },
        "batch_id": {
            "type": "string",
            "default": "",
            "description": "Optional batch id for adhoc runs",
            "cli_name": "batchId",
        },
    },
    adhoc_rules={
        "required": ["business_date"],
    },
    trading_day_check=TradingDayCheckDefinition(
        check_host="calendar-check.company.net",
        check_user="calendaruser",
        command_template=(
            "/opt/calendar/bin/check_trading_day.sh "
            "--business-date {business_date} "
            "--market {market} "
            "--calendar-code {calendar_code}"
        ),
        market="APAC",
        calendar_code="TRADING_APAC",
        timeout_seconds=300,
    ),
    preset_params=None,
    command_timeout_seconds=3600,
    tags=("reporting", "recon", "report-a", "ssh", "kerberos"),
)


REPORT_A_SCHEDULED_VARIANTS = (
    ReportingScheduleVariant(
        dag_id="recon-report-a-apac-morning",
        title_suffix="APAC Morning",
        description_suffix="Scheduled APAC morning run.",
        schedule="10 9 * * 1-5",
        preset_params={
            "run_mode": "normal",
            "region": "APAC",
            "output_format": "csv",
        },
        env_overrides={
            "dev": {
                "schedule": "15 9 * * 1-5",
                "sudo_user": "reportuser_dev",
                "working_dir": "/opt/reporting/dev/recon-report-a",
                "remote_command_prefix": ["java", "-jar", "recon-report-a-dev.jar"],
                "preset_params": {
                    "spring_profile": "dev",
                    "legal_entity": "DEVLE",
                    "output_format": "csv",
                },
            },
            "qa": {
                "schedule": "10 9 * * 1-5",
                "sudo_user": "reportuser_qa",
                "working_dir": "/opt/reporting/qa/recon-report-a",
                "remote_command_prefix": ["java", "-jar", "recon-report-a-qa.jar"],
                "preset_params": {
                    "spring_profile": "qa",
                    "legal_entity": "QALE",
                    "output_format": "csv",
                },
            },
            "prod": {
                "schedule": "5 9 * * 1-5",
                "sudo_user": "reportuser_prod",
                "working_dir": "/opt/reporting/prod/recon-report-a",
                "remote_command_prefix": ["java", "-jar", "recon-report-a-prod.jar"],
                "preset_params": {
                    "spring_profile": "prod",
                    "legal_entity": "PRODLE",
                    "output_format": "xlsx",
                },
            },
            "dr": {
                "schedule": "20 9 * * 1-5",
                "sudo_user": "reportuser_dr",
                "working_dir": "/opt/reporting/dr/recon-report-a",
                "remote_command_prefix": ["java", "-jar", "recon-report-a-dr.jar"],
                "preset_params": {
                    "spring_profile": "dr",
                    "legal_entity": "DRLE",
                    "output_format": "csv",
                },
            },
        },
        tags_additional=("scheduled", "apac", "morning"),
    ),
)


REPORT_A_ADHOC_VARIANT = ReportingScheduleVariant(
    dag_id="recon-report-a-adhoc",
    title_suffix="Adhoc",
    description_suffix="Dedicated adhoc DAG with richer manual parameters.",
    schedule=None,
    preset_params={
        "run_mode": "adhoc",
    },
    adhoc_rules_override={
        "required": ["business_date"],
    },
    env_overrides={
        "dev": {
            "sudo_user": "reportuser_dev",
            "working_dir": "/opt/reporting/dev/recon-report-a",
            "remote_command_prefix": ["java", "-jar", "recon-report-a-dev.jar"],
            "preset_params": {
                "spring_profile": "dev",
                "region": "APAC",
                "legal_entity": "DEVLE",
            },
        },
        "qa": {
            "sudo_user": "reportuser_qa",
            "working_dir": "/opt/reporting/qa/recon-report-a",
            "remote_command_prefix": ["java", "-jar", "recon-report-a-qa.jar"],
            "preset_params": {
                "spring_profile": "qa",
                "region": "APAC",
                "legal_entity": "QALE",
            },
        },
        "prod": {
            "sudo_user": "reportuser_prod",
            "working_dir": "/opt/reporting/prod/recon-report-a",
            "remote_command_prefix": ["java", "-jar", "recon-report-a-prod.jar"],
            "preset_params": {
                "spring_profile": "prod",
                "region": "APAC",
                "legal_entity": "PRODLE",
            },
        },
        "dr": {
            "sudo_user": "reportuser_dr",
            "working_dir": "/opt/reporting/dr/recon-report-a",
            "remote_command_prefix": ["java", "-jar", "recon-report-a-dr.jar"],
            "preset_params": {
                "spring_profile": "dr",
                "region": "APAC",
                "legal_entity": "DRLE",
            },
        },
    },
    tags_additional=("adhoc",),
)


for _variant in REPORT_A_SCHEDULED_VARIANTS:
    _definition = create_reporting_definition_variant(
        base_definition=REPORT_A_BASE,
        variant=_variant,
    )
    globals()[_definition.dag_id.replace("-", "_")] = create_reporting_dag(definition=_definition)

_report_a_adhoc_definition = create_reporting_definition_variant(
    base_definition=REPORT_A_BASE,
    variant=REPORT_A_ADHOC_VARIANT,
)
recon_report_a_adhoc = create_reporting_dag(definition=_report_a_adhoc_definition)