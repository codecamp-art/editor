import groovy.json.JsonSlurperClassic
import java.util.UUID

String shellQuote(String value) {
  return "'" + (value ?: '').replace("'", "'\"'\"'") + "'"
}

String curlConfigQuote(String value) {
  return '"' + (value ?: '')
    .replace('\\', '\\\\')
    .replace('"', '\\"')
    .replace('\r', '\\r')
    .replace('\n', '\\n') + '"'
}

String curlConfigOption(String name, String value = null) {
  return value == null ? name : "${name} = ${curlConfigQuote(value)}"
}

String secretTempPath(String prefix) {
  return "${env.WORKSPACE}/.jenkins_secret_tmp/${prefix}-${UUID.randomUUID().toString()}"
}

String parentPath(String path) {
  int delimiter = (path ?: '').lastIndexOf('/')
  return delimiter > 0 ? path.substring(0, delimiter) : '.'
}

void writeSecretFile(String path, String content) {
  String parentDir = parentPath(path)
  sh """#!/usr/bin/env bash
set +x
set -euo pipefail
mkdir -p ${shellQuote(parentDir)}
chmod 700 ${shellQuote(parentDir)}
"""
  writeFile(file: path, text: content)
  sh """#!/usr/bin/env bash
set +x
set -euo pipefail
chmod 600 ${shellQuote(path)}
"""
}

void deleteSecretFile(String path) {
  if (!path?.trim()) {
    return
  }

  sh """#!/usr/bin/env bash
set +x
rm -f ${shellQuote(path)}
rmdir ${shellQuote(parentPath(path))} 2>/dev/null || true
"""
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
    TDS_PACKAGE_URL: 'REPLACE_ME_TDS_PACKAGE_URL',
    ARTIFACTORY_CERT_FILE: 'REPLACE_ME_ARTIFACTORY_CERT_FILE',
    ARTIFACTORY_KEY_FILE: 'REPLACE_ME_ARTIFACTORY_KEY_FILE',
    CURL_BIN: 'curl',
    ARTIFACTORY_CERT_TYPE: 'PEM'
  ]

  Map pr = [
    VAULT_ADDR: 'REPLACE_ME_PR_VAULT_ADDR',
    VAULT_NAMESPACE: 'REPLACE_ME_PR_VAULT_NAMESPACE',
    VAULT_AUTH_PATH: 'kerberos',
    ARTIFACTORY_CERT_PASSWORD_VAULT_PATH: 'REPLACE_ME_PR_ARTIFACTORY_CERT_PASSWORD_VAULT_PATH',
    ARTIFACTORY_CERT_PASSWORD_VAULT_FIELD: 'password'
  ]

  Map release = [
    VAULT_ADDR: 'REPLACE_ME_RELEASE_VAULT_ADDR',
    VAULT_NAMESPACE: 'REPLACE_ME_RELEASE_VAULT_NAMESPACE',
    VAULT_AUTH_PATH: 'kerberos',
    ARTIFACTORY_CERT_PASSWORD_VAULT_PATH: 'REPLACE_ME_RELEASE_ARTIFACTORY_CERT_PASSWORD_VAULT_PATH',
    ARTIFACTORY_CERT_PASSWORD_VAULT_FIELD: 'password'
  ]

  Map selected = pipelineName == 'release' ? release : pr
  return shared + selected
}

