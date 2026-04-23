#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "${script_dir}/bin" && -d "${script_dir}/config" ]]; then
    package_root="${script_dir}"
else
    package_root="$(cd "${script_dir}/.." && pwd)"
fi

env_name="${1:-}"
if [[ -z "${env_name}" ]]; then
    printf 'Usage: %s <dev|qa|prod> [report args...]\n' "${0##*/}" >&2
    exit 1
fi
shift

"${package_root}/scripts/render-config.sh" "${env_name}" "${package_root}/config/report.properties"
"${package_root}/bin/report" "$@"
