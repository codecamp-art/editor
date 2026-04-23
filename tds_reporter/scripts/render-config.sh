#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "${script_dir}/.." && pwd)"
base_template_path="${root_dir}/config/report.properties.template"

resolve_template_path() {
    local selector="$1"

    case "${selector}" in
        dev|qa|prod)
            printf '%s/config/%s.properties' "${root_dir}" "${selector}"
            return 0
            ;;
    esac

    if [[ -n "${selector}" ]]; then
        printf '%s' "${selector}"
        return 0
    fi

    return 1
}

template_selector="${1:-${APP_ENV:-}}"
if [[ -z "${template_selector}" ]]; then
    printf 'Usage: %s <dev|qa|prod|template-path> [output-path]\n' "${0##*/}" >&2
    exit 1
fi

output_path="${2:-${root_dir}/config/report.properties}"
mkdir -p "$(dirname "${output_path}")"

if [[ "${template_selector}" == "dev" || "${template_selector}" == "qa" || "${template_selector}" == "prod" ]]; then
    overlay_path="$(resolve_template_path "${template_selector}")"
    if [[ ! -f "${base_template_path}" ]]; then
        printf 'Base template does not exist: %s\n' "${base_template_path}" >&2
        exit 1
    fi
    if [[ ! -f "${overlay_path}" ]]; then
        printf 'Environment overlay does not exist: %s\n' "${overlay_path}" >&2
        exit 1
    fi

    {
        cat "${base_template_path}"
        printf '\n'
        cat "${overlay_path}"
    } | perl -pe 's/\$\{([^}:]+)(?::([^}]*))?\}/defined $ENV{$1} && length $ENV{$1} ? $ENV{$1} : (defined $2 ? $2 : "")/ge' \
        > "${output_path}"

    printf 'Rendered %s from %s + %s\n' "${output_path}" "${base_template_path}" "${overlay_path}"
else
    template_path="$(resolve_template_path "${template_selector}")"
    if [[ ! -f "${template_path}" ]]; then
        printf 'Template file does not exist: %s\n' "${template_path}" >&2
        exit 1
    fi

    perl -pe 's/\$\{([^}:]+)(?::([^}]*))?\}/defined $ENV{$1} && length $ENV{$1} ? $ENV{$1} : (defined $2 ? $2 : "")/ge' \
        "${template_path}" > "${output_path}"

    printf 'Rendered %s from %s\n' "${output_path}" "${template_path}"
fi
