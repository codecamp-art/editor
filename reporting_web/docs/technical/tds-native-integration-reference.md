# TDS Integration Notes

This document keeps only the TDS details needed for the initial `reporting_web` project. For deeper vendor/API details, read the root `tds/` folder and the existing `tds_reporter/` implementation.

## Project Scope

`reporting_web` needs:

- An IP-whitelisted web page for permitted network clients.
- A client ID or name search input.
- A selectable list of matching client candidates.
- A result table matching the data structure in `docs/product/client_data_snapshot.png`.
- A copy action that pastes into Excel with the same rows and columns.
- A backend API that gets the data from the vendor TDS native runtime.

Out of scope for the initial version:

- Admin pages.
- Complex entitlement management.
- Editing TDS data.
- Mobile-specific UI.
- Production HA design.
- Email/report generation from `tds_reporter`.

## Suggested API Shape

Client lookup endpoint:

```http
GET /api/tds/clients?query={clientIdOrName}
```

Suggested lookup response:

```json
[
  {
    "clientId": "1001",
    "clientName": "Alpha Capital"
  }
]
```

The UI should show these candidates and use the selected candidate's `clientId` for the detail query.

Initial backend endpoint:

```http
GET /api/tds/clients/{clientId}?tradeDate=YYYYMMDD
```

`tradeDate` is optional. If omitted, the backend should ask TDS for the current trade date.

Response shape:

```json
{
  "clientId": "1001",
  "tradeDate": 20260418,
  "summary": {
    "clientId": "1001",
    "currency": "CNY",
    "marginAvailable": 100000000.00,
    "totalEquity": 100000000.00,
    "riskRatio": 0
  },
  "positions": [
    {
      "position": "ag2703",
      "totalLong": 0,
      "totalShort": 100,
      "intradayLong": 0,
      "intradayShort": 0
    }
  ]
}
```

Keep internal fields such as `riskRatio1`, `riskRatio2`, `fundAccountNo`, and `clientName` if useful, but the first UI must be able to render the snapshot columns exactly:

- `Client ID`
- `Currency`
- `Margin Available`
- `Total Equity`
- `Risk Ratio (%)`
- `Positions`
- `Total Long`
- `Total Short`
- `Intraday Long`
- `Intraday Short`

## Native Adapter Call Flow

Hide vendor API calls behind a small project-owned adapter. Application services should call a `TdsQueryClient`-style interface, not vendor structs directly.

Use the synchronous flow proven by `tds_reporter`:

1. `TdsApi_init`
2. `TdsApi_addDrtpNode` for each configured DRTP endpoint
3. `TdsApi_reqLogin`
4. `TdsApi_reqTradeDate` when request `tradeDate` is absent
5. `TdsApi_subscribeDataByCust` for the requested client ID
6. `TdsApi_reqSnapshot`
7. `TdsApi_hasNext` and `TdsApi_getNext`
8. `TdsApi_closeHandle`
9. `TdsApi_reqLogout`
10. `TdsApi_finalize`

Always close handles and finalize the session on both success and failure.

## TDS Tables Needed

### Client Summary

Use `TDS_TABLE_ID_CUST_REAL_FUND`, struct `TTds_Cust_Real_Fund`.

This table includes both `cust_no` and `cust_name`, so it can also support client lookup candidates when no separate master-client source is introduced.

Required mapping:

| Vendor field | Web field |
|---|---|
| `cust_no` | `clientId` |
| `currency_code` | `currency` |
| `avail_fund` | `marginAvailable` |
| `dyn_rights` | `totalEquity` |
| `risk_degree1` or `risk_degree2` | `riskRatio` |

Notes:

- `tds_reporter` already uses this table for funding and risk data.
- `tds_reporter` does not currently expose `currency_code`; `reporting_web` must map it.
- Confirm with business which native risk field should drive the single UI column `Risk Ratio (%)`.

### Positions

Use `TDS_TABLE_ID_CUST_HOLD`, struct `TTds_Cust_Hold`.

Required mapping:

| Vendor field | Web field |
|---|---|
| `contract_code` | `position` |
| `bs_flag` | long/short direction |
| `hold_qty` | `totalLong` or `totalShort` |
| `today_hold_qty` | `intradayLong` or `intradayShort` |

Direction rules from vendor headers:

- `TDS_BUY_DIRECTION` means long.
- `TDS_SELL_DIRECTION` means short.

Aggregate rows by client, currency, and contract code.

## Build and Runtime Inputs

Vendor package layout expected from existing TDS work:

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

Production target:

- Linux/RHEL8.
- Java 21 + Spring Boot + Gradle.
- Native adapter linked to the Linux `.so`.
- Package must include `libtds_api.so` and `cpack.dat`.

Windows files are for local diagnostics only, not production deployment. A Windows local debug build must use a Win32/x86 CMake generator because the vendor SDK provides 32-bit `tds_api.lib`/`tds_api.dll` files under `tds/win32`. The build copies `tds_api.dll` and `cpack.dat` next to `tds_adapter.exe`.

Local Windows and RHEL8 builds do not download SDK files. Developers manually place the curated vendor files under `tds.sdk-root`, which defaults to `tds` inside `reporting_web`.

Only Jenkins PR and release pipelines download the curated SDK package from Artifactory. Jenkins invokes `cmake/PrepareTdsSdk.cmake` with `TDS_SDK_CONTEXT=jenkins`, certificate authentication, and validation for both `tds/linux_x86_64` and `tds/win32`.

## Configuration

Externalize these values per DEV/QA/PROD:

| Key | Purpose |
|---|---|
| `tds.drtp_endpoints` | DRTP `host:port` list |
| `tds.user` | TDS login user |
| `tds.vault.address` / `VAULT_ADDR` | Vault base URL |
| `tds.vault.namespace` / `VAULT_NAMESPACE` | Optional Vault namespace |
| `tds.vault.secret-engine` / `VAULT_SECRET_ENGINE` | Vault KV v2 secret engine |
| `tds.vault.secret-path` / `VAULT_SECRET_PATH` | Vault secret path containing the TDS password |
| `tds.vault.secret-key` / `VAULT_SECRET_KEY` | Secret field name, default `password` |
| `tds.req_timeout_ms` | Native request timeout |
| `tds.log_level` | Vendor log level |
| `tds.klg_enable` | Vendor KLG output flag |
| `tds.function_no` | Vendor function number |

Do not configure `tds.password` in YAML. Native mode reads the TDS password from Vault using Kerberos login and Vault KV v2. Do not commit secrets. Do not log passwords, Vault tokens, or raw secret values.

## Text and Error Handling

Vendor text can be GB18030/GBK. Decode names and error messages to UTF-8 before returning data to Java or the browser.

When TDS reports no more data, treat that as normal end-of-iteration, not a user-visible error.

## Stub Mode

Add a deterministic stub implementation for tests and local development.

Stub mode should:

- Avoid live DRTP connections.
- Avoid vendor secrets and Vault lookup.
- Return enough summary and position data to test rendering and Excel copy behavior.

## Minimum Tests

- Summary field mapping.
- Position field mapping and aggregation.
- TDS error sanitization.
- API success and validation failures.
- IP whitelist access boundary.
- UI rendering in the snapshot structure.
- Copy-to-Excel payload format.
- Stub mode without loading the vendor runtime.
