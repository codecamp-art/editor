# Report

`report` is a standalone C++17 program that reads the TDS snapshot and sends an HTML mail through `curl`.

## Config Model

The package is environment-neutral.

- `config/report.properties` holds the shared defaults
- `config/dev.properties`, `config/qa.properties`, and `config/prod.properties` only hold environment-specific overrides
- the runtime environment name is selected only by `--env`; properties files do not contain `env.name`
- shared SMTP transport settings stay in `report.properties`
- each environment file owns `smtp.from`, `email.default_to`, and `email.default_cc`; `email.subject` may be overridden per environment
- `smtp.from` is required from the environment overlay, and `email.subject` must exist after merge (base + overlay)
- release/CI can fail with `missing required config key: email.subject` if base `report.properties` omits it, because shared config is loaded before overlay
- SMTP relay authentication is certificate-only; there is no SMTP username/password configuration
- SMTP client certificate and key type are fixed to PEM; encrypted private keys are not supported
- the shared Vault settings are `vault.address`, `vault.namespace`, `vault.secret_engine`, and `vault.secret_key`
- each environment file owns only its environment-specific `vault.secret_path`

At runtime, `--env dev|qa|prod` loads `config/report.properties` first and then overlays `config/<env>.properties`. `--config` can point at a custom properties file, but it does not replace `--env`.

Packaged runs must start with `bin/report --env <env>` and normally do not need `--config`.
Airflow scheduled runs should pass `--report-time HH:MM` so the email body uses the DAG schedule time, for example `--report-time 08:30` or `--report-time 15:30`.

Vault layout:

```properties
# config/report.properties
vault.namespace=...
vault.secret_engine=...
vault.secret_key=password

# config/qa.properties
vault.secret_path=...
```

## DRTP Endpoints

Each environment can configure multiple DRTP access points:

```properties
tds.drtp_endpoints=10.10.20.30:6003,10.10.20.31:6003
```

At startup the program registers every endpoint with the supplier API through `TdsApi_addDrtpNode` before login. If one node is unavailable, the supplier runtime can use another registered node.

Temporary runtime overrides:

```bash
bin/report --env qa --drtp-endpoints 10.10.20.50:6003,10.10.20.51:6003
```

## Logging

Runtime logs are JSON lines. The same log line is written to the daily log file under `log.dir` and to standard output, so Jenkins, systemd, and the local log file see the same runtime log content.

## DRTP and Email Safety Rules

Execution policy by scenario:

1. Any **test run** must not connect to live DRTP and must not send email.
2. Any **Jenkins test run** must not connect to live DRTP to pull data and must not send email.
3. **Windows local real-program runs** may connect to live DRTP to pull data, but must not send email (always keep `--dry-run`).
4. **RHEL8 local real-program runs** may connect to live DRTP and may send email when validation requires end-to-end delivery.

Recommended control mapping:

- disable live DRTP pull: `--stub-file tests/data/stub_snapshot.csv`
- disable email send: `--dry-run`
- live DRTP pull enabled: omit `--stub-file`
- real email send enabled: omit `--dry-run`

## Packaging Model

- Windows local build is only for debugging and does not need release packaging
- RHEL8 local, Jenkins PR, and Jenkins release use the staged runtime directory directly or package it as a plain `.tar.gz`
- every package contains all config files: `report.properties`, `dev.properties`, `qa.properties`, and `prod.properties`

Linux release flow:

1. Build the staged runtime
2. Optionally wrap the staged directory into one `.tar.gz`
3. Unpack/copy the staged directory to the target path
4. Start the program with `--env`

After unpacking/copying:

```bash
/home/user/apps/report/bin/report --env qa
```

To switch environment, run with another `--env` value:

```bash
/home/user/apps/report/bin/report --env prod
```

## Windows Local

Windows local build is for debugging only. It is Win32/x86 only because the supplier currently provides only 32-bit `dll/lib` files.

Windows local testing can connect to DRTP and generate the mail preview, but real SMTP delivery is not supported locally. Always run Windows local tests with `--dry-run`.

