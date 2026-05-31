## Context

`tds_reporter` is a C++17 batch program that already validates the vendor TDS integration path:

- Native session setup calls `TdsApi_init`, registers each configured DRTP endpoint with `TdsApi_addDrtpNode`, then logs in with `TdsApi_reqLogin`.
- Data lookup calls `TdsApi_reqTradeDate`, optionally filters by client through `TdsApi_subscribeDataByCust`, requests `TDS_TABLE_ID_CUST_REAL_FUND` through `TdsApi_reqSnapshot`, iterates with `TdsApi_hasNext` and `TdsApi_getNext`, and always closes the handle with `TdsApi_closeHandle`.
- Session cleanup calls `TdsApi_reqLogout` and `TdsApi_finalize`.
- Vendor text and error messages are decoded from GB18030/GBK to UTF-8 before application use.
- Linux/RHEL8 is the production build target and links `tds/linux_x86_64/libtds_api.so`; Windows local diagnostics require Win32/x86 because the vendor SDK supplies 32-bit `.lib` and `.dll` files.
- Local Windows and RHEL8 builds read manually placed SDK files from `tds.sdk-root`, defaulting to `reporting_web/tds`; SDK download and extraction are Jenkins-only through `cmake/PrepareTdsSdk.cmake` with certificate/key auth.

`reporting_web` has no application code yet. The project direction is Java 21, Spring Boot, Gradle, Linux deployment, IP whitelist access control, and a small native adapter rather than direct vendor API use from application logic.

## Goals / Non-Goals

**Goals:**
- Define the future web contract for finding a client by ID or name and querying the selected client's TDS summary and positions.
- Define the first UI result shape so it matches the product snapshot's data structure and can be copied into Excel.
- Reuse the verified native call order, configuration model, text decoding, error handling, SDK layout, and Linux deployment assumptions from `tds_reporter`.
- Keep the vendor API isolated behind a small backend/native boundary.
- Make clear which fields are available from the existing reporter implementation and which require new native mapping.

**Non-Goals:**
- Do not implement Spring Boot, JNI/JNA, C++ adapter code, or frontend screens in this proposal step.
- Do not migrate the email report, SMTP, or report HTML generation logic into `reporting_web`.
- Do not require the initial UI to match the screenshot styling exactly; the data structure must match.
- Do not design complex entitlement management beyond requiring IP-whitelisted access.
- Do not treat Windows as a production deployment target.

## Decisions

1. Use a small native adapter boundary for TDS access.

   Spring application services will depend on a project-owned `TdsQueryClient` interface, not vendor headers or structs. The first implementation can use a native C++ adapter linked to the vendor SDK; tests and local development can use a deterministic stub implementation.

   Alternatives considered:
   - Direct vendor API access from Java via broad JNA mappings: faster to prototype, but leaks vendor structs and lifecycle rules throughout application code.
   - Reusing `tds_reporter` as a command-line data source: low implementation cost, but it only exposes the funding report path and includes email/report concerns that do not belong in the web API.

2. Keep the initial data flow synchronous.

   The first backend implementation will use `TdsApi_reqSnapshot` plus `TdsApi_hasNext` and `TdsApi_getNext`, matching `tds_reporter`. The vendor async callback API is deferred until there is a measured need.

   Alternatives considered:
   - `TdsApi_enableAsyncNotify`: useful for streaming, but adds callback threading concerns and is not used by the proven reporter path.

3. Query client summary from `TDS_TABLE_ID_CUST_REAL_FUND`.

   Proven reporter fields are `cust_no`, `cust_name`, `fund_account_no`, `dyn_rights`, `hold_profit`, `avail_fund`, `risk_degree1`, and `risk_degree2`. The vendor struct also contains `currency_code`, but the current reporter data model does not carry it; `reporting_web` must map it explicitly because the web requirements include currency.

4. Query positions from `TDS_TABLE_ID_CUST_HOLD`.

   The vendor header defines `TTds_Cust_Hold`, including `cust_no`, `currency_code`, `contract_code`, `bs_flag`, `hold_qty`, and `today_hold_qty`. `tds_reporter` does not implement this table, so `reporting_web` must add and test this mapping. Use `TDS_BUY_DIRECTION` as long quantity and `TDS_SELL_DIRECTION` as short quantity. Aggregate by client, currency, and contract code.

5. Treat client selection as a lookup step before detail query.

   The UI should not require users to know the exact client ID. It should call a lookup API with a search term and present candidate clients by ID and name. After a candidate is selected, the detail API should query TDS by the selected `clientId`, because the proven vendor subscription API filters by customer number.

6. Externalize all runtime configuration.

   DEV/QA/PROD must provide DRTP endpoints, TDS user, Vault location, request timeout, TDS log level, KLG flag, and function number outside source code. The web project should keep the same base-plus-environment overlay idea from `tds_reporter`, adapted to Spring configuration. Native mode reads the TDS password from Vault using Kerberos login and KV v2 by default. Windows local native debugging may explicitly set `tds.password-source=local-config` and read `tds.local-password` from an ignored local config file; this bypass must not be used by QA/PROD or Jenkins release runtime.

7. Preserve Linux release as the production path.

   Production packages must include the Java service, native adapter, vendor `libtds_api.so`, `cpack.dat`, and environment config. Windows support is limited to local diagnostics and must not be required for production.

   Windows local diagnostics use a Win32/x86 adapter build linked to `tds/win32/tds_api.lib`; the adapter output directory must also contain the vendor `.dll` and `cpack.dat`. Local Windows and RHEL8 builds require developers to place those files manually under `reporting_web/tds` by default. Jenkins PR and release pipelines prepare the same curated SDK package before native builds by downloading it from Artifactory using certificate/key authentication.

8. Render the first UI as a copy-friendly table.

   The product snapshot is effectively one worksheet-shaped result: a summary header row, one summary data row, a blank separator row, a positions header row, then position data rows. The UI can use different visual styling, but the DOM should preserve a tabular structure and provide a copy action that writes tab-separated and HTML table clipboard content so pasting into Excel keeps the same rows and columns.

9. Use the reporting-system shell from the updated reference image.

   The first screen should include a blue top bar, signed-in user affordance, left navigation sidebar with only the functional `Client TDS Query` item, query panel, and separate result cards for client summary and positions.

## Risks / Trade-offs

- Vendor API process-global lifecycle may not be thread-safe -> serialize native session initialization/query/cleanup initially, then introduce pooling only after concurrency tests.
- Native crashes can take down the JVM if JNI/JNA runs in-process -> keep the adapter boundary narrow and consider an out-of-process adapter if stability testing shows native crash risk.
- `tds_reporter` currently omits `currency_code` from its internal model -> `reporting_web` must not copy that omission because the web result requires currency.
- Position mapping is inferred from vendor headers, not proven in reporter production logic -> add stub tests and live dry-run smoke validation before enabling production use.
- Network allowlist and Vault/local debug password configuration differ between batch and web runtime -> keep secret values out of logs and command lines, ignore local password config files, and validate the deployed client IP ranges on RHEL8.
- Browser clipboard behavior can vary by environment -> provide an explicit copy button and automated tests for the generated clipboard payload, with normal text selection as a fallback.
