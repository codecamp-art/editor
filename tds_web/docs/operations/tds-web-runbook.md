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
```

Validate the layout:

```bash
gradle verifyNativeSdk -PtdsSdkRoot=/path/to/tds
```

## Native Adapter Build

Build the Linux native adapter on RHEL8/Linux:

```bash
gradle buildNativeAdapter -PtdsSdkRoot=/path/to/tds
```

The adapter executable is expected at `build/native/tds_adapter` unless `tds.native-adapter.executable` is overridden.

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
