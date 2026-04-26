import groovy.json.JsonSlurperClassic

String shellQuote(String value) {
  return "'" + (value ?: '').replace("'", "'\"'\"'") + "'"
}

String trimSlashes(String value) {
  String result = value ?: ''
  while (result.startsWith('/')) {
    result = result.substring(1)
  }
  while (result.endsWith('/')) {
    result = result.substring(0, result.length() - 1)
  }
  return result
}

Map fixedIntegrationConfig(String pipelineName) {
  // Replace these fixed values in code only; Jenkins UI should not provide them.
  // Set optional Vault paths to '' when that material is not required.
  Map shared = [
    ARTIFACTORY_PACKAGE_URL: 'REPLACE_ME_SUPPLIER_PACKAGE_URL',
    CURL_BIN: 'curl',
    ARTIFACTORY_CERT_TYPE: 'PEM'
  ]

  Map pr = [
    VAULT_ADDR: 'REPLACE_ME_PR_VAULT_ADDR',
    VAULT_NAMESPACE: 'REPLACE_ME_PR_VAULT_NAMESPACE',
    VAULT_AUTH_PATH: 'kerberos',
    ARTIFACTORY_CERT_VAULT_PATH: 'REPLACE_ME_PR_ARTIFACTORY_CERT_VAULT_PATH',
    ARTIFACTORY_CERT_VAULT_FIELD: 'cert',
    ARTIFACTORY_KEY_VAULT_PATH: 'REPLACE_ME_PR_ARTIFACTORY_KEY_VAULT_PATH',
    ARTIFACTORY_KEY_VAULT_FIELD: 'key',
    ARTIFACTORY_CA_VAULT_PATH: 'REPLACE_ME_PR_ARTIFACTORY_CA_VAULT_PATH',
    ARTIFACTORY_CA_VAULT_FIELD: 'ca_cert',
    ARTIFACTORY_CERT_PASSWORD_VAULT_PATH: 'REPLACE_ME_PR_ARTIFACTORY_CERT_PASSWORD_VAULT_PATH',
    ARTIFACTORY_CERT_PASSWORD_VAULT_FIELD: 'password',
    VENDOR_PACKAGE_PASSWORD_VAULT_PATH: 'REPLACE_ME_PR_VENDOR_PACKAGE_PASSWORD_VAULT_PATH',
    VENDOR_PACKAGE_PASSWORD_VAULT_FIELD: 'password'
  ]

  Map release = [
    VAULT_ADDR: 'REPLACE_ME_RELEASE_VAULT_ADDR',
    VAULT_NAMESPACE: 'REPLACE_ME_RELEASE_VAULT_NAMESPACE',
    VAULT_AUTH_PATH: 'kerberos',
    ARTIFACTORY_CERT_VAULT_PATH: 'REPLACE_ME_RELEASE_ARTIFACTORY_CERT_VAULT_PATH',
    ARTIFACTORY_CERT_VAULT_FIELD: 'cert',
    ARTIFACTORY_KEY_VAULT_PATH: 'REPLACE_ME_RELEASE_ARTIFACTORY_KEY_VAULT_PATH',
    ARTIFACTORY_KEY_VAULT_FIELD: 'key',
    ARTIFACTORY_CA_VAULT_PATH: 'REPLACE_ME_RELEASE_ARTIFACTORY_CA_VAULT_PATH',
    ARTIFACTORY_CA_VAULT_FIELD: 'ca_cert',
    ARTIFACTORY_CERT_PASSWORD_VAULT_PATH: 'REPLACE_ME_RELEASE_ARTIFACTORY_CERT_PASSWORD_VAULT_PATH',
    ARTIFACTORY_CERT_PASSWORD_VAULT_FIELD: 'password',
    VENDOR_PACKAGE_PASSWORD_VAULT_PATH: 'REPLACE_ME_RELEASE_VENDOR_PACKAGE_PASSWORD_VAULT_PATH',
    VENDOR_PACKAGE_PASSWORD_VAULT_FIELD: 'password'
  ]

  Map selected = pipelineName == 'release' ? release : pr
  return shared + selected
}

