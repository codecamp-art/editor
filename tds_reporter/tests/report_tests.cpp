#include "app.h"
#include "logging.h"
#include "text_utils.h"

#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {

void AssertTrue(bool condition, const std::string& message)
{
    if (!condition)
    {
        throw std::runtime_error(message);
    }
}

void AssertContains(const std::string& haystack, const std::string& needle, const std::string& message)
{
    if (haystack.find(needle) == std::string::npos)
    {
        throw std::runtime_error(message + " missing [" + needle + "]");
    }
}

void SetEnv(const std::string& key, const std::string& value)
{
#ifdef _WIN32
    _putenv_s(key.c_str(), value.c_str());
#else
    setenv(key.c_str(), value.c_str(), 1);
#endif
}

class ScopedCurrentPath
{
public:
    explicit ScopedCurrentPath(const std::filesystem::path& path)
        : previous_path_(std::filesystem::current_path())
    {
        std::filesystem::current_path(path);
    }

    ~ScopedCurrentPath()
    {
        std::filesystem::current_path(previous_path_);
    }

private:
    std::filesystem::path previous_path_;
};

report::AppConfig BuildTestConfig(const std::filesystem::path& output_dir)
{
    report::AppConfig config;
    config.env_name = "test";
    config.email_subject = "Test Client Funding and Risk Ratio Report";
    config.attachment_name = "fund_snapshot";
    config.output_dir = output_dir.string();
    config.default_to = {"ops@example.com"};
    config.default_cc = {"cc@example.com"};
    config.smtp.host = "localhost";
    config.smtp.port = 1025;
    config.smtp.from = "sender@example.com";
    config.smtp.security = "plain";
    config.smtp.insecure = true;
    config.log.directory = (output_dir / "logs").string();
    config.log.level = "debug";
    return config;
}

