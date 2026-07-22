Review the current repository structure and implement a production-quality Jenkins CI pipeline for validating this AI toolkit repository.

Do not redesign the existing repository unless necessary.

Create or update:

* `Jenkinsfile`
* Scripts under `jenkins/`
* Validation scripts under `scripts/powershell/` and/or `scripts/python/`
* Supporting tests under `tests/`

The Jenkins pipeline must include these stages:

1. Checkout
2. Environment information
3. Repository structure validation
4. Skill validation
5. Profile validation
6. Script quality validation
7. Secret scanning
8. Unit tests
9. Package validation artifacts
10. Publish validation report
11. Optional release validation when building a Git tag

Detailed requirements:

### Skill validation

Validate every skill under:

* `skills/common/*/`
* `skills/teams/<team>/*/`

Each skill must:

* Contain `SKILL.md`
* Have valid YAML frontmatter
* Include non-empty `name` and `description`
* Have a directory name that matches the skill name
* Use lowercase kebab-case naming
* Not contain hardcoded tokens, passwords, Authorization headers, private keys, or credentials
* Not reference unsupported global skill paths
* Not require files outside the workspace
* Not contain broken relative references to scripts, templates, or configuration examples

### Profile validation

Validate all JSON files under `profiles/`.

Each profile must:

* Be valid JSON
* Have a unique profile name
* Reference only existing skills, rules, and MCP components
* Not contain real credentials
* Not contain absolute developer-specific paths
* Support inheritance or inclusion of a common profile in a simple and clearly documented way

### Secret scanning

Implement a repository-local secret scan without printing detected secret values.

At minimum detect suspicious patterns such as:

* Password assignments
* Token assignments
* Bearer tokens
* Bitbucket app passwords
* Private key blocks
* AWS-style keys
* Generic high-entropy credentials in JSON, YAML, ENV, PowerShell, Python, and Markdown files

Allow approved false positives through a documented allowlist file.

Do not require an external SaaS scanner.

### Testing and reports

* Use Python standard library where practical.
* Add unit tests for the validation logic.
* Generate machine-readable JSON and human-readable Markdown reports.
* Archive reports as Jenkins artifacts.
* Publish test results in JUnit XML format.
* Fail the build when mandatory validation fails.
* Ensure secrets are never printed into Jenkins logs.

### Jenkins implementation

* Use a Declarative Pipeline.
* Make stages easy to understand.
* Use `post` blocks for cleanup and artifact publication.
* Do not hardcode Jenkins credentials.
* Do not require Bitbucket credentials for ordinary validation.
* Support both Windows and Linux agents where practical.
* Clearly document any platform-specific limitations.
* Prefer a Python-based validator so Windows and Linux behavior remains consistent.

### Release behavior

When the build is triggered from a tag such as `v1.2.0`:

* Validate that `VERSION` matches the Git tag.
* Validate that `CHANGELOG.md` contains an entry for the version.
* Create a ZIP artifact containing only distributable toolkit files.
* Exclude caches, test output, local config, secrets, and `.git`.

At the end:

1. Show all created or changed files.
2. Explain how to run the validation locally.
3. Explain how Jenkins credentials should be configured, without embedding credential values.
4. Provide examples for both Windows PowerShell and Linux shell execution.
