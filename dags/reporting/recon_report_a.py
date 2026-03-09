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
        },
        # manual-only richer fields can live here and be hidden/unused by scheduled presets
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
        tags_additional=("scheduled", "apac", "morning"),
    ),
    ReportingScheduleVariant(
        dag_id="recon-report-a-emea-midday",
        title_suffix="EMEA Midday",
        description_suffix="Scheduled EMEA midday run.",
        schedule="10 12 * * 1-5",
        preset_params={
            "run_mode": "normal",
            "region": "EMEA",
            "output_format": "csv",
        },
        tags_additional=("scheduled", "emea", "midday"),
    ),
    ReportingScheduleVariant(
        dag_id="recon-report-a-apac-eod",
        title_suffix="APAC EOD",
        description_suffix="Scheduled APAC end-of-day run.",
        schedule="10 18 * * 1-5",
        preset_params={
            "run_mode": "normal",
            "region": "APAC",
            "output_format": "xlsx",
        },
        tags_additional=("scheduled", "apac", "eod"),
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
    # optional: stronger adhoc rules here if you want
    adhoc_rules_override={
        "required": ["business_date"],
    },
    tags_additional=("adhoc",),
)


# Create scheduled DAGs
for _variant in REPORT_A_SCHEDULED_VARIANTS:
    _definition = create_reporting_definition_variant(
        base_definition=REPORT_A_BASE,
        variant=_variant,
    )
    globals()[_definition.dag_id.replace("-", "_")] = create_reporting_dag(definition=_definition)

# Create dedicated adhoc DAG
_report_a_adhoc_definition = create_reporting_definition_variant(
    base_definition=REPORT_A_BASE,
    variant=REPORT_A_ADHOC_VARIANT,
)
recon_report_a_adhoc = create_reporting_dag(definition=_report_a_adhoc_definition)