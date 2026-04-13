from __future__ import annotations

from airflow.models.param import Param


COMMON_FIELDS = {
    "run_mode": {
        "type": "enum",
        "default": "normal",
        "values": ["normal", "adhoc"],
        "description": "normal for scheduled/default mode, adhoc for manual on-demand mode",
        "include_in_cli": False,
    },
    "skip_trading_day_check": {
        "type": "boolean",
        "default": False,
        "description": "Set true to bypass weekday trading-day check",
        "include_in_cli": False,
    },
    "extra_args": {
        "type": "string",
        "default": "",
        "description": "Optional extra CLI args appended to the remote command",
        "include_in_cli": False,
    },
}


SYSTEMD_CONTROL_FIELDS = {
    "target_mode": {
        "type": "enum",
        "default": "workflow",
        "values": ["workflow", "single_process"],
        "description": "Run the full workflow or a single process only",
        "include_in_cli": False,
    },
    "target_process": {
        "type": "string",
        "default": "",
        "description": "Required when target_mode is single_process",
        "include_in_cli": False,
    },
}


def merge_field_definitions(*field_dicts: dict) -> dict:
    merged: dict = {}
    for item in field_dicts:
        merged.update(item)
    return merged


def normalize_empty(value):
    if value is None:
        return None
    if isinstance(value, str) and value.strip() == "":
        return None
    return value


def build_airflow_params_from_fields(fields: dict) -> dict:
    params = {}

    for field_name, spec in fields.items():
        field_type = spec["type"]
        default = spec.get("default")
        description = spec.get("description", "")
        values_display = spec.get("values_display")

        if field_type == "enum":
            param_kwargs = {
                "default": default,
                "enum": spec["values"],
                "description": description,
            }
            if values_display:
                param_kwargs["values_display"] = values_display
            params[field_name] = Param(**param_kwargs)
        elif field_type == "multi_enum":
            param_kwargs = {
                "default": list(default or []),
                "type": "array",
                "items": {
                    "type": "string",
                    "enum": spec["values"],
                },
                "examples": spec["values"],
                "description": description,
            }
            if values_display:
                param_kwargs["values_display"] = values_display
            params[field_name] = Param(**param_kwargs)
        elif field_type == "boolean":
            params[field_name] = Param(
                default=default,
                type=["boolean", "null"],
                description=description,
            )
        elif field_type == "integer":
            params[field_name] = Param(
                default=default,
                type=["integer", "null"],
                description=description,
            )
        else:
            params[field_name] = Param(
                default=default,
                type=["string", "null"],
                description=description,
            )

    return params


def validate_fields(raw_params: dict, fields: dict) -> dict:
    cleaned = {}

    for field_name, spec in fields.items():
        field_type = spec["type"]
        value = raw_params.get(field_name)
        value = normalize_empty(value)

        if value is None and "default" in spec:
            value = spec["default"]

        if field_type == "boolean":
            value = bool(value) if value is not None else None

        elif field_type == "integer":
            if value is not None:
                if isinstance(value, bool):
                    raise ValueError(f"{field_name} must be an integer, got boolean.")
                try:
                    value = int(value)
                except (TypeError, ValueError) as exc:
                    raise ValueError(f"{field_name} must be an integer.") from exc

        elif field_type == "enum":
            if value is not None and value not in spec["values"]:
                raise ValueError(
                    f"{field_name} must be one of {spec['values']}, got '{value}'."
                )

        elif field_type == "multi_enum":
            if value is None:
                value = []
            elif isinstance(value, tuple):
                value = list(value)
            elif not isinstance(value, list):
                raise ValueError(f"{field_name} must be a list of values.")

            invalid_values = [item for item in value if item not in spec["values"]]
            if invalid_values:
                raise ValueError(
                    f"{field_name} must only contain values from {spec['values']}, "
                    f"got invalid values {invalid_values}."
                )

        elif field_type == "string":
            if value is not None and not isinstance(value, str):
                raise ValueError(f"{field_name} must be a string.")

        else:
            raise ValueError(f"Unsupported field type '{field_type}' for {field_name}.")

        cleaned[field_name] = value

    return cleaned