The current Windows PowerShell environment on this machine does not have `cmake` on `PATH`.

Use the Visual Studio bundled executables directly:

```powershell
$cmake = 'C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
$ctest = 'C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\ctest.exe'
```

Win32 local debug build:

```powershell
& $cmake --preset windows-local-x86
& $cmake --build --preset windows-local-x86-debug
& $ctest --preset windows-local-x86-debug
.\build\windows-local-x86\Debug\report.exe --env dev --dry-run --to debug@example.com
```

The command above uses live DRTP when the supplier 32-bit files exist under `tds/win32`. Without those files, use the deterministic stub input:

```powershell
.\build\windows-local-x86\Debug\report.exe --env dev --stub-file .\tests\data\stub_snapshot.csv --dry-run --to debug@example.com
```

`report.exe --env dev` works in-place because the program now loads `config/report.properties` first and then overlays `config/dev.properties`.

To temporarily test another DRTP access point:

```powershell
.\build\windows-local-x86\Debug\report.exe --env dev --drtp-endpoints 127.0.0.1:6003 --dry-run --to debug@example.com
```

If you want to inspect the staged directory locally:

```powershell
& $cmake --build --preset windows-local-x86-debug --target report_stage
.\build\windows-local-x86\client_funding_risk_report\bin\report.exe --env qa --dry-run --to debug@example.com
```

## Local Vendor SDK

Local and Jenkins builds consume the supplier SDK from `tds/`, but `tds/` is generated from the curated Artifactory package instead of being committed.

Required layout:

```text
tds/
  include/
    tds_api.h
  win32/
    tds_api.lib
    tds_api.dll
    cpack.dat
  linux_x86_64/
    libtds_api.so
    cpack.dat
```

Local credential/config files:

- copy `tds.properties.example` to `tds.properties` in this directory, or to `~/.tds/tds.properties`
- on Windows, `~/.tds/tds.properties` resolves to `%USERPROFILE%\.tds\tds.properties`, for example `C:\Users\<user>\.tds\tds.properties`
- `tds.properties` is gitignored and may contain local Artifactory token or certificate/key paths
- `TDS_SDK_PROPERTIES_FILE` may point at a different properties file
- CMake also accepts non-secret overrides such as `-DTDS_SDK_URL=...`, `-DTDS_SDK_AUTH=...`, `-DTDS_SDK_CERT_FILE=...`, and `-DTDS_SDK_KEY_FILE=...`

Rules:

- Windows local live/debug build downloads `tds_sdk.zip` from Artifactory with token authentication only, then reads the supplier 32-bit files from `tds/win32`
- Windows stage/install output includes every `*.dll` and `*.dat` file under `tds/win32`
- Windows x64 build presets are intentionally not provided
- RHEL8 local live build downloads `tds_sdk.zip` from Artifactory with either token or certificate/key authentication
- Jenkins downloads the same Artifactory package into `workspace/tds` with fixed certificate/key authentication only
- CMake does not support overriding supplier header/library paths with `-D` variables

Typical local token configuration:

```properties
tds.sdk.url=https://artifactory.example.com/artifactory/vendor/tds_sdk.zip
tds.sdk.auth=token
tds.sdk.token=REPLACE_WITH_TOKEN
```

If Artifactory expects a custom token header, use the full header instead:

```properties
tds.sdk.url=https://artifactory.example.com/artifactory/vendor/tds_sdk.zip
tds.sdk.auth=token
tds.sdk.authHeader=X-JFrog-Art-Api: REPLACE_WITH_TOKEN
```

RHEL8 local certificate/key configuration:

```properties
tds.sdk.url=https://artifactory.example.com/artifactory/vendor/tds_sdk.zip
tds.sdk.auth=cert
tds.sdk.certFile=/path/to/artifactory-client.pem
tds.sdk.keyFile=/path/to/artifactory-client.key
```

The standalone preparation command is:

```bash
cmake -DTDS_SDK_CONTEXT=local -P cmake/PrepareTdsSdk.cmake
```

