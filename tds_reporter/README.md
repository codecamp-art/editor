# Report

`report` is a standalone C++17 program that reads the TDS snapshot, writes a CSV report, and sends mail through `curl`.

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
    libtds_api.so    or tds_api.so
    cpack.dat
```

Rules:

- Windows local live build reads `tds/win32`
- RHEL8 local live build reads `tds/linux_x86_64`
- Jenkins release normalizes downloaded supplier files into `workspace/tds/linux_x86_64`
- Live builds require `cpack.dat` beside the vendor library

## Config Discovery

The executable looks for config relative to itself before falling back to the current working directory.

Behavior:

1. If `--env qa` is specified, the runtime prefers `config/qa.properties`.
2. If `--env` is omitted, the runtime prefers `config/report.properties`.
3. `--config` still overrides everything, but packaged runs normally do not need it.

That means:

- a staged dev or qa package can run with only `--env`
- a staged production package can run with no arguments when it contains `config/report.properties`
- users no longer need `--config` just to point at the packaged config directory
- dev and qa packages no longer install `config/report.properties`, so explicit `--env` stays deterministic

## Package Modes

### Dev and QA package

The staged `config/` directory includes:

- `dev.properties`
- `qa.properties`
- `prod.properties.template`
- `report.properties.template`

Run example:

```bash
./client_funding_risk_report/bin/report --env qa --dry-run --to qa-ops@example.com
```

### Production package

Production packaging uses CMake single-config mode:

- only `config/report.properties` is installed
- `dev.properties` and `qa.properties` are not installed
- the program can start with no arguments

If `config/report.properties` does not already exist, the Jenkins release job copies `config/prod.properties.template` to `config/report.properties` before configure and stage.

Run example:

```bash
./client_funding_risk_report/bin/report
```

## Vault and Kerberos

Vault access is Kerberos-only.

Behavior:

1. When a config value uses `vault://...`, the program runs `vault login -method=kerberos -token-only -no-store`.
2. It uses that short-lived token only for the following `vault kv get`.

Assumptions:

- TGT is refreshed outside the program
- on RHEL8 and Jenkins, cron refreshes the TGT for the runtime user
- `KRB5CCNAME` is already exported by that user's shell profile
- `report` and `vault` just inherit the current environment

No extra code or runtime flag is needed if `klist` already works after switching to that user.

## Windows Local

The current Windows PowerShell environment on this machine does not have `cmake` on `PATH`.

Use the Visual Studio bundled executables directly:

```powershell
$cmake = 'C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
$ctest = 'C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\ctest.exe'
```

### Stub build

```powershell
& $cmake --preset windows-stub-x64
& $cmake --build --preset windows-stub-x64-debug
& $ctest --preset windows-stub-x64-debug
& $cmake --build --preset windows-stub-x64-debug --target report_stage
.\build\windows-stub-x64\Debug\report.exe --env dev --stub-file .\tests\data\stub_snapshot.csv --dry-run --to debug@example.com
.\build\windows-stub-x64\client_funding_risk_report\bin\report.exe --env dev --stub-file .\tests\data\stub_snapshot.csv --dry-run --to debug@example.com
```

### Live build with manual `tds/win32`

If the supplier files are really 32-bit, use the x86 preset:

```powershell
& $cmake --preset windows-live-x86
& $cmake --build --preset windows-live-x86-debug
& $ctest --preset windows-live-x86-debug
.\build\windows-live-x86\Debug\report.exe --env dev --dry-run --to debug@example.com
```

If the supplier gives a real x64 import library and DLL, pass them explicitly:

```powershell
& $cmake --preset windows-live-x64 -DTDS_VENDOR_LIBRARY=D:/vendor/tds/win64/tds_api.lib -DTDS_VENDOR_RUNTIME=D:/vendor/tds/win64/tds_api.dll
& $cmake --build --preset windows-live-x64-debug
& $ctest --preset windows-live-x64-debug
```

## RHEL8 Local

After manually placing the vendor `.so` and `cpack.dat` in `tds/linux_x86_64`:

```bash
cmake --preset linux-rhel8-release
cmake --build --preset linux-rhel8-release --parallel
ctest --preset linux-rhel8-release
cmake --build build/linux-rhel8-release --target report_stage
./build/linux-rhel8-release/client_funding_risk_report/bin/report --env qa --dry-run --to qa-ops@example.com
```

## Production Packaging On RHEL8

If you want a local production-style package with only `report.properties`:

```bash
cp config/prod.properties.template config/report.properties
cmake -S . -B build_release -G Ninja -DCMAKE_BUILD_TYPE=Release -DTDS_VENDOR_LIBRARY="$PWD/tds/linux_x86_64/libtds_api.so" -DREPORT_STAGE_DIR="$PWD/build_release/client_funding_risk_report" -DREPORT_INSTALL_SINGLE_CONFIG=ON -DREPORT_SINGLE_CONFIG_SOURCE="$PWD/config/report.properties"
cmake --build build_release --parallel
ctest --test-dir build_release --output-on-failure
cmake --build build_release --target report_stage
./build_release/client_funding_risk_report/bin/report
```

## Jenkins Release

Pipeline file: [jenkins/Jenkinsfile.release](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.release)

Release assumptions:

- Jenkins user has a cron-refreshed TGT
- that user's profile exports `KRB5CCNAME`
- the pipeline sources the user profile and checks `klist` before Vault Kerberos login and before live smoke
- Artifactory client cert, key, CA, and optional cert password come from Vault, not Jenkins local file credentials
- supplier `so` and `cpack.dat` are normalized into `workspace/tds/linux_x86_64`
- the staged production package installs only `config/report.properties`
- the stage step asserts that `config/` contains no other properties file

Important parameters:

- `ARTIFACTORY_PACKAGE_URL`
- `ARTIFACTORY_CERT_VAULT_PATH`
- optional `ARTIFACTORY_KEY_VAULT_PATH`
- optional `ARTIFACTORY_CA_VAULT_PATH`
- optional `ARTIFACTORY_CERT_PASSWORD_VAULT_PATH`
- `VAULT_ADDR`
- `VAULT_KERBEROS_USERNAME`
- optional `VAULT_KERBEROS_SERVICE`
- optional `VAULT_KERBEROS_REALM`
- optional `VAULT_KERBEROS_KEYTAB_CREDENTIALS_ID`
- optional `VAULT_KRB5CONF_CREDENTIALS_ID`
- optional `VENDOR_PACKAGE_PASSWORD_VAULT_PATH`

Core flow:

1. Source the Jenkins user profile and verify `KRB5CCNAME` plus `klist`
2. Kerberos login to Vault
3. Read the Artifactory cert materials from Vault
4. Download the supplier package from Artifactory
5. Normalize `tds_api.so` and `cpack.dat` into `workspace/tds/linux_x86_64`
6. Copy `config/prod.properties.template` to `config/report.properties` if needed
7. Configure with `REPORT_INSTALL_SINGLE_CONFIG=ON`
8. Build, test, stage, verify that `config/` contains only `report.properties`, and optionally run `bin/report --dry-run`

## Jenkins PR

Pipeline file: [jenkins/Jenkinsfile.pr](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.pr)

The PR smoke step now relies on normal config discovery and no longer needs `--config`.