void validateFixedIntegrationConfig(Map config, String pipelineName) {
  List<String> requiredKeys = [
    'ARTIFACTORY_PACKAGE_URL',
    'VAULT_ADDR',
    'ARTIFACTORY_CERT_VAULT_PATH',
    'ARTIFACTORY_CERT_VAULT_FIELD',
    'VENDOR_PACKAGE_PASSWORD_VAULT_PATH',
    'VENDOR_PACKAGE_PASSWORD_VAULT_FIELD'
  ]

  List<String> invalidKeys = []
  config.each { key, rawValue ->
    String value = rawValue?.toString()?.trim() ?: ''
    if (value.startsWith('REPLACE_ME_')) {
      invalidKeys << key.toString()
    }
  }

  requiredKeys.each { key ->
    String value = config[key]?.toString()?.trim() ?: ''
    if (!value) {
      invalidKeys << key
    }
  }

  if (invalidKeys) {
    error("Fixed Jenkins integration config for ${pipelineName} is incomplete: ${invalidKeys.unique().join(', ')}")
  }
}

Map vaultConfigFromParams(def pipelineParams) {
  return [
    curlBin: pipelineParams.CURL_BIN?.trim() ?: 'curl',
    address: pipelineParams.VAULT_ADDR?.trim() ?: '',
    namespace: pipelineParams.VAULT_NAMESPACE?.trim() ?: '',
    authPath: pipelineParams.VAULT_AUTH_PATH?.trim() ?: ''
  ]
}

String vaultApiUrl(Map vault, String apiPath) {
  String address = (vault.address ?: '').trim()
  if (!address) {
    error('VAULT_ADDR is required when Jenkins needs Vault access')
  }
  while (address.endsWith('/')) {
    address = address.substring(0, address.length() - 1)
  }
  return "${address}/v1/${trimSlashes(apiPath)}"
}

String vaultCurl(Map vault, String method, String apiPath, List<String> extraArgs = []) {
  String curlBin = (vault.curlBin ?: 'curl').trim()
  String namespace = (vault.namespace ?: '').trim()
  String namespaceArg = namespace ? "--header ${shellQuote("X-Vault-Namespace: ${namespace}")}" : ''

  return sh(
    returnStdout: true,
    script: """#!/usr/bin/env bash
set -euo pipefail
${shellQuote(curlBin)} --fail --silent --show-error --request ${shellQuote(method)} ${extraArgs.join(' ')} ${namespaceArg} ${shellQuote(vaultApiUrl(vault, apiPath))}
"""
  ).trim()
}

String vaultKerberosLogin(Map vault) {
  String authPath = trimSlashes((vault.authPath ?: '').trim() ?: 'kerberos')
  if (!authPath.startsWith('auth/')) {
    authPath = "auth/${authPath}"
  }

  def payload = new JsonSlurperClassic().parseText(
    vaultCurl(vault, 'POST', "${authPath}/login", ['--negotiate', '--user', ':'])
  )
  String token = payload?.auth?.client_token?.toString() ?: ''
  if (!token) {
    error('Vault HTTP login did not return auth.client_token')
  }
  return token
}

String prepareVaultAccess(def pipelineParams) {
  if (!pipelineParams.VAULT_ADDR?.trim()) {
    error('VAULT_ADDR is required when Jenkins needs Vault access')
  }
  return vaultKerberosLogin(vaultConfigFromParams(pipelineParams))
}

