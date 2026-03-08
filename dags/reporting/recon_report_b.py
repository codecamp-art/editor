from common.reporting_workflow import ReportingDefinition, create_reporting_dag
from common.trading_calendar import TradingDayCheckDefinition


REPORT_B = ReportingDefinition(
    report_id="recon_report_b",
    dag_id="recon-report-b",
    title="Recon Report B",
    description="""
# Recon Report B

This DAG runs Report B on the remote reporting server.
""".strip(),
    schedule="20 9 * * 1-5",
    remote_script="/opt/reporting/bin/run_recon_report_b.sh",
    sudo_user="reportuser",
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
        }
    },
    adhoc_rules={"required_together": [["from_date", "to_date"]]},
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
    command_timeout_seconds=5400,
    tags=("reporting", "recon", "report-b", "ssh", "kerberos"),
)

dag = create_reporting_dag(definition=REPORT_B)