std::string ReadFile(const std::filesystem::path& path)
{
    std::ifstream input(path, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
}

void WriteFile(const std::filesystem::path& path, const std::string& content)
{
    std::ofstream output(path, std::ios::binary);
    output << content;
}

std::filesystem::path CreateFakeCurlExecutable(const std::filesystem::path& temp_dir)
{
#ifdef _WIN32
    const std::filesystem::path script_path = temp_dir / "fake-curl.cmd";
    WriteFile(
        script_path,
        "@echo off\r\n"
        "set args=%*\r\n"
        "echo %args% | findstr /C:\"/v1/auth/kerberos/login\" >nul\r\n"
        "if %errorlevel%==0 (\r\n"
        "  echo %args% | findstr /C:\"--negotiate\" >nul\r\n"
        "  if not %errorlevel%==0 (\r\n"
        "    echo missing curl kerberos negotiate option: %args% 1>&2\r\n"
        "    exit /b 1\r\n"
        "  )\r\n"
        "  echo {\"auth\":{\"client_token\":\"fake-vault-token\"}}\r\n"
        "  exit /b 0\r\n"
        ")\r\n"
        "echo %args% | findstr /C:\"/v1/secret/data/tds/qa\" >nul\r\n"
        "if %errorlevel%==0 (\r\n"
        "  echo {\"data\":{\"data\":{\"password\":\"vault-secret-value\"}}}\r\n"
        "  exit /b 0\r\n"
        ")\r\n"
        "echo unexpected curl command: %args% 1>&2\r\n"
        "exit /b 1\r\n");
    return script_path;
#else
    const std::filesystem::path script_path = temp_dir / "fake-curl.sh";
    WriteFile(
        script_path,
        "#!/usr/bin/env bash\n"
        "set -euo pipefail\n"
        "if printf '%s\\n' \"$*\" | grep -q '/v1/auth/kerberos/login'; then\n"
        "  printf '%s\\n' \"$*\" | grep -q -- '--negotiate'\n"
        "  printf '{\"auth\":{\"client_token\":\"fake-vault-token\"}}\\n'\n"
        "  exit 0\n"
        "fi\n"
        "if printf '%s\\n' \"$*\" | grep -q '/v1/secret/data/tds/qa'; then\n"
        "  printf '{\"data\":{\"data\":{\"password\":\"vault-secret-value\"}}}\\n'\n"
        "  exit 0\n"
        "fi\n"
        "printf 'unexpected curl command: %s\\n' \"$*\" >&2\n"
        "exit 1\n");
    std::filesystem::permissions(
        script_path,
        std::filesystem::perms::owner_exec |
            std::filesystem::perms::owner_read |
            std::filesystem::perms::owner_write,
        std::filesystem::perm_options::add);
    return script_path;
#endif
}

void TestParseCli()
{
    const char* argv[] = {
        "report",
        "--env=qa",
        "--to",
        "qa1@example.com,qa2@example.com",
        "--cc=cc@example.com",
        "--cust-list",
        "1001,1002",
        "--dry-run",
        "--trade-date",
        "20260419"
    };

    const report::CliOptions options =
        report::ParseCli(static_cast<int>(std::size(argv)), const_cast<char**>(argv));

    AssertTrue(options.env == "qa", "expected env=qa");
    AssertTrue(options.to.size() == 2, "expected two To recipients");
    AssertTrue(options.cc.size() == 1, "expected one Cc recipient");
    AssertTrue(options.cust_filters.size() == 2, "expected two customer filters");
    AssertTrue(options.dry_run, "expected dry_run=true");
    AssertTrue(options.trade_date_override == 20260419, "expected trade date override");
}

void TestLoadConfig()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_config_test";
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path config_path = temp_dir / "test.properties";

    SetEnv("TDS_TEST_PASSWORD", "super-secret");
    std::ofstream output(config_path);
    output
        << "env.name=dev\n"
        << "tds.drtp_host=10.0.0.1\n"
        << "tds.drtp_port=6003\n"
        << "tds.user=10000\n"
        << "tds.password=${TDS_TEST_PASSWORD:}\n"
        << "smtp.host=mail.local\n"
        << "smtp.port=2587\n"
        << "smtp.from=sender@example.com\n"
        << "smtp.security=starttls\n"
        << "smtp.client_cert_path=/app/.cert/server.pem\n"
        << "smtp.client_key_path=/app/.cert/server.key\n"
        << "log.dir=../logs\n"
        << "log.level=warn\n";
    output.close();

    report::CliOptions cli;
    cli.env = "prod";
    cli.output_dir = "custom-output";
    const report::AppConfig config = report::LoadConfig(config_path.string(), cli);

    AssertTrue(config.env_name == "prod", "cli env should override file env");
    AssertTrue(config.output_dir == "custom-output", "cli output dir should override file output dir");
    AssertTrue(config.tds.password == "super-secret", "env reference should be resolved");
    AssertTrue(config.smtp.port == 2587, "smtp port should be parsed");
    AssertTrue(config.smtp.security == "starttls", "smtp security should be parsed");
    AssertTrue(config.smtp.client_cert_path == "/app/.cert/server.pem", "smtp client cert should be parsed");
    AssertTrue(config.smtp.client_key_path == "/app/.cert/server.key", "smtp client key should be parsed");
    AssertTrue(
        std::filesystem::path(config.log.directory).filename() == "logs",
        "log directory should be resolved relative to the config");
    AssertTrue(config.log.level == "warn", "log level should be parsed");
}

void TestLoadConfigUsesFileEnvWhenCliEnvMissing()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_config_file_env_test";
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path config_path = temp_dir / "test.properties";

    std::ofstream output(config_path);
    output
        << "env.name=prod\n"
        << "tds.drtp_host=10.0.0.1\n"
        << "tds.drtp_port=6003\n"
        << "tds.user=10000\n"
        << "tds.password=secret\n"
        << "smtp.host=mail.local\n"
        << "smtp.port=2587\n"
        << "smtp.from=sender@example.com\n";
    output.close();

    report::CliOptions cli;
    const report::AppConfig config = report::LoadConfig(config_path.string(), cli);

    AssertTrue(config.env_name == "prod", "config env should be used when --env is omitted");
}

