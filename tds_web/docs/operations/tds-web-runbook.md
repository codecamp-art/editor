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

- `tds.drtp-endpoints`
- `tds.user`
- `tds.password`, usually through environment or Vault integration
- `tds.req-timeout-ms`
- `tds.log-level`
- `tds.klg-enable`
- `tds.function-no`

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

## Authentication

The first implementation enforces an authenticated Spring Security context. Production Kerberos/SPNEGO wiring should be configured for the deployment environment before exposing the service to users.
