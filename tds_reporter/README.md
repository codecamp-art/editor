# Report

`report` is a standalone C++17 program that reads the TDS snapshot, writes a CSV report, and sends mail through `curl`.

## Config Model

The package is environment-neutral.

- `config/report.properties.template` holds the shared defaults
- `config/dev.properties`, `config/qa.properties`, and `config/prod.properties` only hold environment-specific overrides
- the SMTP section is maintained once in `report.properties.template`, not duplicated per environment
- SMTP relay authentication is certificate-only; there is no SMTP username/password configuration
- the Vault section is also reduced to shared settings only: `vault.curl_executable`, `vault.address`, `vault.namespace`, and `vault.auth_path`

At install time, the selected environment overlay is merged onto `report.properties.template` and written to `config/report.properties`.

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
4. Remove the environment overlays and template from the installed `config` directory
5. Leave `bin/report` and a ready-to-run `config/report.properties` in that directory

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

To switch environment later, rerun the installer with another `--env` value:

```bash
./client_funding_risk_report-installer.run --env prod --prefix /home/user/apps/report
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

`report.exe --env dev` works in-place because the program now loads `config/report.properties.template` first and then overlays `config/dev.properties`.

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

If an older generated installer reports `Installer payload marker was not found`, remove that `.run` file and rebuild the `report_run_installer` target.

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

Shared Jenkins helper: [jenkins/report_common.groovy](/D:/Codes/local/Test/tds_reporter/jenkins/report_common.groovy)

Release assumptions:

- Jenkins user has a cron-refreshed TGT
- the Jenkins systemd service startup loads the user's profile so the Jenkins process inherits `KRB5CCNAME`
- the pipeline trusts the inherited Kerberos environment and does not re-check `KRB5CCNAME` or run `klist`
- Jenkins has `curl` available through `CURL_BIN` or `PATH`
- Jenkins parses Vault JSON with the built-in Groovy JSON parser, not Python
- Artifactory client cert, key, CA, and optional cert password come from Vault, not Jenkins local file credentials
- supplier files are normalized into `workspace/tds/include/tds_api.h`, `workspace/tds/linux_x86_64/libtds_api.so`, and `workspace/tds/linux_x86_64/cpack.dat`
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
- optional `CURL_BIN`
- optional `VENDOR_PACKAGE_PASSWORD_VAULT_PATH`
- optional `REPORT_RUNTIME_ENV`

Core flow:

1. Kerberos login to Vault through the HTTP API with curl
2. Read the Artifactory cert materials from Vault through the HTTP API
3. Download the supplier package from Artifactory
4. Normalize the supplier files into `workspace/tds/include/tds_api.h`, `workspace/tds/linux_x86_64/libtds_api.so`, and `workspace/tds/linux_x86_64/cpack.dat`
5. Build, test, and stage one common runtime
6. Build `client_funding_risk_report-*.run`
7. For smoke, install that `.run` with `--env "$REPORT_RUNTIME_ENV" --prefix "$SMOKE_INSTALL_DIR"`
8. Run `"$SMOKE_INSTALL_DIR/bin/report" --dry-run`

## Jenkins PR

Pipeline file: [jenkins/Jenkinsfile.pr](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.pr)

Shared Jenkins helper: [jenkins/report_common.groovy](/D:/Codes/local/Test/tds_reporter/jenkins/report_common.groovy)

The PR pipeline now uses the same supplier package preparation model as release:

- optional `ARTIFACTORY_PACKAGE_URL` downloads the supplier package from Artifactory
- Artifactory certificate, optional key, optional CA, optional certificate password, and optional supplier ZIP password come from Vault
- the downloaded package may be ZIP, password-protected ZIP, `.tar.gz`, or `.tar`
- extracted supplier files are normalized into `workspace/tds/include/tds_api.h`, `workspace/tds/linux_x86_64/libtds_api.so`, and `workspace/tds/linux_x86_64/cpack.dat`
- if `ARTIFACTORY_PACKAGE_URL` is not set, PR expects those fixed paths to be pre-populated in the workspace
- PR smoke builds the `.run` installer, installs it into a temporary directory with `--env dev`, and runs the installed `bin/report`