void TestLoadConfigMergesSharedTemplateAndEnvOverlay()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_config_overlay_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path template_path = temp_dir / "report.properties.template";
    const std::filesystem::path config_path = temp_dir / "qa.properties";

    WriteFile(
        template_path,
        "tds.drtp_port=6003\n"
        "tds.user=10000\n"
        "tds.password=shared-secret\n"
        "smtp.host=mail.shared\n"
        "smtp.port=2587\n"
        "smtp.from=sender@example.com\n"
        "email.default_cc=shared-cc@example.com\n"
        "report.output_dir=../shared-output\n"
        "log.dir=../shared-logs\n");
    WriteFile(
        config_path,
        "env.name=qa\n"
        "tds.drtp_host=10.10.20.30\n"
        "email.default_to=qa-ops@example.com\n");

    report::CliOptions cli;
    const report::AppConfig config = report::LoadConfig(config_path.string(), cli);

    AssertTrue(config.env_name == "qa", "overlay env should be applied");
    AssertTrue(config.tds.drtp_host == "10.10.20.30", "overlay tds host should be applied");
    AssertTrue(config.tds.drtp_port == 6003, "shared template tds port should be applied");
    AssertTrue(config.tds.user == "10000", "shared template tds user should be applied");
    AssertTrue(config.tds.password == "shared-secret", "shared template password should be applied");
    AssertTrue(config.smtp.host == "mail.shared", "shared template smtp host should be applied");
    AssertTrue(config.smtp.port == 2587, "shared template smtp port should be applied");
    AssertTrue(config.default_to == std::vector<std::string>{"qa-ops@example.com"}, "overlay recipients should be applied");
    AssertTrue(config.default_cc == std::vector<std::string>{"shared-cc@example.com"}, "shared cc should be applied");
    AssertTrue(
        std::filesystem::path(config.output_dir).filename() == "shared-output",
        "shared output dir should be resolved relative to the overlay config");
    AssertTrue(
        std::filesystem::path(config.log.directory).filename() == "shared-logs",
        "shared log dir should be resolved relative to the overlay config");
}

void TestDefaultConfigPathPrefersExplicitEnvOverSingleConfig()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_default_config_env_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir / "config");
    WriteFile(temp_dir / "config" / "report.properties", "env.name=prod\n");
    WriteFile(temp_dir / "config" / "qa.properties", "env.name=qa\n");

    const ScopedCurrentPath scoped_path(temp_dir);
    const std::string config_path = report::DefaultConfigPath("qa");

    AssertTrue(
        std::filesystem::path(config_path).filename() == "qa.properties",
        "explicit --env should prefer <env>.properties over report.properties");
}

void TestDefaultConfigPathUsesSingleConfigWhenEnvMissing()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_default_single_config_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir / "config");
    WriteFile(temp_dir / "config" / "report.properties", "env.name=prod\n");

    const ScopedCurrentPath scoped_path(temp_dir);
    const std::string config_path = report::DefaultConfigPath("");

    AssertTrue(
        std::filesystem::path(config_path).filename() == "report.properties",
        "single-config package should use report.properties when --env is omitted");
}

void TestDefaultConfigPathDoesNotFallBackToDevWhenEnvMissing()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_default_requires_single_config_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir / "config");
    WriteFile(temp_dir / "config" / "dev.properties", "env.name=dev\n");

    const ScopedCurrentPath scoped_path(temp_dir);
    const std::string config_path = report::DefaultConfigPath("");

    AssertTrue(
        std::filesystem::path(config_path).filename() == "report.properties",
        "when --env is omitted the runtime should require report.properties instead of falling back to dev.properties");
}

void TestLoadConfigWithVaultBackedTdsPassword()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_vault_test";
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path config_path = temp_dir / "vault.properties";
    const std::filesystem::path curl_executable = CreateFakeCurlExecutable(temp_dir);

    std::ofstream output(config_path);
    output
        << "env.name=qa\n"
        << "tds.drtp_host=10.0.0.1\n"
        << "tds.drtp_port=6003\n"
        << "tds.user=10000\n"
        << "tds.password=vault://secret/tds/qa#password\n"
        << "vault.curl_executable=" << curl_executable.string() << "\n"
        << "vault.address=https://vault.example.com\n"
        << "smtp.host=mail.local\n"
        << "smtp.port=2587\n"
        << "smtp.from=sender@example.com\n";
    output.close();

    report::CliOptions cli;
    const report::AppConfig config = report::LoadConfig(config_path.string(), cli);

    AssertTrue(config.tds.password == "vault-secret-value", "vault-backed tds password should be resolved");
}

