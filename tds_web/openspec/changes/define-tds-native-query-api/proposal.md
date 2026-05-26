## Why

`tds_web` needs a clear contract for an IP-whitelisted web page where users can query client data from the vendor TDS runtime and copy the result into Excel. `tds_reporter` already proves the native login, DRTP registration, trade-date lookup, customer fund snapshot, SDK preparation, and Linux packaging path; this change captures only the parts needed by the web project and adds the page/API shape required by the product snapshot.

## What Changes

- Define a web page with the provided reporting-system style, including top navigation and sidebar, where a user from an allowed client IP can input a client search term, select a client by ID or name, and view the same data structure shown in `docs/product/client_data_snapshot.png`.
- Define a copy-to-Excel capability that preserves the table-like structure from the displayed result.
- Define a backend capability for querying TDS-backed client summary and positions through application APIs.
- Define how the native TDS adapter should initialize sessions, register DRTP nodes, authenticate, request snapshots, iterate records, decode vendor text, close handles, and clean up.
- Define compile, packaging, and runtime requirements for Linux deployment and Windows local diagnostics.
- Define which customer fields are already proven by `tds_reporter` and which position fields require a new native mapping from the vendor `TDS_TABLE_ID_CUST_HOLD` table.
- Keep email/report generation out of scope for `tds_web`.

## Capabilities

### New Capabilities
- `tds-client-query`: Web backend APIs and native adapter behavior for searching clients and querying selected client TDS summary and position data.
- `client-query-ui`: Browser UI behavior for searching/selecting one client by ID or name, rendering the snapshot-shaped result, and copying the result for Excel paste.

### Modified Capabilities
- None.

## Impact

- Affects future backend modules for Java 21, Spring Boot, Gradle, and the native C/C++ adapter.
- Affects the first web UI for client lookup, result display, and copy-to-Excel behavior.
- Introduces an API contract for the exact data blocks in the product snapshot: client summary and positions.
- Requires the vendor TDS SDK package layout used by `tds_reporter`: headers under `tds/include`, Linux library under `tds/linux_x86_64`, and Windows `.dll`/`.lib` diagnostic files under `tds/win32`.
- Requires externalized DEV/QA/PROD configuration for DRTP endpoints, TDS user, password/Vault lookup, request timeout, log level, KLG flag, and function number.