String vaultKvGet(Map vault, String token, String secretPath, String field) {
  String normalized = trimSlashes(secretPath)
  int delimiter = normalized.indexOf('/')
  if (delimiter <= 0 || delimiter + 1 >= normalized.length()) {
    error("Vault path must include mount and path: ${secretPath}")
  }

  String mount = normalized.substring(0, delimiter)
  String rest = normalized.substring(delimiter + 1)
  String lastError = ''

  for (String candidatePath : ["${mount}/data/${rest}", "${mount}/${rest}"]) {
    try {
      def payload = new JsonSlurperClassic().parseText(
        vaultCurl(vault, 'GET', candidatePath, ["--header ${shellQuote("X-Vault-Token: ${token}")}"])
      )
      def data = payload?.data
      def value = null
      if (data instanceof Map) {
        if (data.data instanceof Map && data.data.containsKey(field)) {
          value = data.data[field]
        }
        if (value == null && data.containsKey(field)) {
          value = data[field]
        }
      }
      if (value != null && value.toString().length() > 0) {
        return value.toString()
      }
      lastError = "field was empty or missing: ${secretPath}#${field}"
    } catch (Exception ex) {
      lastError = ex.message ?: ex.toString()
    }
  }

  error("Vault HTTP read failed for ${secretPath}#${field}: ${lastError}")
}

String optionalVaultSecret(def pipelineParams, String token, String pathParam, String fieldParam, String fallbackField) {
  String secretPath = pipelineParams[pathParam]?.trim() ?: ''
  if (!secretPath) {
    return ''
  }
  String field = pipelineParams[fieldParam]?.trim() ?: fallbackField
  return vaultKvGet(vaultConfigFromParams(pipelineParams), token, secretPath, field)
}

void prepareArtifactoryAuthFiles(def pipelineParams, String token) {
  if (!pipelineParams.ARTIFACTORY_CERT_VAULT_PATH?.trim()) {
    error('ARTIFACTORY_CERT_VAULT_PATH is required when ARTIFACTORY_PACKAGE_URL is set')
  }

  Map vaultConfig = vaultConfigFromParams(pipelineParams)
  String cert = vaultKvGet(
    vaultConfig,
    token,
    pipelineParams.ARTIFACTORY_CERT_VAULT_PATH.trim(),
    pipelineParams.ARTIFACTORY_CERT_VAULT_FIELD?.trim() ?: 'cert'
  )
  writeFile(file: "${env.ARTIFACTORY_AUTH_DIR}/client-cert", text: cert)

  if (pipelineParams.ARTIFACTORY_KEY_VAULT_PATH?.trim()) {
    writeFile(
      file: "${env.ARTIFACTORY_AUTH_DIR}/client-key",
      text: vaultKvGet(
        vaultConfig,
        token,
        pipelineParams.ARTIFACTORY_KEY_VAULT_PATH.trim(),
        pipelineParams.ARTIFACTORY_KEY_VAULT_FIELD?.trim() ?: 'key'
      )
    )
  }

  if (pipelineParams.ARTIFACTORY_CA_VAULT_PATH?.trim()) {
    writeFile(
      file: "${env.ARTIFACTORY_AUTH_DIR}/ca-cert",
      text: vaultKvGet(
        vaultConfig,
        token,
        pipelineParams.ARTIFACTORY_CA_VAULT_PATH.trim(),
        pipelineParams.ARTIFACTORY_CA_VAULT_FIELD?.trim() ?: 'ca_cert'
      )
    )
  }
}