void TestStubClientAndCsv()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "report_output_test";
    std::filesystem::create_directories(output_dir);

    report::CliOptions cli;
    cli.stub_file = (std::filesystem::path("tests") / "data" / "stub_snapshot.csv").string();

    const report::AppConfig config = BuildTestConfig(output_dir);
    std::unique_ptr<report::ITdsClient> client = report::CreateClient(config, cli);

    const int trade_date = client->FetchTradeDate();
    const std::vector<report::CustomerFundRecord> records =
        client->FetchCustomerFunds(trade_date, {"1001"});

    AssertTrue(trade_date == 20260418, "stub trade date should come from csv");
    AssertTrue(records.size() == 1, "filter should keep exactly one row");
    AssertTrue(records.front().cust_name == "Alpha Capital", "unexpected customer name");

    const std::string csv_path = report::WriteCsvReport(records, config, trade_date);
    const std::string csv_content = ReadFile(csv_path);
    AssertContains(csv_content, "cust_no", "csv header");
    AssertContains(csv_content, "1001", "csv row");
    AssertContains(csv_content, "Alpha Capital", "csv customer name");
}

void TestMimeAndDryRun()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "report_mail_test";
    std::filesystem::create_directories(output_dir);
    const report::AppConfig config = BuildTestConfig(output_dir);

    const std::vector<report::CustomerFundRecord> records {
        {20260418, "1001", "Alpha Capital", "FA1001", 1000.5, 12.5, 800.0, 0.12, 0.15},
        {20260418, "1002", "Beta Futures", "FA1002", 2000.5, -8.5, 1200.0, 0.08, 0.11}
    };
    const std::string csv_path = report::WriteCsvReport(records, config, 20260418);

    report::CliOptions cli;
    cli.to = {"dest@example.com"};
    const report::MailRequest request =
        report::BuildMailRequest(records, config, cli, 20260418, csv_path);
    const std::string mime = report::BuildMimeMessage(request);

    AssertContains(
        mime,
        "Subject: Test Client Funding and Risk Ratio Report - Market Close 20260418",
        "mime subject");
    AssertContains(mime, "Content-Disposition: attachment;", "mime attachment");
    AssertContains(mime, "Alpha Capital", "mime html body");
    AssertContains(mime, "Beta Futures", "mime html body should contain every customer");
    AssertContains(
        mime,
        u8"\u5BA2\u6237\u8D44\u91D1\u53CA\u98CE\u9669\u5EA6 Funding &amp; Risk ratio",
        "mime body section title");
    AssertContains(mime, u8"Total \u5408\u8BA1:", "mime body total row");
    AssertContains(mime, "1,000.50", "mime amount formatting");
    AssertContains(mime, "12.00%", "mime risk formatting");
    AssertContains(mime, "No Action Required", "mime threshold note");

    const report::SendMailResult result = report::SendMailWithCurl(request, config, true);
    AssertTrue(!result.sent, "dry run should not mark mail as sent");
    AssertTrue(std::filesystem::exists(result.preview_path), "dry run preview should exist");
}

void TestCurlConfigWithClientCertificate()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "report_curl_test";
    std::filesystem::create_directories(output_dir);
    report::AppConfig config = BuildTestConfig(output_dir);
    config.smtp.host = "mta-hub.example.com.cn";
    config.smtp.port = 2587;
    config.smtp.security = "starttls";
    config.smtp.insecure = false;
    config.smtp.client_cert_path = "/app/.cert/server.pem";
    config.smtp.client_key_path = "/app/.cert/server.key";
    config.smtp.client_cert_type = "PEM";
    config.smtp.client_key_type = "PEM";

    const std::vector<report::CustomerFundRecord> records {
        {20260418, "1001", "Alpha Capital", "FA1001", 1000.5, 12.5, 800.0, 0.12, 0.15}
    };
    const std::string csv_path = report::WriteCsvReport(records, config, 20260418);

    report::CliOptions cli;
    cli.to = {"dest@example.com"};
    const report::MailRequest request =
        report::BuildMailRequest(records, config, cli, 20260418, csv_path);
    const std::string curl_config =
        report::BuildCurlConfig(request, config, "D:/mail/message.eml");

    AssertContains(curl_config, "url = \"smtp://mta-hub.example.com.cn:2587\"", "curl smtp url");
    AssertContains(curl_config, "cert = \"/app/.cert/server.pem\"", "curl client cert");
    AssertContains(curl_config, "key = \"/app/.cert/server.key\"", "curl client key");
    AssertContains(curl_config, "cert-type = \"PEM\"", "curl cert type");
    AssertContains(curl_config, "key-type = \"PEM\"", "curl key type");
    AssertContains(curl_config, "ssl-reqd", "curl starttls requirement");
    AssertTrue(curl_config.find("user = ") == std::string::npos, "smtp relay must not use basic auth");
}