Normal CMake configure also calls the same preparation script when `tds/` is incomplete and a local properties file or environment config is available.

## Vault and Kerberos

Vault access is Kerberos-only.

Behavior:

1. When `tds.password` is empty and `vault.secret_path` is configured, the program calls the Vault HTTP API through C++ libcurl with Kerberos SPNEGO.
2. Kerberos login uses the fixed default auth mount: `POST /v1/auth/kerberos/login`.
3. TDS password reads use KV v2 only: `GET /v1/<vault.secret_engine>/data/<vault.secret_path>`.
4. The secret field is selected by `vault.secret_key`.

Assumptions:

- TGT is refreshed outside the program
- on RHEL8 and Jenkins, cron refreshes the TGT for the runtime user
- `KRB5CCNAME` is already exported by that user's shell profile
- `report` inherits the current environment and lets libcurl use the existing Kerberos credential cache

The application does not read or require `kerberos_realm`, `keytab_path`, `krb5conf_path`, `service`, or `disable_fast_negotiation` from properties anymore. If `klist` already works after switching to that user, no extra runtime flag is needed.

`vault.auth_path` was removed. It only meant the Vault Kerberos auth mount used for login, not the secret engine or secret path. The current implementation assumes the Kerberos auth method is mounted at the standard `kerberos` path.

`tds.password` can still be set directly through `TDS_PASSWORD` for local override. When it is empty and `vault.secret_path` is configured, the program reads the password from Vault using `vault.secret_engine`, `vault.secret_path`, and `vault.secret_key`.

`bin/report` does not call `vault-http.sh`, `python3`, external `curl`, or the HashiCorp Vault CLI for Vault access. Vault tokens are kept inside the process and are not written to command-line arguments, environment variables, or temp files. Vault HTTP failure messages only include libcurl errors or HTTP status, not response bodies.

## RHEL8 Local

After configuring Artifactory access in `tds.properties`:

Install the build tools once if the machine does not already have them:

```bash
sudo dnf install -y gcc-c++ make cmake curl libcurl-devel
```

If CMake reports `Could NOT find CURL (missing: CURL_LIBRARY CURL_INCLUDE_DIR)`, the `libcurl-devel` RPM is missing. `curl --version` is not enough because it only checks the CLI runtime, while this program links C++ code against libcurl headers and libraries.

```bash
rpm -q libcurl-devel
ls /usr/include/curl/curl.h /usr/lib64/libcurl.so
```

After installing `libcurl-devel`, rerun from a clean Linux build directory if CMake cached the failed lookup:

```bash
rm -rf build/linux-rhel8-release-make
cmake --preset linux-rhel8-release
```

The `linux-rhel8-release` preset now uses `Unix Makefiles`, not `Ninja`, because a default RHEL8 environment usually has `make` more often than `ninja-build`.

If `cmake --preset linux-rhel8-release` still reports `CMAKE_CXX_COMPILER not set`, that machine is missing the C++ toolchain and you still need `gcc-c++`.

If you previously ran the older `Ninja`-based preset, that old cache may still exist under `build/linux-rhel8-release`. The preset now uses `build/linux-rhel8-release-make`, so a fresh pull no longer collides with that old directory. The old directory can be removed manually when convenient.

```bash
cmake --preset linux-rhel8-release
cmake --build --preset linux-rhel8-release --parallel
ctest --preset linux-rhel8-release
cmake --build --preset linux-rhel8-release --target report_package
mkdir -p "$PWD/install_qa"
tar -C "$PWD/install_qa" -xzf ./build/linux-rhel8-release-make/client_funding_risk_report.tar.gz
./install_qa/client_funding_risk_report/bin/report --env qa --stub-file ./tests/data/stub_snapshot.csv --dry-run --to qa-ops@example.com
```

RHEL8 local real-program run examples:

- no email (can use live DRTP):

```bash
./install_qa/client_funding_risk_report/bin/report --env qa --dry-run --to qa-ops@example.com
```

- full end-to-end with live DRTP and real email:

