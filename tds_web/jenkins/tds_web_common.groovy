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
./gradlew --no-daemon test verifyFullTdsSdk buildNativeAdapter bootJar -PtdsSdkRoot="$TDS_DIR"
'''
}

void stageReleasePackage() {
  sh '''#!/usr/bin/env bash
set -euxo pipefail
cd "$PROJECT_DIR"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/bin" "$STAGE_DIR/lib" "$STAGE_DIR/config"

JAR_PATH="$(find "$PROJECT_DIR/build/libs" -maxdepth 1 -name 'tds-client-query-web-*.jar' -type f | sort | head -n 1)"
test -n "$JAR_PATH"
test -f "$PROJECT_DIR/build/native/tds_adapter"
test -f "$TDS_DIR/linux_x86_64/libtds_api.so"
test -f "$TDS_DIR/linux_x86_64/cpack.dat"

cp "$JAR_PATH" "$STAGE_DIR/bin/tds-client-query-web.jar"
cp "$PROJECT_DIR/build/native/tds_adapter" "$STAGE_DIR/bin/tds_adapter"
cp "$TDS_DIR/linux_x86_64/libtds_api.so" "$STAGE_DIR/lib/libtds_api.so"
cp "$TDS_DIR/linux_x86_64/cpack.dat" "$STAGE_DIR/bin/cpack.dat"
cp "$PROJECT_DIR"/src/main/resources/application*.yml "$STAGE_DIR/config/"

tar -C "$(dirname "$STAGE_DIR")" -czf "$PACKAGE_PATH" "$(basename "$STAGE_DIR")"
test -f "$PACKAGE_PATH"
'''
}

void runStubSmoke() {
  sh '''#!/usr/bin/env bash
set -euxo pipefail
cd "$PROJECT_DIR"

JAR_PATH="$(find "$PROJECT_DIR/build/libs" -maxdepth 1 -name 'tds-client-query-web-*.jar' -type f | sort | head -n 1)"
test -n "$JAR_PATH"

SMOKE_PORT="${SMOKE_PORT:-$((18080 + (${BUILD_NUMBER:-0} % 1000)))}"
SMOKE_LOG="$PROJECT_DIR/build/stub-smoke.log"
SMOKE_RESPONSE="$PROJECT_DIR/build/stub-smoke-response.json"

java -jar "$JAR_PATH" \
  --server.port="$SMOKE_PORT" \
  --app.security.enabled=false \
  --tds.mode=stub \
  > "$SMOKE_LOG" 2>&1 &
APP_PID="$!"

cleanup() {
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

for attempt in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$SMOKE_PORT/api/tds/clients?query=Alpha" > "$SMOKE_RESPONSE"; then
    grep -q '"clientId":"1001"' "$SMOKE_RESPONSE"
    exit 0
  fi
  sleep 1
done

cat "$SMOKE_LOG"
exit 1
'''
}

return this
