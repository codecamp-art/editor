#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "${script_dir}/.." && pwd)"

template_path="${1:-${root_dir}/config/tds_reporter.properties.template}"
output_path="${2:-${root_dir}/config/tds_reporter.properties}"

mkdir -p "$(dirname "${output_path}")"

perl -pe 's/\$\{([^}:]+)(?::([^}]*))?\}/defined $ENV{$1} && length $ENV{$1} ? $ENV{$1} : (defined $2 ? $2 : "")/ge' \
    "${template_path}" > "${output_path}"

printf 'Rendered %s from %s\n' "${output_path}" "${template_path}"
