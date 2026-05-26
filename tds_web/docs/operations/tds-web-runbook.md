# TDS Web Runbook

## Local Development

The default configuration uses `tds.mode=stub`, so the web app can run without vendor libraries, DRTP access, or secrets.

```powershell
gradle bootRun --args="--spring.profiles.active=dev"
```

If Gradle is not installed locally, use an installed Gradle distribution or a project wrapper when one is added.

## Native SDK Layout

Linux native mode expects the prepared vendor SDK at `tds.sdk-root`, defaulting to `../tds`:

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
gradle verifyNativeSdk -PtdsSdkRoot=/path/to/tds
```

Validate the full curated package layout, including Windows local debug files:

```bash
gradle verifyFullTdsSdk -PtdsSdkRoot=/path/to/tds
```

## SDK Download From Artifactory

Local and Jenkins builds can prepare `tds.sdk-root` from the curated Artifactory package with `cmake/PrepareTdsSdk.cmake`. The package must contain `tds/include`, `tds/linux_x86_64`, and `tds/win32`.

Windows local debug downloads use token authentication:

```powershell
cd D:\Codes\local\Test\tds_web
Copy-Item .\tds.properties.example .\tds.properties
# Edit tds.properties with tds.sdk.url and token values.
.\gradlew.bat prepareTdsSdk -PtdsSdkRoot=..\tds
```

RHEL8/Jenkins downloads use Artifactory certificate authentication:

```bash
./gradlew prepareTdsSdk \
  -PtdsSdkRoot="$WORKSPACE/tds" \
  -PtdsSdkUrl="https://artifactory.example.com/artifactory/vendor/tds_sdk.zip" \
  -PtdsSdkAuth=cert \
  -PtdsSdkCertFile=/jenkins/build.pem \
  -PtdsSdkKeyFile=/jenkins/build.key
```

Jenkins should normally call `jenkins/tds_web_common.groovy`, which fixes the package URL and certificate paths in code rather than Jenkins UI parameters.

## Native Adapter Build

Build the Linux native adapter on RHEL8/Linux:

```bash
gradle buildNativeAdapter -PtdsSdkRoot=/path/to/tds
```

The adapter executable is expected at `build/native/tds_adapter` unless `tds.native-adapter.executable` is overridden.

Build the Windows local debug adapter with a Win32/x86 CMake generator and the vendor files under `tds/win32`:

```powershell
cd D:\Codes\local\Test\tds_web
.\gradlew.bat buildNativeAdapter `
  -PtdsSdkRoot=..\tds `
  -PnativeBuildType=Debug `
  -PnativeCmakePlatform=Win32
```

The Windows adapter executable is expected at `build\native\tds_adapter.exe`, with `tds_api.dll` and `cpack.dat` copied next to it. The web app can be started against the Windows adapter for local debugging:

```powershell
java -jar .\build\libs\tds-client-query-web-0.1.0-SNAPSHOT.jar `
  --server.port=18080 `
  --app.security.enabled=false `
  --tds.mode=native `
  --tds.native-adapter.executable=build\native\tds_adapter.exe
```

Native mode still reads the TDS password from Vault; configure `VAULT_ADDR`, `VAULT_SECRET_ENGINE`, `VAULT_SECRET_PATH`, and `VAULT_SECRET_KEY` before starting the web app.

## Runtime Configuration

Set profile-specific values outside source code:

- `app.security.allowed-ip-ranges`, the CIDR/IP allowlist for clients permitted to open the page and API
- `tds.drtp-endpoints`
- `tds.user`
- `tds.vault.address` or `VAULT_ADDR`
- `tds.vault.namespace` or `VAULT_NAMESPACE`, if the Vault deployment requires a namespace
- `tds.vault.secret-engine` or `VAULT_SECRET_ENGINE`
- `tds.vault.secret-path` or `VAULT_SECRET_PATH`
- `tds.vault.secret-key` or `VAULT_SECRET_KEY`, defaulting to `password`
- `tds.req-timeout-ms`
- `tds.log-level`
- `tds.klg-enable`
- `tds.function-no`

Do not configure `tds.password` in YAML. Native mode reads the TDS password from Vault by calling `POST /v1/auth/kerberos/login`, then `GET /v1/<secret-engine>/data/<secret-path>` and reading the configured secret key. The runtime user must already have a valid Kerberos TGT, for example through `kinit` or the deployment host's ticket refresh process.

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
