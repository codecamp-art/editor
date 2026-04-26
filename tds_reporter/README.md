# Report

`report` is a standalone C++17 program that reads the TDS snapshot, writes a CSV report, and sends mail through `curl`.

## Config Model

The package is environment-neutral.

- `config/report.properties` holds the shared defaults
- `config/dev.properties`, `config/qa.properties`, and `config/prod.properties` only hold environment-specific overrides
- the SMTP section is maintained once in `report.properties`, not duplicated per environment
- SMTP relay authentication is certificate-only; there is no SMTP username/password configuration
- the Vault section is also reduced to shared settings only: `vault.curl_executable`, `vault.address`, `vault.namespace`, and `vault.auth_path`

At runtime, `--env dev|qa|prod` loads `config/report.properties` first and then overlays `config/<env>.properties`.

Packaged runs must start with `bin/report --env <env>` and normally do not need `--config`.

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

Windows local testing can connect to DRTP and generate the CSV/mail preview, but real SMTP delivery is not supported locally. Always run Windows local tests with `--dry-run`.

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

## Local Vendor Files

Local build inputs are prepared manually under `tds/`.

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

Rules:

- Windows local live/debug build reads the supplier 32-bit files from `tds/win32`
- Windows stage/install output includes every `*.dll` and `*.dat` file under `tds/win32`
- Windows x64 build presets are intentionally not provided
- RHEL8 local live build requires `tds/linux_x86_64/libtds_api.so` and `tds/linux_x86_64/cpack.dat`
- Jenkins release normalizes downloaded supplier files into `workspace/tds/linux_x86_64`
- CMake does not support overriding supplier header/library paths with `-D` variables

## Vault and Kerberos

Vault access is Kerberos-only.

Behavior:

1. When a config value uses `vault://...`, the program calls the Vault HTTP API with `curl --negotiate --user :`.
2. Kerberos login uses `POST /v1/auth/kerberos/login`.
3. Secret reads use the KV HTTP API and try KV v2 first, then KV v1.

Assumptions:

- TGT is refreshed outside the program
- on RHEL8 and Jenkins, cron refreshes the TGT for the runtime user
- `KRB5CCNAME` is already exported by that user's shell profile
- `report` and `curl` just inherit the current environment

The application does not read or require `kerberos_realm`, `keytab_path`, `krb5conf_path`, `service`, or `disable_fast_negotiation` from properties anymore. If `klist` already works after switching to that user, no extra runtime flag is needed.

`vault.curl_executable` is the curl command used for Vault HTTP calls. Leave it as the default `curl` when the command is on `PATH`; set `CURL_BIN` or `vault.curl_executable` only when the binary lives elsewhere. The HashiCorp Vault CLI is not required on RHEL8.

`bin/report` does not call `vault-http.sh`, `python3`, or the HashiCorp Vault CLI. Vault access is implemented in the C++ program itself and shells out only to `curl`.

## RHEL8 Local

After manually placing `tds/linux_x86_64/libtds_api.so` and `tds/linux_x86_64/cpack.dat`:

Install the build tools once if the machine does not already have them:

```bash
sudo dnf install -y gcc-c++ make cmake curl
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
./install_qa/client_funding_risk_report/bin/report --env qa --dry-run --to qa-ops@example.com
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
- Artifactory client cert, key, CA, and optional cert password come from Vault, not Jenkins local file credentials
- supplier files are normalized into `workspace/tds/include/tds_api.h`, `workspace/tds/linux_x86_64/libtds_api.so`, and `workspace/tds/linux_x86_64/cpack.dat`
- Jenkins builds one plain `.tar.gz` package per build
- the live smoke extracts that `.tar.gz` into a temporary directory and runs with `REPORT_RUNTIME_ENV`

Fixed Jenkins integration config:

- `jenkins/report_common.groovy` owns the shared Artifactory package URL
- the same helper owns separate PR and Release Vault paths, fields, namespace, auth path, and package password secret paths
- these values are not Jenkins UI parameters; update the helper only when Vault locations or the supplier package version changes
- placeholder values beginning with `REPLACE_ME_` fail fast before Vault or Artifactory access
- `REPORT_RUNTIME_ENV` remains a Release smoke parameter because it selects the runtime `--env`, not package download credentials

Core flow:

1. Kerberos login to Vault through the HTTP API with curl
2. Read the Artifactory cert materials from Vault through the HTTP API
3. Download the supplier package from Artifactory
4. Normalize the supplier files into `workspace/tds/include/tds_api.h`, `workspace/tds/linux_x86_64/libtds_api.so`, and `workspace/tds/linux_x86_64/cpack.dat`
5. Build, test, and stage one common runtime containing all properties files
6. Build `client_funding_risk_report-*.tar.gz`
7. For smoke, extract that `.tar.gz` and run with `--env "$REPORT_RUNTIME_ENV"`
8. Run `"$SMOKE_INSTALL_DIR/client_funding_risk_report/bin/report" --env "$REPORT_RUNTIME_ENV" --dry-run`

## Jenkins PR

Pipeline file: [jenkins/Jenkinsfile.pr](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.pr)

Shared Jenkins helper: [jenkins/report_common.groovy](/D:/Codes/local/Test/tds_reporter/jenkins/report_common.groovy)

The PR pipeline now uses the same supplier package preparation model as release:

- the fixed Artifactory package URL downloads the supplier package from Artifactory
- Artifactory certificate, optional key, optional CA, optional certificate password, and optional supplier ZIP password come from Vault
- the downloaded package may be ZIP, password-protected ZIP, `.tar.gz`, or `.tar`
- extracted supplier files are normalized into `workspace/tds/include/tds_api.h`, `workspace/tds/linux_x86_64/libtds_api.so`, and `workspace/tds/linux_x86_64/cpack.dat`
- PR builds the same `.tar.gz` packaging target and smoke runs the staged `bin/report --env dev`
