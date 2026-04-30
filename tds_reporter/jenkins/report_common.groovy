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
  String certFile = '/jenkins/build.pem'
  String keyFile = env.ARTIFACTORY_KEY_FILE?.trim() ?: ''

  return [
    TDS_PACKAGE_URL: 'REPLACE_ME_TDS_PACKAGE_URL',
    ARTIFACTORY_CERT_FILE: certFile,
    // Optional when cert+key are bundled in one PEM file.
    ARTIFACTORY_KEY_FILE: keyFile,
    CURL_BIN: 'curl'
  ]
}

void validateFixedIntegrationConfig(Map config, String pipelineName) {
  List<String> requiredKeys = [
    'TDS_PACKAGE_URL',
    'ARTIFACTORY_CERT_FILE'
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

void prepareTdsPackage(Map args) {
  def pipelineParams = args.params

  if (!pipelineParams.TDS_PACKAGE_URL?.trim()) {
    error('TDS_PACKAGE_URL is required')
  }

  sh '''#!/usr/bin/env bash
    set -euo pipefail
    rm -rf "$TDS_PACKAGE_DOWNLOAD_DIR" "$TDS_PACKAGE_EXTRACT_DIR" "$TDS_DIR"
    mkdir -p "$TDS_PACKAGE_DOWNLOAD_DIR" "$TDS_PACKAGE_EXTRACT_DIR"
  '''

  String artifactoryCertFile = pipelineParams.ARTIFACTORY_CERT_FILE?.trim() ?: ''
  String artifactoryKeyFile = pipelineParams.ARTIFACTORY_KEY_FILE?.trim() ?: ''
  if (!artifactoryCertFile) {
    error('ARTIFACTORY_CERT_FILE is required')
  }
  sh """#!/usr/bin/env bash
    set +x
    set -euo pipefail

    cmake \\
      -DTDS_SDK_PROJECT_DIR=${shellQuote(env.PROJECT_DIR)} \\
      -DTDS_SDK_DEST_DIR=${shellQuote(env.TDS_DIR)} \\
      -DTDS_SDK_DOWNLOAD_DIR=${shellQuote(env.TDS_PACKAGE_DOWNLOAD_DIR)} \\
      -DTDS_SDK_EXTRACT_DIR=${shellQuote(env.TDS_PACKAGE_EXTRACT_DIR)} \\
      -DTDS_SDK_PLATFORM=linux \\
      -DTDS_SDK_CONTEXT=jenkins \\
      -DTDS_SDK_AUTH=cert \\
      -DTDS_SDK_FORCE=ON \\
      -DTDS_SDK_URL=${shellQuote(pipelineParams.TDS_PACKAGE_URL.trim())} \\
      -DTDS_SDK_CERT_FILE=${shellQuote(artifactoryCertFile)} \\
      -DTDS_SDK_KEY_FILE=${shellQuote(artifactoryKeyFile)} \\
      -DTDS_SDK_CURL_BIN=${shellQuote(pipelineParams.CURL_BIN?.trim() ?: 'curl')} \\
      -P ${shellQuote("${env.PROJECT_DIR}/cmake/PrepareTdsSdk.cmake")}

    find "\$TDS_DIR" -maxdepth 2 -type f | sort
  """
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
    test -n "\$BUILD_DIR"
    test -n "\$STAGE_DIR"

    if [[ "\$BUILD_DIR" != "\$PROJECT_DIR"/build_* ]]; then
      echo "Refusing to clean unsafe BUILD_DIR: \$BUILD_DIR" >&2
      exit 1
    fi

    if [[ "\$STAGE_DIR" != "\$BUILD_DIR"/* ]]; then
      echo "Refusing to configure with STAGE_DIR outside BUILD_DIR: \$STAGE_DIR" >&2
      exit 1
    fi

    rm -rf "\$BUILD_DIR"

    cmake -S . -B "\$BUILD_DIR" \\
      -DCMAKE_BUILD_TYPE="\$BUILD_TYPE" \\
      -DREPORT_STAGE_DIR="\$STAGE_DIR" \\
      -DREPORT_PACKAGE_PATH=${shellQuote(packagePath)}
  """
}

return this
