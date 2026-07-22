You are working in a newly created empty Git repository named `toolkit`.

Create a maintainable monorepo structure for shared AI development assets used by multiple engineering teams.

The repository will contain:

* Common and team-specific Agent Skills
* MCP server implementations
* Shared AI rules
* Templates
* Team profiles/manifests
* Installation and validation scripts
* Jenkins pipelines
* Documentation and examples

Important constraints:

1. Windsurf can only access files inside the current workspace.
2. Global skills outside the workspace are not supported.
3. Shared skills may be copied into downstream projects under `.windsurf/skills/`.
4. Real tokens, passwords, private keys, and credentials must never be committed.
5. Skills may contain supporting Python scripts and example configuration files.
6. The repository is hosted in Bitbucket.
7. Windows 11 and PowerShell are the primary developer environment.
8. Jenkins runs the CI and update automation.
9. Do not add unnecessary frameworks or dependencies.
10. Prefer simple, maintainable scripts with clear error handling.

Create the following high-level structure:

toolkit/
├── skills/
│   ├── common/
│   └── teams/
│       ├── team-a/
│       └── team-b/
├── mcp/
│   ├── common/
│   └── teams/
├── rules/
│   ├── common/
│   └── teams/
├── templates/
│   ├── skill/
│   ├── mcp/
│   └── config/
├── profiles/
├── scripts/
│   ├── powershell/
│   └── python/
├── jenkins/
├── docs/
├── examples/
├── tests/
├── Jenkinsfile
├── README.md
├── CONTRIBUTING.md
├── CHANGELOG.md
├── VERSION
├── .gitignore
└── .editorconfig

Add realistic starter content, including:

1. A common `pr-review` skill:

   * `skills/common/pr-review/SKILL.md`
   * `skills/common/pr-review/scripts/`
   * `skills/common/pr-review/config.example.json`
   * It must use Bitbucket MCP first when available.
   * Local scripts may be used only as fallback.
   * It must never read tokens from committed files.
   * It must not approve, merge, push, commit, or post comments unless explicitly requested.

2. A common `commit-message` skill.

3. One example team-specific skill under:

   * `skills/teams/team-a/example-domain-review/`

4. A reusable `SKILL.md` template with valid YAML frontmatter.

5. Example MCP directory structure, but do not build a large MCP implementation yet.

6. Example profiles:

   * `profiles/common.json`
   * `profiles/team-a.json`

Each profile should list the skills, rules, and optional MCP components required by that profile.

7. Documentation covering:

   * Repository purpose
   * Directory ownership
   * How common and team-specific assets are separated
   * How to add a new skill
   * How to add a new team
   * How secrets must be handled
   * How downstream projects consume skills
   * Which files are centrally managed and must not be edited in downstream projects

8. A secure `.gitignore` that excludes:

   * Local configuration overrides
   * Tokens and secret files
   * Python caches and virtual environments
   * Node modules
   * Jenkins local output
   * Generated temporary installation directories

Do not leave empty directories. Use `.gitkeep` only where absolutely necessary.

Before creating files:

1. Briefly summarize the proposed design.
2. List any assumptions.
3. Then create the files.
4. At the end, show the final directory tree and explain the purpose of each top-level directory.
