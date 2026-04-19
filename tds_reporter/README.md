# TDS Reporter

`tds_reporter` is a standalone C++17 program that reads the vendor TDS snapshot, exports a CSV report, and sends the report through SMTP by calling `curl`.

## What It Collects

The implementation reads the `TTds_Cust_Real_Fund` snapshot and outputs:

- `cust_no`
- `cust_name`
- `dyn_rights`
- `hold_profit`
- `avail_fund`
- `risk_degree1`
- `risk_degree2`

It also keeps `fund_account_no` and `currency_code` in the CSV so duplicate customer rows remain unambiguous.

## TDS Integration

This project now keeps only one supported live integration path:

- direct link: `src/tds_client.cpp` includes `tds_api.h` and links to the vendor platform library at build time
  - RHEL8/Jenkins/release: link the vendor `tds_api.so`
  - Windows local development: link the vendor `.lib` and run with the matching `.dll`

The old dynamic-loading implementation is still preserved as a source backup only:

- `backup/tds_client_dynamic_backup.cpp`

It is not compiled, not linked, and not documented as a supported runtime mode anymore. If you ever need to revive it, you must also restore the old `tds.library_path` config field by hand.

Why direct link is preferred when the supplier can reliably provide `.h` and the platform library (`.so` on Linux, `.lib + .dll` on Windows):

- the compiler can check API signatures during build
- you get link-time errors earlier instead of runtime symbol lookup failures
- the code is simpler than manual `dlsym` handling

## Simple Diagram

```mermaid
flowchart LR
    H[".h header<br/>function declarations"] --> D["Direct link<br/>compile + link stage"]
    S[".so shared library<br/>real implementation"] --> D
    H --> L["Dynamic load<br/>runtime lookup"]
    S --> L
```

Short version:

- `.h`: tells the compiler what functions and structs exist
- `.so`: contains the real implementation
- direct link: build time connects your program to the `.so`
- dynamic load: program starts first and looks up symbols later

## Configuration

The default config file path is selected by `--env`:

- `--env dev` -> `tds_reporter/config/dev.properties`
- `--env qa` -> `tds_reporter/config/qa.properties`
- `--env prod` -> provide `tds_reporter/config/prod.properties`

`prod.properties.template` is included as a template. Copy it to `prod.properties` and fill the real values.

Properties support `${ENV_VAR:default}` syntax, so passwords can stay in environment variables instead of config files. Relative paths inside the config are resolved from the config file directory, not from the current shell directory.

Important keys:

- `tds.drtp_host` and `tds.drtp_port`: DRTP endpoint for each environment
- `tds.user` and `tds.password`: TDS login
- `smtp.*`: SMTP connection settings used by `curl`
- `email.default_to` and `email.default_cc`: default recipients
- `report.output_dir`: where CSV and dry-run `.eml` previews are written

The SMTP transport settings are aligned with the company JavaMailSender example:

- `smtp.port=2587`
- `smtp.security=starttls`
- `smtp.client_cert_path=/app/.cert/server.pem`
- `smtp.client_key_path=/app/.cert/server.key`

The three environment config files keep different TDS endpoints, but use the same mail transport block. The default host is intentionally left as `mta-hub.REPLACE_ME.com.cn` because the screenshot only showed the prefix and suffix. Replace it in the config file or set `SMTP_HOST`.

## Build On RHEL8

Install the build tools first:

```bash
sudo dnf install -y gcc-c++ make cmake curl
```

Build:

```bash
cd /path/to/repo/tds_reporter
cmake -S . -B build \
  -DCMAKE_BUILD_TYPE=Release \
  -DTDS_VENDOR_LIBRARY=/opt/tds/lib/tds_api.so
cmake --build build --config Release
ctest --test-dir build --output-on-failure
```

In this mode:

- `src/tds_client.cpp` includes the vendor `tds_api.h`
- the linker links against `tds_api.so`
- the build-tree executable uses the vendor library directory as `BUILD_RPATH`

## CMake Presets

The project now includes [CMakePresets.json](/D:/Codes/local/Test/tds_reporter/CMakePresets.json) so local and CI builds do not need to remember long command lines.

Available presets:

- `windows-stub-x64`: local x64 Windows build without vendor binaries
- `windows-live-x64`: local x64 Windows build that expects the supplier `win32` package to be installed
- `windows-live-x86`: local x86 Windows build for a real 32-bit supplier `win32` package
- `linux-rhel8-release`: RHEL8 release build linked against `/opt/tds/lib/tds_api.so`

Typical local Windows stub flow:

```powershell
cmake --preset windows-stub-x64
cmake --build --preset windows-stub-x64-debug
ctest --preset windows-stub-x64-debug
```

Typical local Windows live flow after the supplier installs `tds\win32\*.lib` and `*.dll`:

```powershell
cmake --preset windows-live-x64
cmake --build --preset windows-live-x64-debug
ctest --preset windows-live-x64-debug
```

