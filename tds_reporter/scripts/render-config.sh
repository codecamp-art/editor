#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "${script_dir}/.." && pwd)"

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

template_path="$(resolve_template_path "${template_selector}")"
output_path="${2:-${root_dir}/config/report.properties}"

if [[ ! -f "${template_path}" ]]; then
    printf 'Template file does not exist: %s\n' "${template_path}" >&2
    exit 1
fi

mkdir -p "$(dirname "${output_path}")"

perl -pe 's/\$\{([^}:]+)(?::([^}]*))?\}/defined $ENV{$1} && length $ENV{$1} ? $ENV{$1} : (defined $2 ? $2 : "")/ge' \
    "${template_path}" > "${output_path}"

printf 'Rendered %s from %s\n' "${output_path}" "${template_path}"
