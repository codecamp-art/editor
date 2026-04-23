# 代码说明

这份说明对应当前仓库里的最新实现。

现在程序的原则是：

- 最终发布版本仍然在 RHEL8 上编译、链接 `.so`、运行
- Windows 本地开发既支持 `--stub-file`，也支持在供应商提供 `.lib + .dll` 时做 live 联调
- 旧的 dynamic 实现已经退出主流程，只在 `backup/tds_client_dynamic_backup.cpp` 里保留源码备份

## 当前结构

- `src/tds_client.cpp`
  当前唯一仍在编译的 TDS 客户端实现文件。
- `src/app.cpp`
  负责配置读取、CSV 生成、邮件正文、MIME 拼装、`curl` 配置生成和发信。
- `src/main.cpp`
  串起命令行解析、配置加载、TDS 取数、报表输出和邮件发送。
- `backup/tds_client_dynamic_backup.cpp`
  旧 dynamic 方案的源码备份，不参与编译。

## `src/tds_client.cpp`

这个文件里同时保留了两类客户端对象：

- `VendorTdsClient`
  真实调用供应商 API。
- `StubTdsClient`
  从本地 CSV 读模拟数据。

### 1. 什么时候会包含供应商头文件

文件开头有：

- `#ifdef REPORT_HAS_VENDOR_API`
- `#include "tds_api.h"`

这表示只有在 CMake 已经确认“当前平台有可用的供应商库”时，才会编译 live 代码。

当前 CMake 的判断规则是：

- Linux/RHEL8：必须显式传 `-DTDS_VENDOR_LIBRARY=/path/to/tds_api.so`
- Windows：如果 `./tds/win32/` 下有 `.lib`，或者显式传了 `-DTDS_VENDOR_LIBRARY=...`，就启用 live 编译

也就是说，当前已经不是“Windows 永远 stub-only”，而是：

- 没有 Windows 供应商库时，Windows 走 stub
- 有 Windows `.lib + .dll` 时，Windows 也能走 live

### 2. `VendorTdsClient`

这个类是真正的供应商直连实现。

它在构造函数里调用 `InitializeSession()`，顺序和供应商文档一致：

1. `TdsApi_init`
2. `TdsApi_addDrtpNode`
3. `TdsApi_reqLogin`

对应意义：

- `TdsApi_init`
  初始化 API 运行环境。
- `TdsApi_addDrtpNode`
  注册当前环境的 DRTP 地址和端口。
- `TdsApi_reqLogin`
  用用户名密码登录。

### 3. `FetchTradeDate`

这个函数直接调用：

- `TdsApi_reqTradeDate`

拿当前交易日。

如果供应商接口返回非 0，就抛出异常，把错误码和错误信息带出来。

### 4. `FetchCustomerFunds`

这个函数负责拿客户资金快照。

关键流程是：

1. 如果命令行传了客户过滤列表，就先调 `TdsApi_subscribeDataByCust`
2. 调 `TdsApi_reqSnapshot(..., TDS_TABLE_ID_CUST_REAL_FUND, ...)`
3. 用 `TdsApi_hasNext` / `TdsApi_getNext` 循环取记录
4. 把 `TTds_Cust_Real_Fund` 映射成程序内部的 `CustomerFundRecord`

映射出的字段就是你要求的这些：

- `cust_no`
- `cust_name`
- `dyn_rights`
- `hold_profit`
- `avail_fund`
- `risk_degree1`
- `risk_degree2`

另外还保留了：

- `fund_account_no`
- `currency_code`

这样导出的 CSV 更容易排查重复行或币种差异。

### 5. `StubTdsClient`

这个类从本地 CSV 文件读取模拟数据。

用途主要有两个：

- Windows 本地不接真实 TDS 时调试主流程
- 单元测试

也就是说，哪怕现在 Windows 能支持 `.lib + .dll` live 联调，stub 仍然有价值，因为它更快、更稳定，也不依赖外部网络和真实账号。

### 6. `CreateClient`

这个工厂函数决定程序最终用哪个客户端：

1. 如果传了 `--stub-file`，一定创建 `StubTdsClient`
2. 否则如果当前构建带了 `REPORT_HAS_VENDOR_API`，创建 `VendorTdsClient`
3. 否则抛错，提示当前平台没有可用的 live 供应商库

这个设计的好处是：

- 同一套主流程代码不用关心底层是 live 还是 stub
- Windows 本地和 RHEL8 生产只是在“客户端实现”上不同

## `src/app.cpp`

这个文件主要负责“程序业务层”。

### 1. 配置读取

`LoadConfig` 会读取 `*.properties`，并支持：

- `${ENV_VAR:default}`

这种环境变量占位写法。

它会把配置分别装进：

- `TdsConnectionConfig`
- `SmtpConfig`
- `AppConfig`

其中 SMTP 相关已经按你给的 JavaMailSender 配置做了兼容，支持：