```bash
./install_qa/client_funding_risk_report/bin/report --env qa
```

## Jenkins Release

Pipeline file: [jenkins/Jenkinsfile.release](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.release)

Shared Jenkins helper: [jenkins/report_common.groovy](/D:/Codes/local/Test/tds_reporter/jenkins/report_common.groovy)

Release assumptions:

- Jenkins user has a cron-refreshed TGT
- the Jenkins systemd service startup loads the user's profile so the Jenkins process inherits `KRB5CCNAME`
- the pipeline trusts the inherited Kerberos environment and does not re-check `KRB5CCNAME` or run `klist`
- Jenkins has `curl` available through the fixed `CURL_BIN` value or `PATH`
- Jenkins parses Vault JSON with the built-in Groovy JSON parser, not Python
- Artifactory client cert and key are fixed files on the Jenkins server
- no extra Artifactory CA file is configured because the corporate CA is already trusted by the server
- the Artifactory key is not password-protected, so Jenkins does not read any Artifactory secret from Vault
- the fixed Artifactory package already contains the curated TDS files under `tds/include`, `tds/linux_x86_64`, and `tds/win32`
- Jenkins extracts that package into `workspace/tds` and validates `tds_api.h`, `libtds_api.so`, and `cpack.dat`
- Jenkins builds one plain `.tar.gz` package per build
- the live smoke extracts that `.tar.gz` into a temporary directory and runs with `REPORT_RUNTIME_ENV`

Fixed Jenkins integration config:

- `jenkins/report_common.groovy` owns the fixed Artifactory TDS package URL
- the same helper owns the fixed Artifactory certificate/key paths and separate PR/Release Vault settings
- set Jenkins certificate paths by replacing `ARTIFACTORY_CERT_FILE` and `ARTIFACTORY_KEY_FILE` in `fixedIntegrationConfig`; both values should be absolute paths on the Jenkins RHEL8 agent
- these values are not Jenkins UI parameters; update the helper only when Vault locations or the curated TDS package URL changes
- placeholder values beginning with `REPLACE_ME_` fail fast before Vault or Artifactory access
- `REPORT_RUNTIME_ENV` remains a Release smoke parameter because it selects the runtime `--env`, not package download credentials
- Vault token is kept out of Jenkins environment variables and `curl` argv; Vault HTTP login uses a temporary `curl --config` file with mode `600`
- Artifactory download is delegated to `cmake/PrepareTdsSdk.cmake`, which uses the fixed local certificate/key through a temporary `curl --config` file and hard-coded PEM cert type
- the TDS package must not be password-protected; Artifactory mTLS controls download access

Jenkins helper flow:

1. Kerberos login to Vault through the HTTP API with curl
2. Download the curated TDS dependency package from Artifactory using `cmake/PrepareTdsSdk.cmake` and the fixed local certificate/key
3. Extract it into `workspace/tds`
4. Build, test, and stage one common runtime containing all properties files
5. Build `client_funding_risk_report-*.tar.gz`
6. For smoke, extract that `.tar.gz` and run with `--env "$REPORT_RUNTIME_ENV"`
7. Run `"$SMOKE_INSTALL_DIR/client_funding_risk_report/bin/report" --env "$REPORT_RUNTIME_ENV" --dry-run`

## Jenkins PR

Pipeline file: [jenkins/Jenkinsfile.pr](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.pr)

Shared Jenkins helper: [jenkins/report_common.groovy](/D:/Codes/local/Test/tds_reporter/jenkins/report_common.groovy)

The PR pipeline now uses the same TDS package preparation model as release:

- the fixed Artifactory package URL downloads the curated TDS package from Artifactory
- Artifactory certificate and key are fixed files on the Jenkins server
- the downloaded package may be `.tar.gz`, `.tar`, or non-password-protected ZIP
- the package must contain `tds/include`, `tds/linux_x86_64`, and `tds/win32`; Jenkins extracts it into `workspace/tds`
- PR builds the same `.tar.gz` packaging target and smoke runs the staged `bin/report --env dev`