Check the configure output once:

- if you see `Windows build will enable live TDS calls`, local live mode is active
- if you still see `Windows build is stub-only`, the supplier `win32` package was not detected and you should either install it under `../tds/win32/` or pass explicit library paths

If the supplier package is truly 32-bit, use the x86 preset instead:

```powershell
cmake --preset windows-live-x86
cmake --build --preset windows-live-x86-debug
ctest --preset windows-live-x86-debug
```

## Stage A Release Directory

The project now includes standard install rules plus a helper target that produces a self-contained stage directory.

To create the stage directory:

```bash
cd /path/to/repo/tds_reporter
cmake --build build --target tds_reporter_stage --config Release
```

Or with plain CMake install:

```bash
cmake --install build --prefix /tmp/tds_reporter_release
```

The staged layout on RHEL8 is:

```text
stage/
  bin/
    tds_reporter
  lib/
    tds_api.so
  config/
    dev.properties
    qa.properties
    prod.properties.template
  README.md
```

The installed binary uses `INSTALL_RPATH=$ORIGIN/../lib`, so after staging it will first look for the supplier shared library in the sibling `lib/` directory.

If you also do a local Windows live build, the staged layout becomes:

```text
stage/
  bin/
    tds_reporter.exe
    tds_api.dll
  config/
    dev.properties
    qa.properties
    prod.properties.template
  README.md
```

On Windows the supplier runtime library is staged next to `tds_reporter.exe`, because Windows looks for the matching `.dll` beside the executable or on `PATH`.

Runtime prerequisites on RHEL8:

- `curl`
- `/app/.cert/server.pem`
- `/app/.cert/server.key`
- network access to the DRTP endpoint and the SMTP server

## Jenkins On RHEL8

If the build runs on a RHEL8 Jenkins node, the simplest approach is:

1. Install build tools on the node once: `gcc-c++`, `cmake`, `make`, `curl`
2. Make sure the supplier header directory is present in the workspace at `../tds/include`
3. Make sure the supplier library exists on the node, for example `/opt/tds/lib/tds_api.so`
4. In the pipeline, pass `-DTDS_VENDOR_LIBRARY=/opt/tds/lib/tds_api.so`
5. Run unit tests on the node with `ctest`
6. Stage a release directory with `cmake --build build --target tds_reporter_stage`
7. Archive the stage directory as the Jenkins build artifact
8. Use `--stub-file` only for non-Linux local debugging, not for the Jenkins integration build

Example declarative pipeline stage:

```groovy
pipeline {
  agent { label 'rhel8' }
  stages {
    stage('Build And Test') {
      steps {
        sh '''
          set -euxo pipefail
          cd tds_reporter
          cmake -S . -B build \
            -DCMAKE_BUILD_TYPE=Release \
            -DTDS_VENDOR_LIBRARY=/opt/tds/lib/tds_api.so
          cmake --build build --config Release --parallel
          ctest --test-dir build --output-on-failure
          cmake --build build --target tds_reporter_stage --config Release
        '''
      }
    }
  }
}
```

Recommended artifact to archive from Jenkins:

- `tds_reporter/build/stage/bin/tds_reporter`
- `tds_reporter/build/stage/lib/tds_api.so`
- `tds_reporter/build/stage/config/*`

If you also want a smoke test job on Jenkins, use a real QA config:

```bash
./build/stage/bin/tds_reporter \
  --env qa \
  --config ./build/stage/config/qa.properties \
  --dry-run \
  --to qa-ops@example.com
```

This verifies config loading, direct-linked live code path creation, CSV generation, and email MIME generation without actually sending mail.

The repository now also includes ready-to-use Jenkins pipeline files:

- [jenkins/Jenkinsfile.pr](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.pr)
- [jenkins/Jenkinsfile.release](/D:/Codes/local/Test/tds_reporter/jenkins/Jenkinsfile.release)

Recommended Jenkins pipeline script paths:

- PR validation job: `tds_reporter/jenkins/Jenkinsfile.pr`
- Release packaging job: `tds_reporter/jenkins/Jenkinsfile.release`

Pipeline intent:

- `Jenkinsfile.pr`: configure, build, unit test, then run a deterministic `--stub-file --dry-run` smoke test
- `Jenkinsfile.release`: configure, build, unit test, stage the release directory, package it as `tar.gz`, and optionally run a live DRTP dry-run smoke test

## Run On RHEL8

Example using the QA config and overriding recipients from Airflow:

```bash
./build/stage/bin/tds_reporter \
  --env qa \
  --config ./build/stage/config/qa.properties \
  --to qa-ops@example.com,risk@example.com \
  --cc qa-support@example.com
```

Example sending only selected customers:

```bash
./build/stage/bin/tds_reporter \
  --env prod \
  --config /opt/tds-reporter/config/prod.properties \
  --cust-list 1001,1002,1003
```

Example dry run:

