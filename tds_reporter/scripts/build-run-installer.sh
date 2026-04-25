#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 3 ]; then
    printf 'Usage: %s <stage-dir> <output.run> <default-install-dir-name>\n' "${0##*/}" >&2
    exit 1
fi

stage_dir="$1"
output_path="$2"
default_install_dir_name="$3"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
stub_path="${script_dir}/report-installer.stub.sh"

if [ ! -d "${stage_dir}" ]; then
    printf 'Stage directory does not exist: %s\n' "${stage_dir}" >&2
    exit 1
fi

if [ ! -f "${stub_path}" ]; then
    printf 'Installer stub does not exist: %s\n' "${stub_path}" >&2
    exit 1
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/report-run-installer.XXXXXX")"
cleanup() {
    rm -rf "${tmp_dir}"
}
trap cleanup EXIT

payload_path="${tmp_dir}/payload.tar.gz"
mkdir -p "$(dirname "${output_path}")"

tar -C "${stage_dir}" -czf "${payload_path}" .
awk -v default_install_dir_name="${default_install_dir_name}" '
{
    line = $0
    sub(/\r$/, "", line)
    if (line == "__ARCHIVE_BELOW__") {
        exit 0
    }
    gsub(/@REPORT_DEFAULT_INSTALL_DIR_NAME@/, default_install_dir_name, line)
    print line
}
' "${stub_path}" > "${output_path}"
printf '%s\n' "__ARCHIVE_BELOW__" >> "${output_path}"
cat "${payload_path}" >> "${output_path}"
chmod +x "${output_path}"

printf 'Built self-extracting installer %s from %s\n' "${output_path}" "${stage_dir}"
