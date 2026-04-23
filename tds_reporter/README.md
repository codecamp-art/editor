# Report

`report` is a standalone C++17 program that reads the TDS snapshot, writes a CSV report, and sends mail through `curl`.

## Packaging Model

Windows local build is only for debugging and does not need installer packaging.

RHEL8 local and Jenkins release now use a real self-extracting `.run` installer.

Linux release flow:

1. Build the staged runtime
2. Wrap it into one `.run` installer
3. Execute the installer with an environment parameter
4. The installer extracts the package, renders `config/report.properties`, and leaves a ready-to-run standalone directory behind

## Linux `.run` Installer

The Linux installer is a self-extracting shell archive.

Usage:

```bash
./client_funding_risk_report-installer.run --env qa --prefix /home/user/apps/report
```

Behavior:

1. Extract into `--prefix`
2. Render `config/report.properties` from `dev`, `qa`, or `prod`
3. Leave `bin/report`, `run-report.sh`, and all runtime files in that directory

Options:

- `--env <dev|qa|prod>` required
- `--prefix <path>` optional, default is `./client_funding_risk_report`

Reinstall behavior:

- if `--prefix` already contains a previous `report` installation, the installer replaces it automatically
- if `--prefix` points to an unrelated non-empty directory, the installer refuses to overwrite it

After install:

```bash
/home/user/apps/report/bin/report
```

To switch environment later without reinstalling:

```bash
/home/user/apps/report/run-report.sh prod --dry-run --to ops@example.com
```

## Windows Local

Windows local build is for debugging only.

The current Windows PowerShell environment on this machine does not have `cmake` on `PATH`.

Use the Visual Studio bundled executables directly:

```powershell
$cmake = 'C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
$ctest = 'C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\ctest.exe'
```

Stub debug build:

```powershell
& $cmake --preset windows-stub-x64
& $cmake --build --preset windows-stub-x64-debug
& $ctest --preset windows-stub-x64-debug
.\build\windows-stub-x64\Debug\report.exe --env dev --stub-file .\tests\data\stub_snapshot.csv --dry-run --to debug@example.com
```

If you want to inspect the staged directory locally:

```powershell
& $cmake --build --preset windows-stub-x64-debug --target report_stage
.\build\windows-stub-x64\client_funding_risk_report\run-report.ps1 qa --stub-file .\tests\data\stub_snapshot.csv --dry-run --to debug@example.com
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
    libtds_api.so    or tds_api.so
    cpack.dat
```

Rules:

- Windows local live build reads `tds/win32`
- RHEL8 local live build reads `tds/linux_x86_64`
- Jenkins release normalizes downloaded supplier files into `workspace/tds/linux_x86_64`
- Live builds require `cpack.dat` beside the vendor library

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

## RHEL8 Local

After manually placing the vendor `.so` and `cpack.dat` in `tds/linux_x86_64`:

```bash
cmake --preset linux-rhel8-release
cmake --build --preset linux-rhel8-release --parallel
ctest --preset linux-rhel8-release
cmake --build build/linux-rhel8-release --target report_run_installer
./build/linux-rhel8-release/client_funding_risk_report-installer.run --env qa --prefix "$PWD/install_qa"
./install_qa/bin/report --dry-run --to qa-ops@example.com
```

## Jenkins Release

Pipeline file: [jenkins/Jenkinsfile.release](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.release)

Release assumptions:

- Jenkins user has a cron-refreshed TGT
- that user's profile exports `KRB5CCNAME`
- the pipeline sources the user profile and checks `klist` before Vault Kerberos login and before live smoke
- Artifactory client cert, key, CA, and optional cert password come from Vault, not Jenkins local file credentials
- supplier `so` and `cpack.dat` are normalized into `workspace/tds/linux_x86_64`
- Jenkins builds one `.run` installer per build
- the live smoke installs that `.run` into a temporary directory with `REPORT_RUNTIME_ENV`

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
- optional `REPORT_RUNTIME_ENV`

Core flow:

1. Source the Jenkins user profile and verify `KRB5CCNAME` plus `klist`
2. Kerberos login to Vault
3. Read the Artifactory cert materials from Vault
4. Download the supplier package from Artifactory
5. Normalize `tds_api.so` and `cpack.dat` into `workspace/tds/linux_x86_64`
6. Build, test, and stage one common runtime
7. Build `client_funding_risk_report-*.run`
8. For smoke, install that `.run` with `--env "$REPORT_RUNTIME_ENV" --prefix "$SMOKE_INSTALL_DIR"`
9. Run `"$SMOKE_INSTALL_DIR/bin/report" --dry-run`

## Jenkins PR

Pipeline file: [jenkins/Jenkinsfile.pr](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.pr)

The PR smoke now builds the `.run` installer, installs it into a temporary directory with `--env dev`, and runs the installed `bin/report`.
