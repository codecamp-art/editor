# Report

`report` is a standalone C++17 program that reads the vendor TDS snapshot, exports a CSV report, and sends the report through SMTP by calling `curl`.

## What It Produces

- CSV fields: `trade_date`, `cust_no`, `cust_name`, `fund_account_no`, `currency_code`, `dyn_rights`, `hold_profit`, `avail_fund`, `risk_degree1`, `risk_degree2`
- Dry-run mail preview: `.eml`
- Runtime logs: `logs/report-YYYY-MM-DD.log`

## Config

Default config selection:

- `--env dev` -> `config/dev.properties`
- `--env qa` -> `config/qa.properties`
- `--env prod` -> `config/prod.properties`

If `config/report.properties` exists, it overrides the per-environment files.

Important keys:

- `tds.drtp_host`, `tds.drtp_port`, `tds.user`, `tds.password`
- `smtp.*`
- `email.default_to`, `email.default_cc`
- `report.output_dir`
- `log.dir`, `log.level`
- `vault.*` when a value uses `vault://...`

`tds.password` supports:

- plain text
- `${ENV_VAR:default}`
- `vault://secret/path#field`

Relative paths inside the config are resolved from the config file directory.

## Vault And Kerberos

The secret resolution path is fixed to Kerberos:

1. When a config value uses `vault://...`, the program runs `vault login -method=kerberos -token-only -no-store`.
2. It then uses the returned session token only for the following `vault kv get`.

For this project, Kerberos TGT creation is outside the program. On RHEL8 the expected setup is that a cron job refreshes the TGT in advance. The program and Jenkins job only reuse the existing ticket cache.

Practical rules:

- keep `VAULT_KERBEROS_USERNAME` in Jenkins
- if the target user already gets `KRB5CCNAME` from the shell profile, no extra code or config is needed
- keep `keytab_path` and `krb5conf_path` only as optional overrides when the node cannot reuse the existing cache cleanly

## CMake Presets

See [CMakePresets.json](CMakePresets.json).

Available configure presets:

- `windows-stub-x64`
- `windows-live-x64`
- `windows-live-x86`
- `linux-rhel8-release`

## Minimal Commands

### Windows Local

Stub build:

```powershell
cmake --preset windows-stub-x64
cmake --build --preset windows-stub-x64-debug
ctest --preset windows-stub-x64-debug
.\build\windows-stub-x64\Debug\report.exe --env dev --config .\config\dev.properties --stub-file .\tests\data\stub_snapshot.csv --dry-run --to debug@example.com
```

Live build when the vendor gives Windows `.lib + .dll`:

```powershell
cmake --preset windows-live-x64 -DTDS_VENDOR_LIBRARY=D:/vendor/tds/win64/tds_api.lib -DTDS_VENDOR_RUNTIME=D:/vendor/tds/win64/tds_api.dll
cmake --build --preset windows-live-x64-debug
ctest --preset windows-live-x64-debug
```

If the supplier package is truly 32-bit, use `windows-live-x86` instead.

### RHEL8 Local

```bash
cmake -S . -B build_release -G Ninja -DCMAKE_BUILD_TYPE=Release -DTDS_VENDOR_LIBRARY=/opt/tds/lib/tds_api.so -DREPORT_STAGE_DIR="$PWD/build_release/client_funding_risk_report"
cmake --build build_release --parallel
ctest --test-dir build_release --output-on-failure
cmake --build build_release --target report_stage
./build_release/client_funding_risk_report/bin/report --env qa --config ./build_release/client_funding_risk_report/config/qa.properties --dry-run --to qa-ops@example.com
```

### Jenkins PR

Pipeline file: [jenkins/Jenkinsfile.pr](jenkins/Jenkinsfile.pr)

Core commands:

```bash
cmake -S . -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE="$BUILD_TYPE" -DTDS_VENDOR_LIBRARY="$TDS_VENDOR_LIBRARY"
cmake --build "$BUILD_DIR" --parallel
ctest --test-dir "$BUILD_DIR" --output-on-failure
"$BUILD_DIR/report" --env dev --config "$CONFIG_PATH" --stub-file "$PROJECT_DIR/tests/data/stub_snapshot.csv" --dry-run --to pr-smoke@example.com
```

### Jenkins Release On RHEL8

Pipeline file: [jenkins/Jenkinsfile.release](jenkins/Jenkinsfile.release)

Core commands:

```bash
cmake -S . -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE="$BUILD_TYPE" -DTDS_VENDOR_LIBRARY="$EFFECTIVE_VENDOR_LIBRARY" -DREPORT_STAGE_DIR="$STAGE_DIR"
cmake --build "$BUILD_DIR" --parallel
ctest --test-dir "$BUILD_DIR" --output-on-failure
cmake --build "$BUILD_DIR" --target report_stage
"$STAGE_DIR/bin/report" --env "$SMOKE_ENV" --config "$CONFIG_PATH" --dry-run --to "$SMOKE_TO"
```

Kerberos-related Jenkins parameters:

- `VAULT_ADDR`
- `VAULT_KERBEROS_USERNAME`
- optional `VAULT_KERBEROS_SERVICE`
- optional `VAULT_KERBEROS_REALM`
- optional keytab and `krb5.conf` credentials only when cache reuse is not enough

## Stage Layout

RHEL8 stage directory:

```text
client_funding_risk_report/
  bin/
    report
  lib/
    tds_api.so
    cpack.dat
  config/
  scripts/
  logs/
  README.md
```

Windows stage directory:

```text
client_funding_risk_report/
  bin/
    report.exe
    tds_api.dll
    cpack.dat
  config/
  scripts/
  logs/
  README.md
```

## Runtime Examples

RHEL8 dry-run:

```bash
./build_release/client_funding_risk_report/bin/report --env dev --config ./build_release/client_funding_risk_report/config/dev.properties --dry-run
```

Selected customers:

```bash
./build_release/client_funding_risk_report/bin/report --env prod --config /opt/report/config/prod.properties --cust-list 1001,1002,1003
```

Airflow-style recipient override:

```bash
/opt/report/bin/report --env prod --to trading-ops@example.com,risk@example.com --cc support@example.com
```
