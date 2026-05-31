# Reporting Web Runbook

## Local Development

The default configuration uses `tds.mode=stub`, so the web app can run without vendor libraries, DRTP access, or secrets.

```powershell
gradle bootRun --args="--spring.profiles.active=dev"
```

If Gradle is not installed locally, use an installed Gradle distribution or a project wrapper when one is added.

## Native SDK Layout

Local Windows and RHEL8 native builds do not download vendor SDK files. Place the TDS headers, libraries, runtime files, and `cpack.dat` manually under `tds.sdk-root`. The Gradle default is `tds` inside `reporting_web`, which is `D:\Codes\local\Test\reporting_web\tds` in this workspace:

```text
tds/
  include/
    tds_api.h
    tds_api_define.h
    tds_api_struct_type.h
    tds_api_error.h
  linux_x86_64/
    libtds_api.so
    cpack.dat
  win32/
    tds_api.lib
    tds_api.dll
    cpack.dat
```

Validate the layout:

```bash
gradle verifyNativeSdk -PtdsSdkRoot=tds
```

Validate the full curated package layout, including Windows local debug files:

```bash
gradle verifyFullTdsSdk -PtdsSdkRoot=tds
```

## Jenkins SDK Download From Artifactory

Only Jenkins PR and release pipelines download the curated SDK package from Artifactory. Local Windows and RHEL8 builds must use the manually prepared `tds.sdk-root` directory described above.

Jenkins downloads use `cmake/PrepareTdsSdk.cmake` with `TDS_SDK_CONTEXT=jenkins` and certificate authentication. The downloaded package must contain `tds/include`, `tds/linux_x86_64`, and `tds/win32`.

Jenkins uses separate PR and release pipelines:

- `jenkins/Jenkinsfile.pr` prepares the TDS SDK, validates/builds the native adapter, runs tests, builds the boot jar, and optionally runs a stub-mode HTTP smoke test.
- `jenkins/Jenkinsfile.release` prepares the same TDS SDK, runs the same validation/build path, stages the RHEL8 runtime package, and archives `tds-client-query-web-rhel8-<build>.tar.gz`.

Both pipelines call `jenkins/reporting_web_common.groovy`, which fixes the Artifactory package URL and certificate paths in code rather than Jenkins UI parameters.

## Native Adapter Build

Build the Linux native adapter on RHEL8/Linux:

```bash
gradle buildNativeAdapter -PtdsSdkRoot=tds
```

The adapter executable is expected at `build/native/tds_adapter` unless `tds.native-adapter.executable` is overridden.

Build the Windows local debug adapter with a Win32/x86 CMake generator and the vendor files under `tds/win32`:

```powershell
cd D:\Codes\local\Test\reporting_web
.\gradlew.bat buildNativeAdapter `
  -PtdsSdkRoot=tds `
  -PnativeBuildType=Debug `
  -PnativeCmakePlatform=Win32
```

The Windows adapter executable is expected at `build\native\tds_adapter.exe`, with `tds_api.dll` and `cpack.dat` copied next to it. The web app can be started against the Windows adapter for local debugging:

## Windows Local Native Real Environment

Use this path only for local Windows debugging against a real DRTP/TDS environment. QA/PROD and Jenkins release runtime must keep using Vault-backed passwords.

Prerequisites:

- JDK 21 is available on `PATH`.
- CMake is available on `PATH`.
- Visual Studio Build Tools or Visual Studio with MSVC x86 tools is installed.
- The local machine can reach the target DRTP host and port.
- Vendor SDK files are manually placed under `D:\Codes\local\Test\reporting_web\tds`.

Validate the Windows SDK files:

```powershell
cd D:\Codes\local\Test\reporting_web
.\gradlew.bat verifyNativeSdk -PtdsSdkRoot=tds
```

Build the Win32/x86 debug adapter:

```powershell
.\gradlew.bat buildNativeAdapter `
  -PtdsSdkRoot=tds `
  -PnativeBuildType=Debug `
  -PnativeCmakeGenerator="Visual Studio 17 2022" `
  -PnativeCmakePlatform=Win32
```

Build the Spring Boot jar:

```powershell
.\gradlew.bat bootJar
```

Create a local-only config file at `D:\Codes\local\Test\reporting_web\config\application-local-windows.yml`. This path is ignored by git.

```yaml
server:
  port: 18080

app:
  security:
    enabled: false

tds:
  mode: native
  sdk-root: tds
  password-source: local-config
  local-password: "REPLACE_WITH_TDS_PASSWORD"
  native-adapter:
    executable: build/native/tds_adapter.exe
  drtp-endpoints:
    - host: REPLACE_WITH_REAL_DRTP_HOST
      port: 6003
  user: REPLACE_WITH_TDS_USER
  req-timeout-ms: 300000
  log-level: 2000
  klg-enable: false
  function-no: 20100
```

If the password contains YAML-sensitive characters, keep it quoted. Do not commit this file.

Start the web app with the local config:

```powershell
java -jar .\build\libs\tds-client-query-web-0.1.0-SNAPSHOT.jar `
  --spring.config.additional-location=file:./config/application-local-windows.yml
```

Open `http://localhost:18080/`, search a known real client by ID or name, then query the selected client detail.

## Runtime Configuration

Set profile-specific values outside source code:

- `app.security.allowed-ip-ranges`, the CIDR/IP allowlist for clients permitted to open the page and API
- `tds.drtp-endpoints`
- `tds.user`
- `tds.password-source`, defaulting to `vault`; use `local-config` only for Windows local native debug
- `tds.local-password`, only in ignored local Windows debug config when `tds.password-source=local-config`
- `tds.vault.address` or `VAULT_ADDR`
- `tds.vault.namespace` or `VAULT_NAMESPACE`, if the Vault deployment requires a namespace
- `tds.vault.secret-engine` or `VAULT_SECRET_ENGINE`
- `tds.vault.secret-path` or `VAULT_SECRET_PATH`
- `tds.vault.secret-key` or `VAULT_SECRET_KEY`, defaulting to `password`
- `tds.req-timeout-ms`
- `tds.log-level`
- `tds.klg-enable`
- `tds.function-no`

Do not configure `tds.password` in YAML. By default, native mode reads the TDS password from Vault by calling `POST /v1/auth/kerberos/login`, then `GET /v1/<secret-engine>/data/<secret-path>` and reading the configured secret key. The runtime user must already have a valid Kerberos TGT, for example through `kinit` or the deployment host's ticket refresh process. The only direct password exception is ignored Windows local debug config with `tds.password-source=local-config` and `tds.local-password`.

Do not commit real DRTP endpoints, TDS passwords, Vault tokens, certificates, or private keys.

## Smoke Test

Before enabling native mode for users:

1. Run the unit and API tests.
2. Run `gradle verifyNativeSdk`.
3. Build the native adapter on Linux.
4. Start the app with `tds.mode=native` and QA configuration.
5. Search a known QA client by ID and name.
6. Query the client detail page.
7. Copy the result and paste into Excel to verify the row and column structure.

## IP Whitelist Access

The web app enforces `app.security.allowed-ip-ranges` before serving the page or API. Entries may be exact IPs or CIDR ranges, for example:

```yaml
app:
  security:
    enabled: true
    allowed-ip-ranges:
      - 10.20.30.0/24
      - 192.168.10.15
```

The filter uses the request remote address seen by the application server. If the service is deployed behind a reverse proxy or load balancer, configure that layer so the application receives the real client IP before relying on the allowlist.
