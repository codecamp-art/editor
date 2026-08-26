#!/bin/bash

set -Eeuo pipefail

# 防止调用方通过 bash -x 意外记录token和密码
set +x

# 此后创建的文件默认不允许group/other访问
umask 077

# 禁止生成可能包含密码或token的core dump
ulimit -c 0

# cron环境的PATH通常很精简
export PATH="/usr/local/bin:/usr/bin:/bin"

# ============================================================
# Configuration
# ============================================================

DEPLOY_ROOT="/d/d1/deployment"
CERT_DIR="${DEPLOY_ROOT}/.cert"

# 此PEM文件必须同时包含客户端证书和未加密私钥
PEM_FILE="${CERT_DIR}/<combined-client-cert-and-key>.pem"

VAULT_BASE_URL="https://vault.example.com/v1"
VAULT_NAMESPACE="<vault-namespace>"

# 保留你当前脚本中的模板，只替换被遮挡的部分
VAULT_SECRET_PATH_TEMPLATE="secret/<path>/{env}/<account-prefix>_{env}@<domain>"
VAULT_SECRET_KEY_TEMPLATE="<account-prefix>_{env}@<domain>"

# 对应Vault cert auth role/body中的name
VAULT_BODY_PREFIX="<vault-cert-role-prefix>"

# Kerberos realm，保留实际大小写
KERBEROS_REALM="<EXAMPLE.CN>"

CURL_CONNECT_TIMEOUT=10
CURL_MAX_TIME=30

# ============================================================
# Input validation
# ============================================================

ENV_INPUT="${1:-}"

if [[ -z "$ENV_INPUT" ]]; then
    echo "Usage: $0 <dev|qa|prod>" >&2
    exit 1
fi

# Bash原生转换为小写，不需要echo | tr
ENV_LOWER="${ENV_INPUT,,}"

case "$ENV_LOWER" in
    dev|qa|prod)
        ;;
    *)
        echo "Error: invalid environment: $ENV_INPUT" >&2
        echo "Allowed values: dev, qa, prod" >&2
        exit 1
        ;;
esac

for required_command in curl python3 kinit klist flock stat grep id; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        echo "Error: required command not found: $required_command" >&2
        exit 1
    fi
done

if [[ ! -f "$PEM_FILE" ]]; then
    echo "Error: combined PEM file not found: $PEM_FILE" >&2
    exit 1
fi

if [[ ! -r "$PEM_FILE" ]]; then
    echo "Error: combined PEM file is not readable: $PEM_FILE" >&2
    exit 1
fi

# 确认证书存在
if ! grep -q -- "-----BEGIN CERTIFICATE-----" "$PEM_FILE"; then
    echo "Error: no certificate found in PEM file" >&2
    exit 1
fi

# 确认未加密私钥存在
if ! grep -Eq -- \
    "-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----" \
    "$PEM_FILE"
then
    echo "Error: no supported private key found in PEM file" >&2
    exit 1
fi

# 加密私钥不适合当前无人值守cron实现
if grep -Eq -- \
    "-----BEGIN ENCRYPTED PRIVATE KEY-----|^Proc-Type: 4,ENCRYPTED|^DEK-Info:" \
    "$PEM_FILE"
then
    echo "Error: encrypted private key is not supported for unattended cron execution" >&2
    exit 1
fi

# PEM不能允许group或other读取
PEM_MODE="$(stat -Lc '%a' "$PEM_FILE")"