- `smtp.port=2587`
- `smtp.security=starttls`
- `smtp.client_cert_path`
- `smtp.client_key_path`
- `smtp.client_cert_type`
- `smtp.client_key_type`
- `smtp.client_key_password`

### 2. CSV 生成

`WriteCsvReport` 会把客户资金快照导出成 CSV 文件。

### 3. 邮件正文

`BuildHtmlBody` 会生成 HTML 邮件正文。

当前版本已经按你的要求修改为：

- 邮件正文显示全部客户
- 不再只截取前 10 个客户

### 4. SMTP 配置生成

`BuildCurlConfig` 会给 `curl --config` 生成配置文件。

针对你们公司的邮件网关，它现在会输出：

- `url = "smtp://host:2587"`
- `ssl-reqd`
- `cert = "..."`
- `key = "..."`
- `cert-type = "PEM"`
- `key-type = "PEM"`

如果私钥有口令，还会写：

- `pass = "..."`

### 5. 发信和 dry-run

`SendMailWithCurl` 的行为分两种：

- `dry_run=true`
  只生成 `.eml` 预览文件，不真正连 SMTP
- `dry_run=false`
  先生成 MIME 邮件，再调用 `curl` 真正发信

## `CMakeLists.txt`

当前 CMake 逻辑已经变成：

- RHEL8 发布构建：链接供应商 `.so`
- Windows 本地开发构建：如果有 `.lib + .dll`，也能链接供应商 Windows 库

### 1. Linux / RHEL8

Linux 下必须传：

- `-DTDS_VENDOR_LIBRARY=/path/to/tds_api.so`

否则直接配置失败。

### 2. Windows

Windows 下有两种方式启用 live：

1. 把供应商文件放到 `tds/win32/`
2. 显式传：
   - `-DTDS_VENDOR_LIBRARY=/path/to/tds_api.lib`
   - `-DTDS_VENDOR_RUNTIME=/path/to/tds_api.dll`

CMake 现在会自动做这些事：

- 找到 `.lib` 后启用 `REPORT_HAS_VENDOR_API`
- 如果给了 `.dll`，在构建后把它复制到 `report.exe` 和 `report_tests.exe` 旁边
- `cmake --install` 时也把 `.dll` 安装到 `bin/`

如果只给了 `.lib` 没给 `.dll`，CMake 会给出警告，因为这种情况下虽然能链接，但本地运行仍然会缺运行时库。

### 3. 阶段产物目录

`report_stage` 会生成一个可部署目录。

RHEL8 阶段目录大致是：

- `bin/report`
- `lib/tds_api.so`
- `config/*.properties`

Windows 本地 live 阶段目录大致是：

- `bin/report.exe`
- `bin/tds_api.dll`
- `config/*.properties`

注意这里的意义不同：

- Windows 阶段目录主要是方便本地联调
- 真正要交付和运行的正式版本仍然是 RHEL8 阶段目录

## `tests/report_tests.cpp`

当前单元测试仍以 stub 流程为主。

这很正常，因为单元测试重点是验证我们自己写的业务逻辑，而不是依赖真实交易网络。

已经覆盖的重点包括：

- 命令行解析
- 配置文件加载
- stub CSV 数据读取
- CSV 输出
- 邮件 MIME 生成
- dry-run 输出
- SMTP 客户端证书参数生成
- 正文包含全部客户

## Jenkins On RHEL8

最终正式版本的推荐流程没有变：

1. 在 RHEL8 Jenkins node 上编译
2. 链接供应商 `tds_api.so`
3. 跑单元测试
4. 生成 stage 目录
5. 把 stage 目录作为制品发布到独立 RHEL8 机器运行

也就是说，Windows `.lib + .dll` 的作用主要是：

- 帮助本地开发
- 帮助本地断点调试 live 接口
- 更早发现结构体映射、调用顺序、字段取值问题

但它不改变最终生产链路：

- 最终编译仍然在 RHEL8
- 最终链接仍然是 `.so`
- 最终运行仍然在独立 RHEL8

## `CMakePresets.json` 和 Jenkins 文件

为了降低日常使用门槛，仓库里现在还新增了这些辅助文件：

- `CMakePresets.json`
  预定义了：
  - `windows-stub-x64`
  - `windows-live-x64`
  - `windows-live-x86`
  - `linux-rhel8-release`
- `jenkins/Jenkinsfile.pr`
  用于 PR 校验，重点是编译、单测和稳定的 stub smoke test。
- `jenkins/Jenkinsfile.release`
  用于发布构建，重点是编译、单测、stage 目录打包，以及可选的 live dry-run smoke test。

这些文件不改变程序逻辑，只是把“如何构建、如何验证、如何打包”固化成了可重复执行的工程入口。

## 结论

当前仓库现在是这样的分工：

- 生产标准路径：RHEL8 + `.so`
- 本地快速开发：Windows + `--stub-file`
- 本地真实联调：Windows + `.lib + .dll`

这三条路径共用同一套业务代码，只在最底层的供应商接入方式和运行平台上不同。
