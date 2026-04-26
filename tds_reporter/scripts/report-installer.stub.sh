#!/usr/bin/env bash
set -euo pipefail

default_install_dir_name='@REPORT_DEFAULT_INSTALL_DIR_NAME@'
install_marker_file='.report-install-root'

usage() {
    cat <<EOF
Usage:
  ${0##*/} --env <dev|qa|prod> [--prefix /path/to/install]
  ${0##*/} <dev|qa|prod> [--prefix /path/to/install]

Behavior:
  1. Extract the packaged runtime into the install directory
  2. Render config/report.properties for the selected environment
  3. Leave a ready-to-run standalone installation behind
  4. If the target directory already contains a previous report install, replace it automatically

Options:
  --env <name>      Environment name: dev, qa, or prod
  --prefix <path>   Install directory. Default: \$PWD/${default_install_dir_name}
  -h, --help        Show this help
EOF
}

env_name=""
install_prefix=""

looks_like_report_install() {
    local target_dir="$1"
    [ -f "${target_dir}/${install_marker_file}" ] || {
        [ -x "${target_dir}/bin/report" ] &&
        [ -d "${target_dir}/config" ] &&
        {
            [ -f "${target_dir}/config/report.properties" ] ||
            [ -f "${target_dir}/config/report.properties.template" ]
        }
    }
}

render_env_references() {
    local rendered="$1"
    local match name fallback value

    while [[ "${rendered}" =~ \$\{([A-Za-z_][A-Za-z0-9_]*)(:([^}]*))?\} ]]; do
        match="${BASH_REMATCH[0]}"
        name="${BASH_REMATCH[1]}"
        fallback="${BASH_REMATCH[3]:-}"
        value="${!name:-}"

        if [ -z "${value}" ]; then
            value="${fallback}"
        fi

        rendered="${rendered/"${match}"/${value}}"
    done

    printf '%s\n' "${rendered}"
}

append_rendered_file() {
    local input_path="$1"
    local output_path="$2"
    local line

    while IFS= read -r line || [ -n "${line}" ]; do
        render_env_references "${line}"
    done < "${input_path}" >> "${output_path}"
}

render_config() {
    local selected_env="$1"
    local output_path="$2"
    local base_template_path="${install_prefix}/config/report.properties.template"
    local overlay_path="${install_prefix}/config/${selected_env}.properties"

    if [ ! -f "${base_template_path}" ]; then
        printf 'Base template does not exist: %s\n' "${base_template_path}" >&2
        exit 1
    fi
    if [ ! -f "${overlay_path}" ]; then
        printf 'Environment overlay does not exist: %s\n' "${overlay_path}" >&2
        exit 1
    fi

    mkdir -p "$(dirname "${output_path}")"
    : > "${output_path}"
    append_rendered_file "${base_template_path}" "${output_path}"
    printf '\n' >> "${output_path}"
    append_rendered_file "${overlay_path}" "${output_path}"
}

ensure_safe_install_prefix() {
    local target_dir="$1"
    case "${target_dir}" in
        ""|"/"|"/home"|"/root"|"/usr"|"/usr/local"|"/opt"|"/var"|"/tmp")
            printf 'Refusing to install into unsafe directory: %s\n' "${target_dir}" >&2
            exit 1
            ;;
    esac

    if [ "${target_dir}" = "${HOME:-}" ]; then
        printf 'Refusing to install into the home directory itself: %s\n' "${target_dir}" >&2
        exit 1
    fi
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --env)
            shift
            env_name="${1:-}"
            ;;
        --env=*)
            env_name="${1#*=}"
            ;;
        --prefix)
            shift
            install_prefix="${1:-}"
            ;;
        --prefix=*)
            install_prefix="${1#*=}"
            ;;
        --force)
            printf 'Warning: --force is no longer needed and is ignored.\n' >&2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            if [ -z "${env_name}" ]; then
                env_name="$1"
            else
                printf 'Unknown argument: %s\n' "$1" >&2
                usage >&2
                exit 1
            fi
            ;;
    esac
    shift
done

case "${env_name}" in
    dev|qa|prod)
        ;;
    *)
        printf 'An environment is required: dev, qa, or prod\n' >&2
        usage >&2
        exit 1
        ;;
esac

if [ -z "${install_prefix}" ]; then
    install_prefix="${PWD}/${default_install_dir_name}"
fi

install_parent="$(dirname "${install_prefix}")"
mkdir -p "${install_parent}"
install_prefix="$(cd "${install_parent}" && pwd)/$(basename "${install_prefix}")"
ensure_safe_install_prefix "${install_prefix}"

if [ -e "${install_prefix}" ]; then
    if [ -d "${install_prefix}" ] && [ -z "$(ls -A "${install_prefix}" 2>/dev/null)" ]; then
        :
    elif [ -d "${install_prefix}" ] && looks_like_report_install "${install_prefix}"; then
        rm -rf "${install_prefix}"
    else
        printf 'Install directory already exists but is not recognized as a previous report install: %s\n' "${install_prefix}" >&2
        printf 'Choose another --prefix or remove that directory manually.\n' >&2
        exit 1
    fi
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/${default_install_dir_name}.XXXXXX")"
cleanup() {
    rm -rf "${tmp_dir}"
}
trap cleanup EXIT

archive_line="$(awk '{ line = $0; sub(/\r$/, "", line); if (line == "__ARCHIVE_BELOW__") { print NR + 1; exit 0; } }' "$0")"
if [ -z "${archive_line}" ]; then
    printf 'Installer payload marker was not found.\n' >&2
    exit 1
fi

mkdir -p "${install_prefix}"
tail -n +"${archive_line}" "$0" | tar -xzf - -C "${tmp_dir}"
cp -a "${tmp_dir}/." "${install_prefix}/"

if [ ! -x "${install_prefix}/bin/report" ]; then
    printf 'Installed runtime is incomplete: %s/bin/report\n' "${install_prefix}" >&2
    exit 1
fi

render_config "${env_name}" "${install_prefix}/config/report.properties"
rm -f \
    "${install_prefix}/config/dev.properties" \
    "${install_prefix}/config/qa.properties" \
    "${install_prefix}/config/prod.properties" \
    "${install_prefix}/config/report.properties.template"
printf 'installed_by=%s\n' "${0##*/}" > "${install_prefix}/${install_marker_file}"

cat <<EOF
Installed report to: ${install_prefix}
Rendered config/report.properties for environment: ${env_name}

Run:
  ${install_prefix}/bin/report

Switch environment later by re-running this installer with another --env value.
EOF
exit 0
__ARCHIVE_BELOW__