void TestVendorTextDecodingAndErrorFormatting()
{
    const std::string gbk_error = "\xB4\xB4\xBD\xA8\x54\x44\x53\xBE\xE4\xB1\xFA\xCA\xA7\xB0\xDC";
    const std::string decoded = report::DecodeVendorText(gbk_error);
    const std::string formatted =
        report::FormatTdsApiError("TdsApi_reqLogin", 430000103, gbk_error);

    AssertTrue(
        decoded == u8"\u521B\u5EFATDS\u53E5\u67C4\u5931\u8D25",
        "GBK vendor text should decode to UTF-8");
    AssertContains(
        report::DescribeTdsErrorCode(430000103),
        "failed to create TDS handle",
        "known TDS error code description");
    AssertContains(formatted, "430000103", "formatted error code");
    AssertContains(formatted, "failed to create TDS handle", "formatted error description");
}

void TestNoMoreDataDetection()
{
    AssertTrue(
        report::IsTdsNoMoreDataResult(1009, ""),
        "error code 1009 should be treated as end-of-data");
    AssertTrue(
        report::IsTdsNoMoreDataResult(1, u8"\u6ca1\u6709\u66f4\u591a\u6570\u636e"),
        "decoded vendor message should be treated as end-of-data");
    AssertTrue(
        !report::IsTdsNoMoreDataResult(430000111, "snapshot failed"),
        "real snapshot errors must not be ignored");
}

void TestLoggerWritesJsonLine()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "report_logger_test";
    std::filesystem::create_directories(output_dir);

    report::AppConfig config = BuildTestConfig(output_dir);
    report::InitializeLogger(config);
    report::LogInfo(
        "logger_test",
        "Logger writes json lines",
        {
            {"trade_date", "20260418"},
            {"mode", "test"}
        });
    const std::string log_path = report::CurrentLogFilePath();
    report::ShutdownLogger();

    AssertTrue(!log_path.empty(), "logger should expose the current log file path");
    AssertTrue(std::filesystem::exists(log_path), "log file should be created");

    const std::string log_content = ReadFile(log_path);
    AssertContains(log_content, "\"event\":\"logger_test\"", "log event");
    AssertContains(log_content, "\"msg\":\"Logger writes json lines\"", "log message");
    AssertContains(log_content, "\"trade_date\":\"20260418\"", "log field");
    AssertContains(log_content, "\"level\":\"info\"", "log level");
}

} // namespace

int main()
{
    try
    {
        TestParseCli();
        TestLoadConfig();
        TestLoadConfigUsesFileEnvWhenCliEnvMissing();
        TestLoadConfigMergesSharedTemplateAndEnvOverlay();
        TestDefaultConfigPathPrefersExplicitEnvOverSingleConfig();
        TestDefaultConfigPathUsesSingleConfigWhenEnvMissing();
        TestDefaultConfigPathDoesNotFallBackToDevWhenEnvMissing();
        TestLoadConfigWithVaultBackedTdsPassword();
        TestStubClientAndCsv();
        TestMimeAndDryRun();
        TestCurlConfigWithClientCertificate();
        TestVendorTextDecodingAndErrorFormatting();
        TestNoMoreDataDetection();
        TestLoggerWritesJsonLine();
        std::cout << "All report tests passed\n";
        return 0;
    }
    catch (const std::exception& ex)
    {
        std::cerr << "report_tests failed: " << ex.what() << '\n';
        return 1;
    }
}
