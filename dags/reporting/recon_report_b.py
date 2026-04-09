from common.remote_script_workflow import (
    ReportingDefinition,
    ReportingScheduleVariant,
    create_remote_script_dag,
    create_reporting_definition_variant,
)
from common.trading_calendar import TradingDayCheckDefinition


REPORT_B_BASE = ReportingDefinition(
    report_id="recon_report_b",
    dag_id="recon-report-b-base-not-used-directly",
    title="Recon Report B",
    description="""
# Recon Report B

This report supports multiple scheduled DAG variants and one dedicated adhoc DAG.
""".strip(),
    schedule=None,
    remote_script="/opt/reporting/bin/run_recon_report_b.sh",
    sudo_user="reportuser_default",
    fields={
        "from_date": {
            "type": "string",
            "default": "",
            "description": "Start date in YYYY-MM-DD format",
            "cli_name": "fromDate",
        },
        "to_date": {
            "type": "string",
            "default": "",
            "description": "End date in YYYY-MM-DD format",
            "cli_name": "toDate",
        },
        "book": {
            "type": "string",
            "default": "",
            "description": "Optional book name",
            "cli_name": "book",
        },
        "include_exceptions": {
            "type": "boolean",
            "default": False,
            "description": "Whether to include exceptions",
            "cli_name": "includeExceptions",
            "transform": "lower_bool",
        },
        "notify_email": {
            "type": "string",
            "default": "",
            "description": "Optional notification email",
            "cli_name": "notifyEmail",
        },
        "desk": {
            "type": "string",
            "default": "",
            "description": "Optional desk filter for adhoc runs",
            "cli_name": "desk",
        },
    },
    adhoc_rules={
        "required_together": [["from_date", "to_date"]],
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
    command_timeout_seconds=5400,
    tags=("reporting", "recon", "report-b", "ssh"),
)


REPORT_B_SCHEDULED_VARIANTS = (
    ReportingScheduleVariant(
        dag_id="recon-report-b-book1-morning",
        title_suffix="Book1 Morning",
        description_suffix="Scheduled Book1 morning run.",
        schedule="20 9 * * 1-5",
        preset_params={
            "run_mode": "normal",
            "book": "BOOK1",
            "include_exceptions": False,
        },
        env_overrides={
            "dev": {
                "schedule": "25 9 * * 1-5",
                "sudo_user": "reportuser_dev_b",
                "preset_params": {
                    "notify_email": "dev-ops@example.com",
                },
            },
            "qa": {
                "schedule": "20 9 * * 1-5",
                "sudo_user": "reportuser_qa_b",
                "preset_params": {
                    "notify_email": "qa-ops@example.com",
                },
            },
            "prod": {
                "schedule": "10 9 * * 1-5",
                "sudo_user": "reportuser_prod_b",
                "preset_params": {
                    "notify_email": "prod-ops@example.com",
                },
            },
        },
        tags_additional=("scheduled", "book1", "morning"),
    ),
    ReportingScheduleVariant(
        dag_id="recon-report-b-book2-evening",
        title_suffix="Book2 Evening",
        description_suffix="Scheduled Book2 evening run.",
        schedule="20 18 * * 1-5",
        preset_params={
            "run_mode": "normal",
            "book": "BOOK2",
            "include_exceptions": True,
        },
        env_overrides={
            "dev": {
                "schedule": "25 18 * * 1-5",
                "sudo_user": "reportuser_dev_b",
                "preset_params": {
                    "notify_email": "dev-ops@example.com",
                },
            },
            "qa": {
                "schedule": "20 18 * * 1-5",
                "sudo_user": "reportuser_qa_b",
                "preset_params": {
                    "notify_email": "qa-ops@example.com",
                },
            },
            "prod": {
                "schedule": "10 18 * * 1-5",
                "sudo_user": "reportuser_prod_b",
                "preset_params": {
                    "notify_email": "prod-ops@example.com",
                },
            },
        },
        tags_additional=("scheduled", "book2", "evening"),
    ),
)


REPORT_B_ADHOC_VARIANT = ReportingScheduleVariant(
    dag_id="recon-report-b-adhoc",
    title_suffix="Adhoc",
    description_suffix="Dedicated adhoc DAG with richer manual parameters.",
    schedule=None,
    preset_params={
        "run_mode": "adhoc",
    },
    adhoc_rules_override={
        "required_together": [["from_date", "to_date"]],
    },
    env_overrides={
        "dev": {
            "sudo_user": "reportuser_dev_b",
            "preset_params": {
                "notify_email": "dev-ops@example.com",
            },
        },
        "qa": {
            "sudo_user": "reportuser_qa_b",
            "preset_params": {
                "notify_email": "qa-ops@example.com",
            },
        },
        "prod": {
            "sudo_user": "reportuser_prod_b",
            "preset_params": {
                "notify_email": "prod-ops@example.com",
            },
        },
    },
    tags_additional=("adhoc",),
)


for _variant in REPORT_B_SCHEDULED_VARIANTS:
    _definition = create_reporting_definition_variant(
        base_definition=REPORT_B_BASE,
        variant=_variant,
    )
    _dag = create_remote_script_dag(
        definition=_definition,
        source_file=__file__,
    )
    if _dag is not None:
        globals()[_definition.dag_id.replace("-", "_")] = _dag

_report_b_adhoc_definition = create_reporting_definition_variant(
    base_definition=REPORT_B_BASE,
    variant=REPORT_B_ADHOC_VARIANT,
)
_report_b_adhoc_dag = create_remote_script_dag(
    definition=_report_b_adhoc_definition,
    source_file=__file__,
)
if _report_b_adhoc_dag is not None:
    recon_report_b_adhoc = _report_b_adhoc_dag