void validateFixedIntegrationConfig(Map config, String pipelineName) {
  List<String> requiredKeys = [
    'TDS_PACKAGE_URL',
    'VAULT_ADDR',
    'ARTIFACTORY_CERT_FILE',
    'ARTIFACTORY_KEY_FILE'
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

String vaultCurl(Map vault, String method, String apiPath, List<String> extraConfigLines = []) {
  String curlBin = (vault.curlBin ?: 'curl').trim()
  String namespace = (vault.namespace ?: '').trim()
  List<String> configLines = [
    curlConfigOption('fail'),
    curlConfigOption('silent'),
    curlConfigOption('show-error'),
    curlConfigOption('request', method),
    curlConfigOption('url', vaultApiUrl(vault, apiPath))
  ]
  configLines.addAll(extraConfigLines)
  if (namespace) {
    configLines << curlConfigOption('header', "X-Vault-Namespace: ${namespace}")
  }

  String configPath = secretTempPath('vault-curl')
  writeSecretFile(configPath, configLines.join('\n') + '\n')

  try {
    return sh(
      returnStdout: true,
      script: """#!/usr/bin/env bash
set +x
set -euo pipefail
${shellQuote(curlBin)} --config ${shellQuote(configPath)}
"""
    ).trim()
  } finally {
    deleteSecretFile(configPath)
  }
}

String vaultKerberosLogin(Map vault) {
  String authPath = trimSlashes((vault.authPath ?: '').trim() ?: 'kerberos')
  if (!authPath.startsWith('auth/')) {
    authPath = "auth/${authPath}"
  }

  def payload = new JsonSlurperClassic().parseText(
    vaultCurl(
      vault,
      'POST',
      "${authPath}/login",
      [
        curlConfigOption('negotiate'),
        curlConfigOption('user', ':')
      ]
    )
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
        vaultCurl(
          vault,
          'GET',
          candidatePath,
          [curlConfigOption('header', "X-Vault-Token: ${token}")]
        )
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

void prepareTdsPackage(Map args) {
  def pipelineParams = args.params
  String token = args.vaultToken ?: ''

  if (!pipelineParams.TDS_PACKAGE_URL?.trim()) {
    error('TDS_PACKAGE_URL is required')
  }
  if (!token?.trim()) {
    error('TDS_PACKAGE_URL requires a Kerberos-authenticated Vault session so Jenkins can fetch the Artifactory certificate')
  }

  sh '''#!/usr/bin/env bash
    set -euo pipefail
    rm -rf "$TDS_PACKAGE_DOWNLOAD_DIR" "$TDS_PACKAGE_EXTRACT_DIR" "$TDS_DIR"
    mkdir -p "$TDS_PACKAGE_DOWNLOAD_DIR" "$TDS_PACKAGE_EXTRACT_DIR"
  '''

  String artifactoryCertPassword = optionalVaultSecret(
    pipelineParams,
    token,
    'ARTIFACTORY_CERT_PASSWORD_VAULT_PATH',
    'ARTIFACTORY_CERT_PASSWORD_VAULT_FIELD',
    'password'
  )

  String artifactoryCurlConfigPath = secretTempPath('artifactory-curl')
  String artifactoryCertFile = pipelineParams.ARTIFACTORY_CERT_FILE?.trim() ?: ''
  String artifactoryKeyFile = pipelineParams.ARTIFACTORY_KEY_FILE?.trim() ?: ''
  if (!artifactoryCertFile) {
    error('ARTIFACTORY_CERT_FILE is required')
  }
  if (!artifactoryKeyFile) {
    error('ARTIFACTORY_KEY_FILE is required')
  }

  String artifactoryCertSpec = artifactoryCertPassword
    ? "${artifactoryCertFile}:${artifactoryCertPassword}"
    : artifactoryCertFile
  List<String> artifactoryCurlConfig = [
    curlConfigOption('fail'),
    curlConfigOption('silent'),
    curlConfigOption('show-error'),
    curlConfigOption('location'),
    curlConfigOption('cert-type', pipelineParams.ARTIFACTORY_CERT_TYPE?.trim() ?: 'PEM'),
    curlConfigOption('cert', artifactoryCertSpec),
    curlConfigOption('key', artifactoryKeyFile),
    curlConfigOption('output', "${env.TDS_PACKAGE_DOWNLOAD_DIR}/tds_package"),
    curlConfigOption('url', pipelineParams.TDS_PACKAGE_URL.trim())
  ]

  writeSecretFile(artifactoryCurlConfigPath, artifactoryCurlConfig.join('\n') + '\n')

  try {
    withEnv([
      "CURL_BIN=${pipelineParams.CURL_BIN?.trim() ?: 'curl'}",
      "TDS_PACKAGE_URL=${pipelineParams.TDS_PACKAGE_URL.trim()}",
      "ARTIFACTORY_CERT_FILE=${artifactoryCertFile}",
      "ARTIFACTORY_KEY_FILE=${artifactoryKeyFile}",
      "ARTIFACTORY_CURL_CONFIG_FILE=${artifactoryCurlConfigPath}"
    ]) {
      sh '''#!/usr/bin/env bash
        set +x
        set -euo pipefail

        TDS_ARCHIVE="$TDS_PACKAGE_DOWNLOAD_DIR/tds_package"
        test -f "$ARTIFACTORY_CERT_FILE"
        test -f "$ARTIFACTORY_KEY_FILE"
        "$CURL_BIN" --config "$ARTIFACTORY_CURL_CONFIG_FILE"
        rm -f "$ARTIFACTORY_CURL_CONFIG_FILE"

        if tar -tzf "$TDS_ARCHIVE" >/dev/null 2>&1; then
          tar -C "$TDS_PACKAGE_EXTRACT_DIR" -xzf "$TDS_ARCHIVE"
        elif tar -tf "$TDS_ARCHIVE" >/dev/null 2>&1; then
          tar -C "$TDS_PACKAGE_EXTRACT_DIR" -xf "$TDS_ARCHIVE"
        elif unzip -tq "$TDS_ARCHIVE" >/dev/null 2>&1; then
          unzip -q "$TDS_ARCHIVE" -d "$TDS_PACKAGE_EXTRACT_DIR"
        else
          echo "Unsupported TDS package format: $TDS_PACKAGE_URL" >&2
          exit 1
        fi

        if [ -d "$TDS_PACKAGE_EXTRACT_DIR/tds" ]; then
          TDS_SOURCE_DIR="$TDS_PACKAGE_EXTRACT_DIR/tds"
        else
          TDS_SOURCE_DIR="$TDS_PACKAGE_EXTRACT_DIR"
        fi

        test -f "$TDS_SOURCE_DIR/include/tds_api.h"
        test -f "$TDS_SOURCE_DIR/linux_x86_64/libtds_api.so"
        test -f "$TDS_SOURCE_DIR/linux_x86_64/cpack.dat"
        test -d "$TDS_SOURCE_DIR/win32"

        mkdir -p "$TDS_DIR"
        cp -a "$TDS_SOURCE_DIR/." "$TDS_DIR/"

        test -f "$TDS_DIR/include/tds_api.h"
        test -f "$TDS_DIR/linux_x86_64/libtds_api.so"
        test -f "$TDS_DIR/linux_x86_64/cpack.dat"
        if [ ! -d "$TDS_DIR/win32" ]; then
          echo "TDS package must include tds/win32 for Windows local parity" >&2
          exit 1
        fi
        find "$TDS_DIR" -maxdepth 2 -type f | sort
      '''
    }
  } finally {
    deleteSecretFile(artifactoryCurlConfigPath)
  }
}

void validateLocalTdsFiles() {
  sh '''
    set -euxo pipefail
    test -f "$TDS_DIR/include/tds_api.h"
    test -f "$TDS_DIR/linux_x86_64/libtds_api.so"
    test -f "$TDS_DIR/linux_x86_64/cpack.dat"
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