```bash
./build/stage/bin/tds_reporter \
  --env dev \
  --config ./build/stage/config/dev.properties \
  --dry-run
```

If the private key is encrypted, also set:

```bash
export SMTP_CLIENT_KEY_PASSWORD=your_key_passphrase
```

## Debug On Windows

### 1. Recommended local workflow

Windows local development now supports two modes:

- stub mode: works everywhere and is still the fastest way to debug CSV generation, MIME generation, and CLI parsing
- local live mode: works when the supplier also gives you a Windows `.lib + .dll`

If you only want fast local iteration, stub mode is enough:

```powershell
tds_reporter.exe --env dev --stub-file tests\data\stub_snapshot.csv --dry-run
```

This lets you debug:

- command-line parsing
- config loading
- CSV generation
- MIME email content
- `curl` config generation

In dry-run mode the mail server is not contacted, so the PEM certificate and key files do not need to exist on your Windows machine.

If the supplier provides a Windows runtime package, place it under `../tds/win32/`:

- `../tds/win32/tds_api.lib`
- `../tds/win32/tds_api.dll`

The CMake project will auto-detect that directory for local Windows live builds. You can also pass the paths explicitly with `-DTDS_VENDOR_LIBRARY=...` and `-DTDS_VENDOR_RUNTIME=...`.

### 2. VS Code plugins

Install these plugins:

- `C/C++` by Microsoft
- `CMake Tools` by Microsoft
- `Remote - SSH` by Microsoft if you want to build or debug directly on the RHEL8 host from Windows

### 3. Visual Studio 2022 workload

Install the `Desktop development with C++` workload.

### 4. Build from a VS 2022 developer shell

If `cmake` is not on `PATH`, use the Visual Studio bundled one. In PowerShell, do not write `cmd /c "\"...\""`. Use this verified form instead:

```powershell
$cmd = 'call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" && "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" -S D:\Codes\local\Test\tds_reporter -B D:\Codes\local\Test\tds_reporter\build'
cmd /c $cmd

$cmd = 'call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" && "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" --build D:\Codes\local\Test\tds_reporter\build --config Debug'
cmd /c $cmd
```

That default command is still fine for stub-only development.

If the supplier provides a Windows `.lib + .dll`, you have two choices:

1. Put them under `D:\Codes\local\Test\tds\win32\`
2. Pass the paths explicitly:

```powershell
$cmd = 'call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" && "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" -S D:\Codes\local\Test\tds_reporter -B D:\Codes\local\Test\tds_reporter\build_live -DTDS_VENDOR_LIBRARY=D:\Codes\local\Test\tds\win32\tds_api.lib -DTDS_VENDOR_RUNTIME=D:\Codes\local\Test\tds\win32\tds_api.dll'
cmd /c $cmd

$cmd = 'call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" && "C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" --build D:\Codes\local\Test\tds_reporter\build_live --config Debug'
cmd /c $cmd
```

When `TDS_VENDOR_RUNTIME` is set, the build copies the `.dll` next to `tds_reporter.exe` and `tds_reporter_tests.exe`, so local execution does not depend on a global `PATH` change.

If you prefer not to type the full configure and build commands, use the presets from [CMakePresets.json](/D:/Codes/local/Test/tds_reporter/CMakePresets.json) instead.

Important architecture note:

- if the supplier `win32` directory really contains 32-bit binaries, you must use an x86 toolchain for the local Windows build
- the default `vcvars64.bat` flow builds x64, so a true 32-bit `.lib` will not link there
- in that case, use an x86 developer prompt or `vcvars32.bat`, and keep that build in a separate directory such as `build_win32`

The folder also includes `.vscode/extensions.json`, `.vscode/settings.json`, and `.vscode/launch.json`. If you open `tds_reporter/` directly in VS Code, install the recommended extensions, let CMake configure the project, and then press `F5`, the default launch profile will start the program in stub dry-run mode.

### 5. Live debugging strategy

If the supplier gives you a Windows `.lib + .dll`, you can debug the real TDS call flow on Windows too. That is useful for stepping through login, snapshot retrieval, and field mapping in Visual Studio or VS Code.

Even so, the final release path is still RHEL8:

- Jenkins should still build the release artifact on a RHEL8 node and link the vendor `tds_api.so`
- the final smoke test should still run on RHEL8

Practical debugging options:

1. Use stub mode on Windows for fast iteration when you are changing report or mail logic.
2. Use local Windows live mode when the supplier also provides `.lib + .dll` and you want to debug the vendor call flow with breakpoints.
3. Use VS Code `Remote - SSH` into the RHEL8 host and run `cmake`, `gdb`, and the executable there for final Linux verification.

## Airflow Example

```bash
/opt/tds-reporter/bin/tds_reporter \
  --env prod \
  --to trading-ops@example.com,risk@example.com \
  --cc support@example.com
```

That satisfies the "same program, different recipients" requirement without changing the config file.
