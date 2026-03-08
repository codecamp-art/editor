from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TradingDayCheckDefinition:
    """
    Declarative config for remote trading-day check.

    check_host:
        SSH target host used to run the DB query command.

    check_user:
        User to sudo into on the remote host before running the command.

    command_template:
        Remote shell command template. It should print one of:
        - Y / YES / TRUE / 1 => trading day
        - N / NO / FALSE / 0 => non-trading day

        You can use:
        - {business_date}
        - {market}
        - {calendar_code}
    """
    check_host: str
    check_user: str
    command_template: str
    market: str = ""
    calendar_code: str = ""
    timeout_seconds: int = 300