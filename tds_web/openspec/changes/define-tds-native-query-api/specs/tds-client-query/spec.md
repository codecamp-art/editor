## ADDED Requirements

### Requirement: Client Lookup API
The system SHALL expose a backend API for requests from allowed client IPs to search client candidates by client ID or client name. The initial endpoint SHALL be `GET /api/tds/clients?query={term}` and each candidate SHALL include `clientId` and `clientName`.

#### Scenario: Search clients by ID
- **WHEN** a request from an allowed client IP searches with query `1001`
- **THEN** the system returns matching client candidates whose client ID or client name matches the query

#### Scenario: Search clients by name
- **WHEN** a request from an allowed client IP searches with a client name fragment
- **THEN** the system returns matching client candidates with both `clientId` and `clientName`

#### Scenario: Empty lookup query is rejected
- **WHEN** a request from an allowed client IP calls the client lookup API with an empty query
- **THEN** the system returns a validation error and does not call the native TDS detail query path

### Requirement: IP-Whitelisted Client Query API
The system SHALL expose a backend API for requests from allowed client IPs to query one selected TDS client by client ID. The initial endpoint SHALL be `GET /api/tds/clients/{clientId}` with an optional `tradeDate` query parameter in `YYYYMMDD` format.

#### Scenario: Allowed client IP queries a client
- **WHEN** a request from an allowed client IP calls `GET /api/tds/clients/1001`
- **THEN** the system returns a client query response containing a client summary and a positions array

#### Scenario: Request outside IP whitelist is rejected
- **WHEN** a request reaches the client query API from an IP address outside the configured whitelist
- **THEN** the system rejects the request before calling the native TDS adapter

### Requirement: TDS Session Lifecycle
The system MUST isolate vendor TDS access behind an application-owned adapter and MUST use the vendor session lifecycle in this order: `TdsApi_init`, one or more `TdsApi_addDrtpNode` calls, `TdsApi_reqLogin`, data requests, `TdsApi_closeHandle` for each request handle, `TdsApi_reqLogout`, and `TdsApi_finalize`.

#### Scenario: Successful native query closes resources
- **WHEN** a client query completes successfully
- **THEN** every TDS request handle opened by the adapter is closed and the TDS session is logged out and finalized

#### Scenario: Failed native query closes resources
- **WHEN** a TDS call fails after a request handle has been opened
- **THEN** the adapter closes the opened handle and finalizes the session before returning the error

### Requirement: Trade Date Resolution
The system SHALL use the supplied `tradeDate` when it is present and valid. When `tradeDate` is omitted, the system SHALL call `TdsApi_reqTradeDate` after login and use the returned trade date for snapshot requests.

#### Scenario: Explicit trade date
- **WHEN** a request includes `tradeDate=20260418`
- **THEN** the adapter requests TDS snapshots for trade date `20260418` without first calling `TdsApi_reqTradeDate`

#### Scenario: Default trade date
- **WHEN** a request does not include `tradeDate`
- **THEN** the adapter calls `TdsApi_reqTradeDate` and uses the returned value for all snapshots in that request

### Requirement: Customer Summary Mapping
The system SHALL build the client summary from `TDS_TABLE_ID_CUST_REAL_FUND` records mapped from `TTds_Cust_Real_Fund`. The response summary SHALL include `clientId`, `clientName`, `fundAccountNo`, `currency`, `marginAvailable`, `totalEquity`, `mtmPnl`, `riskRatio1`, and `riskRatio2`.

#### Scenario: Summary maps vendor fund fields
- **WHEN** TDS returns a `TTds_Cust_Real_Fund` row for client `1001`
- **THEN** the response maps `cust_no` to `clientId`, `cust_name` to `clientName`, `fund_account_no` to `fundAccountNo`, `currency_code` to `currency`, `avail_fund` to `marginAvailable`, `dyn_rights` to `totalEquity`, `hold_profit` to `mtmPnl`, `risk_degree1` to `riskRatio1`, and `risk_degree2` to `riskRatio2`

#### Scenario: Multiple fund rows aggregate by client and currency
- **WHEN** TDS returns multiple fund rows for the same client and currency
- **THEN** the system sums `dyn_rights`, `hold_profit`, and `avail_fund`, and uses the maximum `risk_degree1` and `risk_degree2`