if (( (8#${PEM_MODE} & 077) != 0 )); then
    echo "Error: insecure PEM permissions: $PEM_MODE" >&2
    echo "Expected permissions such as 600 or 400" >&2
    exit 1
fi

# ============================================================
# Derived values
# ============================================================

VAULT_BODY_NAME="${VAULT_BODY_PREFIX}_${ENV_LOWER}"

VAULT_SECRET_PATH="${
    VAULT_SECRET_PATH_TEMPLATE//\{env\}/$ENV_LOWER
}"

VAULT_SECRET_KEY="${
    VAULT_SECRET_KEY_TEMPLATE//\{env\}/$ENV_LOWER
}"

PRINCIPAL="${VAULT_BODY_NAME}@${KERBEROS_REALM}"

# 保持原脚本使用的默认Kerberos cache位置。
# FILE:前缀明确指定为文件类型credential cache。
CACHE_FILE="/tmp/krb5cc_$(id -u)"
export KRB5CCNAME="FILE:${CACHE_FILE}"

# 所有环境共享同一个cache，因此也必须共享同一个锁
LOCK_FILE="${CERT_DIR}/.kinit_renew.lock"

# ============================================================
# Prevent overlapping cron runs
# ============================================================

exec 9>"$LOCK_FILE"

if ! flock -n 9; then
    echo "Another kinit refresh is already running; exiting" >&2
    exit 0
fi

# ============================================================
# Sensitive variables and cleanup
# ============================================================

VAULT_TOKEN=""
VAULT_PASSWORD=""

CURL_COMMON_OPTIONS=(
    --fail
    --silent
    --show-error
    --connect-timeout "$CURL_CONNECT_TIMEOUT"
    --max-time "$CURL_MAX_TIME"
)

# 使用stdin传递X-Vault-Token，避免token出现在curl命令行
vault_request() {
    local http_method="$1"
    local api_path="$2"

    {
        printf 'header = "X-Vault-Token: %s"\n' "$VAULT_TOKEN"
        printf 'header = "X-Vault-Namespace: %s"\n' "$VAULT_NAMESPACE"
    } |
        curl \
            "${CURL_COMMON_OPTIONS[@]}" \
            --config - \
            --request "$http_method" \
            "${VAULT_BASE_URL%/}/${api_path#/}"
}

cleanup() {
    local exit_code=$?

    # 防止EXIT trap递归触发
    trap - EXIT

    set +x

    # 如果中途失败但已取得token，尽量撤销
    if [[ -n "${VAULT_TOKEN:-}" ]]; then
        vault_request \
            POST \
            "auth/token/revoke-self" \
            >/dev/null 2>&1 || true
    fi

    unset VAULT_PASSWORD
    unset VAULT_TOKEN
    unset VAULT_SECRET_PATH
    unset VAULT_SECRET_KEY

    exit "$exit_code"
}

trap cleanup EXIT

# ============================================================
# Authenticate to Vault using combined PEM
# ============================================================

# 登录请求body通过stdin传递。
# PEM内已包含证书和私钥，所以不需要--key。
VAULT_TOKEN="$(
    printf '{"name":"%s"}' "$VAULT_BODY_NAME" |
        curl \
            "${CURL_COMMON_OPTIONS[@]}" \
            --request POST \
            --cert-type PEM \
            --cert "$PEM_FILE" \
            --header "X-Vault-Namespace: $VAULT_NAMESPACE" \
            --header "Content-Type: application/json" \
            --data-binary @- \
            "${VAULT_BASE_URL%/}/auth/cert/login" |
        python3 -c '
import json
import sys

response = json.load(sys.stdin)
token = response["auth"]["client_token"]

if not isinstance(token, str) or not token:
    raise ValueError("Vault returned an empty client token")

sys.stdout.write(token)
'
)"

if [[ -z "$VAULT_TOKEN" ]]; then
    echo "Error: failed to obtain Vault token" >&2
    exit 1
fi

# ============================================================
# Retrieve Kerberos password
# ============================================================

# vault_request通过curl --config -从stdin读取token。
# curl的响应再通过stdout传给Python，因此token和密码均不进入参数。
VAULT_PASSWORD="$(
    vault_request GET "$VAULT_SECRET_PATH" |
        python3 -c '
import json
import sys

secret_key = sys.argv[1]
response = json.load(sys.stdin)
password = response["data"]["data"][secret_key]

if not isinstance(password, str) or not password:
    raise ValueError("Vault returned an empty Kerberos password")

sys.stdout.write(password)
' "$VAULT_SECRET_KEY"
)"

if [[ -z "$VAULT_PASSWORD" ]]; then
    echo "Error: failed to retrieve Kerberos password from Vault" >&2
    exit 1
fi

# Secret读取成功后立即撤销Vault token，不等脚本结束
if ! vault_request \
    POST \
    "auth/token/revoke-self" \
    >/dev/null
then
    # 不输出token，只记录撤销失败
    echo "Warning: failed to revoke Vault token; it remains valid until TTL expiry" >&2
fi

unset VAULT_TOKEN

# ============================================================
# Obtain a new Kerberos TGT
# ============================================================

# printf是Bash builtin，密码只通过匿名管道进入kinit stdin。
# 密码不会成为kinit命令行参数或环境变量。
if ! printf '%s\n' "$VAULT_PASSWORD" |
    kinit -c "$KRB5CCNAME" "$PRINCIPAL"
then
    unset VAULT_PASSWORD
    echo "Error: kinit failed" >&2
    exit 1
fi

# 尽快删除shell变量引用
unset VAULT_PASSWORD

# kinit正常情况下会创建600权限的cache，这里再次强制确认
chmod 600 "$CACHE_FILE"

# ============================================================
# Verify TGT
# ============================================================

if ! klist -s -c "$KRB5CCNAME"; then
    echo "Error: Kerberos TGT verification failed" >&2
    exit 1
fi

echo "Kerberos TGT refreshed successfully for environment: $ENV_LOWER"
echo "Credential cache: $KRB5CCNAME"