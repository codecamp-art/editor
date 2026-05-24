## ADDED Requirements

### Requirement: Reporting System Navigation Shell
The page SHALL use the reporting-system layout from the provided reference image, including a top bar, signed-in user display, sidebar navigation, and active `Client TDS Query` navigation item.

#### Scenario: Page shell is visible
- **WHEN** an authenticated user opens the client query page
- **THEN** the page shows the `TDS Reporting System` top bar, signed-in user display, sidebar navigation, and active `Client TDS Query` item

### Requirement: Client Lookup Page
The system SHALL provide a web page where an authenticated Windows domain user can input a client search term, select one client by client ID or name, and submit a client TDS detail query.

#### Scenario: User searches for a client
- **WHEN** an authenticated user enters a client ID or name fragment
- **THEN** the page calls the backend client lookup API and shows matching client candidates

#### Scenario: User selects a client candidate
- **WHEN** an authenticated user selects a candidate by client ID or client name
- **THEN** the page uses the selected candidate's client ID for the detail query

#### Scenario: Empty client search is rejected
- **WHEN** a user attempts to search without a client ID or name term
- **THEN** the page shows a validation error and does not call the backend client lookup API

### Requirement: Snapshot-Shaped Result Display
The page SHALL display the query result with the same data structure as `docs/product/client_data_snapshot.png`: a client summary block, a blank separator row, and a positions block. The visual style MAY differ from the screenshot.

#### Scenario: Summary block is rendered
- **WHEN** query data is returned for a client
- **THEN** the first block shows columns `Client ID`, `Currency`, `Margin Available`, `Total Equity`, and `Risk Ratio (%)`

#### Scenario: Positions block is rendered
- **WHEN** query data includes positions
- **THEN** the positions block shows columns `Positions`, `Total Long`, `Total Short`, `Intraday Long`, and `Intraday Short`

#### Scenario: Separator row is preserved
- **WHEN** the result is displayed
- **THEN** a blank separator row exists between the client summary block and the positions block

### Requirement: Excel-Compatible Copy
The page SHALL provide a copy action that copies the displayed result in an Excel-compatible tabular format.

#### Scenario: Copy result for Excel
- **WHEN** the user activates the copy action after a successful query
- **THEN** the clipboard content preserves the summary row, blank separator row, positions header row, and position rows so pasting into Excel keeps the same row and column structure

#### Scenario: Copy is unavailable before query result
- **WHEN** no successful query result is displayed
- **THEN** the copy action is disabled or unavailable
