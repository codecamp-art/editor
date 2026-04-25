# Report

`report` is a standalone C++17 program that reads the TDS snapshot, writes a CSV report, and sends mail through `curl`.

## Config Model

The package is environment-neutral.

- `config/report.properties.template` holds the shared defaults
- `config/dev.properties`, `config/qa.properties`, and `config/prod.properties` only hold environment-specific overrides
- the SMTP section is maintained once in `report.properties.template`, not duplicated per environment
- the Vault section is also reduced to shared settings only: `vault.executable`, `vault.address`, `vault.namespace`, and `vault.auth_path`

At install time or via `run-report`, the selected environment overlay is merged onto `report.properties.template` and written to `config/report.properties`.

After that, users start `bin/report` directly and do not need `--env` or `--config`.

## Packaging Model

- Windows local build is only for debugging and does not need installer packaging
- RHEL8 local and Jenkins release both produce the same `.run` installer

Linux release flow:

1. Build the staged runtime
2. Wrap it into one `.run` installer
3. Execute the installer with `--env`
4. The installer extracts the package, renders `config/report.properties`, and leaves a ready-to-run standalone directory behind

## Linux `.run` Installer

Usage:

```bash
./client_funding_risk_report-installer.run --env qa --prefix /home/user/apps/report
```

Behavior:

1. Extract into `--prefix`
2. Merge `config/report.properties.template` with `config/qa.properties`
3. Write the result to `config/report.properties`
4. Leave `bin/report`, `run-report.sh`, and all runtime files in that directory

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

`report.exe --env dev` works in-place because the program now loads `config/report.properties.template` first and then overlays `config/dev.properties`.

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

The application does not read or require `kerberos_realm`, `keytab_path`, `krb5conf_path`, `service`, or `disable_fast_negotiation` from properties anymore. If `klist` already works after switching to that user, no extra runtime flag is needed.

## RHEL8 Local

After manually placing the vendor `.so` and `cpack.dat` in `tds/linux_x86_64`:

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
cmake --build --preset linux-rhel8-release --target report_run_installer
./build/linux-rhel8-release-make/client_funding_risk_report-installer.run --env qa --prefix "$PWD/install_qa"
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
- optional `VAULT_NAMESPACE`
- optional `VAULT_AUTH_PATH`
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
