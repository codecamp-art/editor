## 1. Project Setup

- [x] 1.1 Create the Spring Boot and Gradle backend project structure.
- [x] 1.2 Add backend configuration profiles for DEV, QA, and PROD.
- [x] 1.3 Define the application-owned `TdsQueryClient` interface and DTOs for client lookup candidates, client summary, and positions.
- [x] 1.4 Add a deterministic stub `TdsQueryClient` implementation for tests and local development.

## 2. Native Adapter

- [x] 2.1 Create the native adapter module and build integration for Linux/RHEL8.
- [x] 2.2 Implement SDK layout validation for `tds_api.h`, `libtds_api.so`, and `cpack.dat`.
- [x] 2.3 Implement TDS session lifecycle: init, DRTP node registration, login, logout, and finalize.
- [x] 2.4 Implement trade date resolution using explicit request value or `TdsApi_reqTradeDate`.
- [x] 2.5 Implement snapshot iteration with handle cleanup on success and failure.
- [x] 2.6 Implement vendor GB18030/GBK to UTF-8 text decoding.

## 3. TDS Data Mapping

- [x] 3.1 Map `TDS_TABLE_ID_CUST_REAL_FUND` rows to client summary fields, including `currency_code`.
- [x] 3.2 Aggregate multiple fund rows by client and currency.
- [x] 3.3 Map `TDS_TABLE_ID_CUST_HOLD` rows to position fields.
- [x] 3.4 Aggregate position rows by client, currency, and contract code.
- [x] 3.5 Add client subscription and response-side filtering for requested client IDs.

## 4. Backend API

- [x] 4.1 Implement `GET /api/tds/clients?query={term}` for client lookup by ID or name.
- [x] 4.2 Implement `GET /api/tds/clients/{clientId}` with optional `tradeDate` for selected client detail.
- [x] 4.3 Validate lookup query, client ID, and trade date request inputs.
- [x] 4.4 Require IP-whitelisted access before client lookup or native TDS access.
- [x] 4.5 Return sanitized errors without passwords, Vault tokens, or raw secrets.
- [x] 4.6 Resolve the native TDS password from Vault instead of YAML configuration.

## 5. Client Query UI

- [x] 5.1 Implement the IP-whitelisted client lookup page with client ID or name search input.
- [x] 5.2 Render selectable client candidates with client ID and client name.
- [x] 5.3 Query selected client details using the selected candidate's client ID.
- [x] 5.4 Render the result using the snapshot structure: summary block, blank separator row, and positions block.
- [x] 5.5 Add an Excel-compatible copy action for the displayed result.
- [x] 5.6 Handle empty input, loading, empty result, and error states.
- [x] 5.7 Add reporting-system top bar and sidebar navigation shell.

## 6. Verification

- [x] 6.1 Add unit tests for summary and position aggregation.
- [x] 6.2 Add API tests for client lookup, successful detail query, invalid input, IP whitelist rejection, and native failure mapping.
- [x] 6.3 Add UI tests for client search/selection, result rendering, and Excel-compatible copy payload.
- [x] 6.4 Add stub-mode integration test that does not load the vendor runtime.
- [x] 6.5 Add Linux build/package verification for native runtime files.
- [x] 6.6 Document operational runbook entries for SDK preparation, configuration, and smoke testing.
- [x] 6.7 Add Windows Win32/x86 local debug build support for the native adapter.
- [x] 6.8 Add Jenkins Artifactory certificate-auth SDK preparation support.
- [x] 6.9 Split Jenkins into separate PR and release pipelines.
- [x] 6.10 Restrict Artifactory SDK download to Jenkins PR/release and require manual local SDK placement.
