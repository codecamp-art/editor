import java.util.UUID

String shellQuote(String value) {
  return "'" + (value ?: '').replace("'", "'\"'\"'") + "'"
}

String fixedIntegrationConfig(String pipelineName) {
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

void prepareTdsPackage(Map args) {
  def pipelineParams = args.params

  if (!pipelineParams.TDS_PACKAGE_URL?.trim()) {
    error('TDS_PACKAGE_URL is required')
  }
  if (!pipelineParams.ARTIFACTORY_CERT_FILE?.trim()) {
    error('ARTIFACTORY_CERT_FILE is required')
  }

  sh '''#!/usr/bin/env bash
set -euo pipefail
rm -rf "$TDS_PACKAGE_DOWNLOAD_DIR" "$TDS_PACKAGE_EXTRACT_DIR" "$TDS_DIR"
mkdir -p "$TDS_PACKAGE_DOWNLOAD_DIR" "$TDS_PACKAGE_EXTRACT_DIR"
'''

  String artifactoryCertFile = pipelineParams.ARTIFACTORY_CERT_FILE.trim()
  String artifactoryKeyFile = pipelineParams.ARTIFACTORY_KEY_FILE?.trim() ?: ''

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

void validateTdsPackage() {
  sh '''#!/usr/bin/env bash
set -euxo pipefail
test -f "$TDS_DIR/include/tds_api.h"
test -f "$TDS_DIR/include/tds_api_define.h"
test -f "$TDS_DIR/include/tds_api_struct_type.h"
test -f "$TDS_DIR/linux_x86_64/libtds_api.so"
test -f "$TDS_DIR/linux_x86_64/cpack.dat"
test -f "$TDS_DIR/win32/tds_api.lib"
test -f "$TDS_DIR/win32/cpack.dat"
find "$TDS_DIR/win32" -maxdepth 1 -iname '*.dll' -type f | grep -q .
'''
}

void buildTdsWeb(Map args = [:]) {
  sh '''#!/usr/bin/env bash
set -euxo pipefail
cd "$PROJECT_DIR"
./gradlew --no-daemon verifyFullTdsSdk buildNativeAdapter bootJar -PtdsSdkRoot="$TDS_DIR"
'''
}

return this
