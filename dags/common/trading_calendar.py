from __future__ import annotations

from datetime import datetime


def is_weekday_trading_day(logical_date: datetime) -> bool:
    return logical_date.weekday() < 5