void prepareVendorSdk(Map args) {
  def pipelineParams = args.params
  String token = args.vaultToken ?: ''
  String vendorZipPassword = optionalVaultSecret(
    pipelineParams,
    token,
    'VENDOR_PACKAGE_PASSWORD_VAULT_PATH',
    'VENDOR_PACKAGE_PASSWORD_VAULT_FIELD',
    'password'
  )

  if (pipelineParams.ARTIFACTORY_PACKAGE_URL?.trim()) {
    if (!token?.trim()) {
      error('ARTIFACTORY_PACKAGE_URL requires a Kerberos-authenticated Vault session so Jenkins can fetch the Artifactory certificate')
    }

    sh '''#!/usr/bin/env bash
      set -euo pipefail
      rm -rf "$VENDOR_DOWNLOAD_DIR" "$VENDOR_EXTRACT_DIR" "$VENDOR_TDS_DIR" "$ARTIFACTORY_AUTH_DIR"
      mkdir -p "$VENDOR_DOWNLOAD_DIR" "$VENDOR_EXTRACT_DIR" "$VENDOR_TDS_DIR/include" "$VENDOR_TDS_DIR/linux_x86_64" "$ARTIFACTORY_AUTH_DIR"
    '''

    prepareArtifactoryAuthFiles(pipelineParams, token)
    String artifactoryCertPassword = optionalVaultSecret(
      pipelineParams,
      token,
      'ARTIFACTORY_CERT_PASSWORD_VAULT_PATH',
      'ARTIFACTORY_CERT_PASSWORD_VAULT_FIELD',
      'password'
    )

    withEnv([
      "CURL_BIN=${pipelineParams.CURL_BIN?.trim() ?: 'curl'}",
      "ARTIFACTORY_PACKAGE_URL=${pipelineParams.ARTIFACTORY_PACKAGE_URL.trim()}",
      "ARTIFACTORY_CERT_FILE=${env.ARTIFACTORY_AUTH_DIR}/client-cert",
      "ARTIFACTORY_KEY_FILE=${pipelineParams.ARTIFACTORY_KEY_VAULT_PATH?.trim() ? "${env.ARTIFACTORY_AUTH_DIR}/client-key" : ''}",
      "ARTIFACTORY_CA_FILE=${pipelineParams.ARTIFACTORY_CA_VAULT_PATH?.trim() ? "${env.ARTIFACTORY_AUTH_DIR}/ca-cert" : ''}",
      "ARTIFACTORY_CERT_PASSWORD=${artifactoryCertPassword}",
      "ARTIFACTORY_CERT_TYPE=${pipelineParams.ARTIFACTORY_CERT_TYPE?.trim() ?: 'PEM'}",
      "VENDOR_PACKAGE_ZIP_PASSWORD=${vendorZipPassword}"
    ]) {
      sh '''#!/usr/bin/env bash
        set -euo pipefail

        VENDOR_ARCHIVE="$VENDOR_DOWNLOAD_DIR/vendor_package"
        curl_args=(--fail --silent --show-error --location --cert-type "$ARTIFACTORY_CERT_TYPE")

        if [ -n "${ARTIFACTORY_CERT_PASSWORD:-}" ]; then
          curl_args+=(--cert "${ARTIFACTORY_CERT_FILE}:${ARTIFACTORY_CERT_PASSWORD}")
        else
          curl_args+=(--cert "${ARTIFACTORY_CERT_FILE}")
        fi

        if [ -n "${ARTIFACTORY_KEY_FILE:-}" ]; then
          curl_args+=(--key "$ARTIFACTORY_KEY_FILE")
        fi

        if [ -n "${ARTIFACTORY_CA_FILE:-}" ]; then
          curl_args+=(--cacert "$ARTIFACTORY_CA_FILE")
        fi

        "$CURL_BIN" "${curl_args[@]}" -o "$VENDOR_ARCHIVE" "$ARTIFACTORY_PACKAGE_URL"
        rm -rf "$ARTIFACTORY_AUTH_DIR"

        if unzip -tq "$VENDOR_ARCHIVE" >/dev/null 2>&1; then
          unzip -q "$VENDOR_ARCHIVE" -d "$VENDOR_EXTRACT_DIR"
        elif [ -n "${VENDOR_PACKAGE_ZIP_PASSWORD:-}" ] && unzip -P "$VENDOR_PACKAGE_ZIP_PASSWORD" -tq "$VENDOR_ARCHIVE" >/dev/null 2>&1; then
          unzip -P "$VENDOR_PACKAGE_ZIP_PASSWORD" -q "$VENDOR_ARCHIVE" -d "$VENDOR_EXTRACT_DIR"
        elif tar -tzf "$VENDOR_ARCHIVE" >/dev/null 2>&1; then
          tar -C "$VENDOR_EXTRACT_DIR" -xzf "$VENDOR_ARCHIVE"
        elif tar -tf "$VENDOR_ARCHIVE" >/dev/null 2>&1; then
          tar -C "$VENDOR_EXTRACT_DIR" -xf "$VENDOR_ARCHIVE"
        else
          echo "Unsupported vendor package format or missing ZIP password: $ARTIFACTORY_PACKAGE_URL" >&2
          exit 1
        fi

        HEADER_FILE="$(find "$VENDOR_EXTRACT_DIR" -type f -name 'tds_api.h' | head -n 1)"
        LIB_FILE="$(find "$VENDOR_EXTRACT_DIR" -type f \\( -name 'libtds_api.so' -o -name 'tds_api.so' \\) | head -n 1)"
        CPACK_FILE="$(find "$VENDOR_EXTRACT_DIR" -type f -name 'cpack.dat' | head -n 1 || true)"

        if [ -z "$HEADER_FILE" ] || [ ! -f "$HEADER_FILE" ]; then
          echo "Failed to find tds_api.h in extracted vendor package" >&2
          exit 1
        fi
        if [ -z "$LIB_FILE" ] || [ ! -f "$LIB_FILE" ]; then
          echo "Failed to find libtds_api.so in extracted vendor package" >&2
          exit 1
        fi
        if [ -z "$CPACK_FILE" ] || [ ! -f "$CPACK_FILE" ]; then
          echo "Failed to find cpack.dat in extracted vendor package" >&2
          exit 1
        fi

        HEADER_DIR="$(dirname "$HEADER_FILE")"
        find "$HEADER_DIR" -maxdepth 1 -type f -name '*.h' -exec cp -f {} "$VENDOR_TDS_DIR/include/" \\;
        cp -f "$LIB_FILE" "$VENDOR_TDS_DIR/linux_x86_64/libtds_api.so"
        cp -f "$CPACK_FILE" "$VENDOR_TDS_DIR/linux_x86_64/cpack.dat"

        test -f "$VENDOR_TDS_DIR/include/tds_api.h"
        test -f "$VENDOR_TDS_DIR/linux_x86_64/libtds_api.so"
        test -f "$VENDOR_TDS_DIR/linux_x86_64/cpack.dat"
        find "$VENDOR_TDS_DIR" -maxdepth 2 -type f | sort
      '''
    }
    return
  }

  sh '''
    set -euxo pipefail
    test -f "$VENDOR_TDS_DIR/include/tds_api.h"
    test -f "$VENDOR_TDS_DIR/linux_x86_64/libtds_api.so"
    test -f "$VENDOR_TDS_DIR/linux_x86_64/cpack.dat"
  '''
}

void configureCmake(Map args = [:]) {
  String packagePath = args.packagePath ?: "${env.BUILD_DIR}/client_funding_risk_report.tar.gz"

  sh """#!/usr/bin/env bash
    set -euxo pipefail
    cd "\$PROJECT_DIR"
    test -f "\$PROJECT_DIR/tds/include/tds_api.h"
    test -f "\$PROJECT_DIR/tds/linux_x86_64/libtds_api.so"
    test -f "\$PROJECT_DIR/tds/linux_x86_64/cpack.dat"

    cmake -S . -B "\$BUILD_DIR" \\
      -DCMAKE_BUILD_TYPE="\$BUILD_TYPE" \\
      -DREPORT_STAGE_DIR="\$STAGE_DIR" \\
      -DREPORT_PACKAGE_PATH=${shellQuote(packagePath)}
  """
}

return this
