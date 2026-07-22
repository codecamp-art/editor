Implement a controlled Jenkins automation workflow that distributes selected common and team-specific skills from this `toolkit` repository to downstream Bitbucket project repositories.

The solution must support two distribution modes:

### Mode 1: Local workspace installation

This mode installs skills into a developer's current project workspace without committing them to Bitbucket.

Expected target:

`.windsurf/skills/<skill-name>/`

Requirements:

* Read a downstream project manifest named `.ai-toolkit.json`.
* Copy only the selected skills.
* Use real file copies, not symlinks.
* Generated common and team-managed skills must be suitable for `.gitignore`.
* Project-specific skills already present in the target repository must not be deleted or overwritten.
* Record installation metadata in a generated local file such as:
  `.windsurf/skills/.toolkit-installation.json`
* Include source repository, source version, profile, installed skills, and installation timestamp.
* Do not write credentials into the workspace.

### Mode 2: Managed Bitbucket pull request

This mode updates managed skills in downstream repositories and creates a Bitbucket pull request.

The downstream project manifest should look similar to:

```json
{
  "toolkitSource": "ssh://git@bitbucket.example.com/ABC-CD-AI-DEV/toolkit.git",
  "version": "v1.0.0",
  "profile": "team-a",
  "skills": [
    "common/pr-review",
    "common/commit-message",
    "teams/team-a/example-domain-review"
  ],
  "target": ".windsurf/skills",
  "distributionMode": "pull-request"
}
```

Requirements:

1. Clone the downstream repository into a temporary Jenkins workspace.
2. Read and validate `.ai-toolkit.json`.
3. Fetch the requested toolkit version or Git tag.
4. Resolve the profile and explicitly selected skills.
5. Copy managed skills to `.windsurf/skills/`.
6. Preserve project-owned skills.
7. Remove only obsolete skills previously marked as centrally managed.
8. Generate or update:
   `.windsurf/skills/.managed-by-toolkit.json`
9. The metadata file must contain:

   * Toolkit source
   * Toolkit version
   * Selected profile
   * Managed skill names
   * Content checksums
10. Detect whether any files actually changed.
11. If nothing changed, exit successfully without creating a branch or PR.
12. If changes exist:

* Create a branch such as `chore/update-ai-toolkit-v1.2.0`
* Commit with a message such as:
  `chore(ai): update managed skills to v1.2.0`
* Push the branch
* Create a Bitbucket pull request

13. Never push directly to the default branch.
14. Never automatically merge the PR.
15. Never approve the PR.
16. Never include tokens in command-line arguments, files, logs, commit messages, or PR descriptions.
17. Use Jenkins Credentials Binding for Bitbucket credentials.
18. Mask credentials in logs.
19. Clean the temporary workspace in all cases.

Support a dry-run mode that:

* Shows which skills would be added, updated, or removed
* Shows which repositories would receive a PR
* Does not commit, push, or call the Bitbucket PR creation API

Implementation requirements:

* Use PowerShell for Windows compatibility.
* Python may be used for manifest parsing, checksum generation, and validation.
* Do not depend on `npx skills add` unless it provides a clear benefit.
* If using `npx skills add`, explain why and ensure:

  * project scope only
  * copy mode only
  * telemetry disabled
  * no global installation
* Prefer a native repository copy/validation implementation when it is simpler and more auditable.
* Use Bitbucket REST API for PR creation.
* Support Bitbucket Data Center/Server through configurable base URL, project key, and repository slug.
* Do not assume Bitbucket Cloud-specific APIs.
* Put all non-secret settings in configuration files.
* Inject only credentials from Jenkins.

Create:

* A dedicated Jenkinsfile, such as `jenkins/Jenkinsfile.distribute`
* PowerShell scripts for orchestration
* Python helper scripts where useful
* JSON schema or validation logic for `.ai-toolkit.json`
* Example downstream manifests
* Unit tests
* README documentation
* A safe dry-run example

Before implementation:

1. Inspect the existing repository.
2. Describe the proposed workflow.
3. Identify security-sensitive operations.
4. State assumptions about Bitbucket Data Center API endpoints.
5. Keep API-specific logic isolated so endpoints can be adjusted later.

At the end:

1. Show the generated file tree.
2. Explain required Jenkins credentials.
3. Explain how to configure a list of downstream repositories.
4. Show dry-run and real-run examples.
5. Explain rollback behavior.
6. Explain how managed skills are distinguished from project-owned skills.