### Requirement: Position Mapping
The system SHALL build positions from `TDS_TABLE_ID_CUST_HOLD` records mapped from `TTds_Cust_Hold`. Each response position SHALL include `position`, `currency`, `totalLong`, `totalShort`, `intradayLong`, and `intradayShort`.

#### Scenario: Long position row
- **WHEN** a `TTds_Cust_Hold` row has `bs_flag` equal to `TDS_BUY_DIRECTION`
- **THEN** the system adds `hold_qty` to `totalLong` and `today_hold_qty` to `intradayLong` for that contract

#### Scenario: Short position row
- **WHEN** a `TTds_Cust_Hold` row has `bs_flag` equal to `TDS_SELL_DIRECTION`
- **THEN** the system adds `hold_qty` to `totalShort` and `today_hold_qty` to `intradayShort` for that contract

#### Scenario: Position rows aggregate by contract and currency
- **WHEN** TDS returns multiple hold rows for the same client, currency, and contract code
- **THEN** the system returns one position item with summed long, short, intraday long, and intraday short quantities

### Requirement: Client Filtering
The adapter SHALL subscribe to the requested client before snapshot requests by calling `TdsApi_subscribeDataByCust` with the requested client ID. The adapter SHALL also filter returned records by client ID before returning data to application code.

#### Scenario: Filtered query
- **WHEN** a user queries client `1001`
- **THEN** the adapter subscribes to `1001` and excludes records whose `cust_no` is not `1001`

### Requirement: Vendor Text and Error Handling
The adapter SHALL convert vendor GB18030/GBK text to UTF-8 before returning names, messages, or errors to Java code. The API SHALL return sanitized application errors that do not include TDS passwords, Vault tokens, or raw secret values.

#### Scenario: Vendor customer name is GBK encoded
- **WHEN** TDS returns a GBK encoded customer name
- **THEN** the API response contains a valid UTF-8 `clientName`

#### Scenario: Native call fails
- **WHEN** a vendor TDS API call returns an error
- **THEN** the API response exposes the operation name and decoded error message but does not expose configured secrets

### Requirement: Runtime Configuration
The system SHALL support DEV, QA, and PROD runtime configuration for DRTP endpoints, TDS user, Vault secret location, request timeout, TDS log level, KLG enablement, and function number. The TDS password MUST be read from Vault in native mode and MUST NOT be configured directly in YAML. These values MUST be externalized and MUST NOT be hard-coded into source code.

#### Scenario: Environment config selects DRTP endpoints
- **WHEN** the service starts with the QA environment selected
- **THEN** the adapter uses the QA DRTP endpoint list and does not use DEV or PROD endpoints
- **AND** native mode resolves the TDS password from Vault using the configured KV v2 secret path and key

### Requirement: Native Build and Package Inputs
The build SHALL consume the vendor SDK from a curated package containing `tds/include/tds_api.h`, `tds/linux_x86_64/libtds_api.so`, `tds/linux_x86_64/cpack.dat`, and optional Windows diagnostic files under `tds/win32`. Linux deployment packages SHALL include the native adapter, `libtds_api.so`, and `cpack.dat`.

#### Scenario: Linux package contains native runtime files
- **WHEN** the production package is built
- **THEN** the package contains the Java service, native adapter, vendor `libtds_api.so`, vendor `cpack.dat`, and external configuration templates

#### Scenario: Missing Linux vendor files fail the build
- **WHEN** the Linux build cannot find `tds_api.h`, `libtds_api.so`, or `cpack.dat`
- **THEN** the build fails with an actionable error before packaging

#### Scenario: Windows local debug adapter uses Win32 vendor files
- **WHEN** a developer builds the native adapter on Windows for local debugging
- **THEN** the build uses the Win32/x86 vendor `tds_api.lib`, requires a runtime `.dll` and `cpack.dat` under `tds/win32`, and emits `tds_adapter.exe` with the runtime files copied next to it

#### Scenario: Jenkins prepares SDK from Artifactory
- **WHEN** the Jenkins pipeline starts a native build
- **THEN** it downloads the curated TDS SDK package from the configured Artifactory URL using certificate authentication before validating and building the adapter

### Requirement: Stub Query Mode
The system SHALL provide a deterministic stub implementation for backend tests and local development that does not connect to live DRTP and does not require vendor secrets.

#### Scenario: Stub mode returns deterministic data
- **WHEN** tests configure the TDS client to use stub mode
- **THEN** the query API returns deterministic summary and position data without loading the vendor TDS runtime
