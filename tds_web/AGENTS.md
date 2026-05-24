# AI Agent Rules

## Project

This project is `tds-client-query-web`.

It provides a web UI for users to query client-related TDS data using a backend service that integrates with vendor-provided TDS native libraries.

## Technology Direction

- Backend: Java 21 + Spring Boot + Gradle.
- Native integration: C/C++ vendor headers and Linux `.so` libraries.
- Prefer a small native adapter layer instead of exposing vendor API directly to application code.
- Frontend: React or simple server-rendered UI, depending on project decision.
- Authentication: Kerberos/SPNEGO SSO for Windows domain users opening the web page.
- Deployment target: Linux server.
- Configuration must support DEV/QA/PROD.

## Engineering Rules

- Use OpenSpec workflow for meaningful changes.
- Proposal/review steps must not write application code.
- Apply steps must implement only the approved OpenSpec change.
- No secrets in source code.
- Externalize configuration.
- Add tests for backend business logic and API behavior.
- Keep architecture simple.
- Do not introduce unnecessary frameworks.
- Do not refactor unrelated code.
- Update OpenSpec tasks when completed.

## OpenSpec Rules

When revising an existing OpenSpec change:

- Do not create a new change unless explicitly requested.
- Update only the named change under `openspec/changes/<change-id>/`.
- During proposal/review/fix steps, update only:
  - `proposal.md`
  - `design.md`
  - `tasks.md`
  - spec delta files under `specs/`
- Do not write application code during proposal/review/fix steps.

When applying a change:

- Read proposal, design, tasks, and spec deltas first.
- Implement only the requested phase or task scope.
- Add or update tests.
- Run relevant tests.
- Stop after summarizing changed files and test results.