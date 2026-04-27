#include "app.h"
#include "logging.h"
#include "text_utils.h"

#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
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

void AssertNotContains(const std::string& haystack, const std::string& needle, const std::string& message)
{
    if (haystack.find(needle) != std::string::npos)
    {
        throw std::runtime_error(message + " unexpectedly contained [" + needle + "]");
    }
}

template <typename Callable>
void AssertThrows(Callable&& callable, const std::string& message)
{
    try
    {
        callable();
    }
    catch (const std::exception&)
    {
        return;
    }

    throw std::runtime_error(message);
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
        "--drtp-endpoints",
        "10.1.1.1:7001,10.1.1.2:7002",
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
    AssertTrue(options.drtp_endpoints == "10.1.1.1:7001,10.1.1.2:7002", "expected drtp endpoints override");
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
        << "tds.drtp_endpoints=10.0.0.1:6003\n"
        << "tds.user=10000\n"
        << "tds.password=${TDS_TEST_PASSWORD:}\n"
        << "smtp.host=mail.local\n"
        << "smtp.port=2587\n"
        << "smtp.from=sender@example.com\n"
        << "smtp.security=starttls\n"
        << "smtp.client_cert_path=/app/.cert/server.pem\n"
        << "smtp.client_key_path=/app/.cert/server.key\n"
        << "email.subject=Prod Report\n"
        << "log.dir=../logs\n"
        << "log.level=warn\n";
    output.close();

    report::CliOptions cli;
    cli.env = "prod";
    cli.output_dir = "custom-output";
    const report::AppConfig config = report::LoadConfig(config_path.string(), cli);

    AssertTrue(config.env_name == "prod", "cli env should be the runtime environment");
    AssertTrue(config.output_dir == "custom-output", "cli output dir should override file output dir");
    AssertTrue(config.tds.drtp_endpoints.size() == 1, "single DRTP endpoint should be configured");
    AssertTrue(config.tds.drtp_endpoints.front().host == "10.0.0.1", "DRTP endpoint host should be parsed");
    AssertTrue(config.tds.drtp_endpoints.front().port == 6003, "DRTP endpoint port should be parsed");
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

void TestLoadConfigRejectsMissingCliEnv()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_config_file_env_test";
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path config_path = temp_dir / "test.properties";

    std::ofstream output(config_path);
    output
        << "tds.drtp_endpoints=10.0.0.1:6003\n"
        << "tds.user=10000\n"
        << "tds.password=secret\n"
        << "smtp.host=mail.local\n"
        << "smtp.port=2587\n"
        << "smtp.from=sender@example.com\n"
        << "email.subject=Prod Report\n";
    output.close();

    report::CliOptions cli;
    AssertThrows(
        [&]() { (void)report::LoadConfig(config_path.string(), cli); },
        "LoadConfig should reject missing --env");
}

void TestLoadConfigMergesSharedPropertiesAndEnvOverlay()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_config_overlay_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path shared_path = temp_dir / "report.properties";
    const std::filesystem::path config_path = temp_dir / "qa.properties";

    WriteFile(
        shared_path,
        "tds.user=10000\n"
        "tds.password=shared-secret\n"
        "smtp.host=mail.shared\n"
        "smtp.port=2587\n"
        "report.output_dir=../shared-output\n"
        "log.dir=../shared-logs\n"
        "vault.secret_engine=secret\n"
        "vault.secret_key=password\n");
    WriteFile(
        config_path,
        "tds.drtp_endpoints=10.10.20.30:6003\n"
        "vault.secret_path=tds/qa\n"
        "smtp.from=sender-qa@example.com\n"
        "email.default_to=qa-ops@example.com\n"
        "email.default_cc=qa-cc@example.com\n"
        "email.subject=QA Report\n");

    report::CliOptions cli;
    cli.env = "qa";
    const report::AppConfig config = report::LoadConfig(config_path.string(), cli);

    AssertTrue(config.env_name == "qa", "overlay env should be applied");
    AssertTrue(config.tds.drtp_endpoints.size() == 1, "overlay host should produce one endpoint");
    AssertTrue(config.tds.drtp_endpoints.front().host == "10.10.20.30", "overlay endpoint host should be applied");
    AssertTrue(config.tds.drtp_endpoints.front().port == 6003, "overlay endpoint port should be applied");
    AssertTrue(config.tds.user == "10000", "shared properties tds user should be applied");
    AssertTrue(config.tds.password == "shared-secret", "shared properties password should be applied");
    AssertTrue(config.smtp.host == "mail.shared", "shared properties smtp host should be applied");
    AssertTrue(config.smtp.port == 2587, "shared properties smtp port should be applied");
    AssertTrue(config.smtp.from == "sender-qa@example.com", "overlay smtp sender should be applied");
    AssertTrue(config.default_to == std::vector<std::string>{"qa-ops@example.com"}, "overlay recipients should be applied");
    AssertTrue(config.default_cc == std::vector<std::string>{"qa-cc@example.com"}, "overlay cc should be applied");
    AssertTrue(config.email_subject == "QA Report", "overlay subject should be applied");
    AssertTrue(config.vault.secret_engine == "secret", "shared vault secret engine should be applied");
    AssertTrue(config.vault.secret_key == "password", "shared vault secret key should be applied");
    AssertTrue(config.vault.secret_path == "tds/qa", "overlay vault secret path should be applied");
    AssertTrue(
        std::filesystem::path(config.output_dir).filename() == "shared-output",
        "shared output dir should be resolved relative to the overlay config");
    AssertTrue(
        std::filesystem::path(config.log.directory).filename() == "shared-logs",
        "shared log dir should be resolved relative to the overlay config");
}

void TestLoadConfigParsesMultipleDrtpEndpoints()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_config_drtp_multi_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path config_path = temp_dir / "qa.properties";

    WriteFile(
        config_path,
        "tds.drtp_endpoints=10.10.20.30:6003,10.10.20.31:6004\n"
        "tds.user=10000\n"
        "tds.password=secret\n"
        "smtp.host=mail.local\n"
        "smtp.port=2587\n"
        "smtp.from=sender@example.com\n"
        "email.subject=QA Report\n");

    report::CliOptions cli;
    cli.env = "qa";
    const report::AppConfig config = report::LoadConfig(config_path.string(), cli);

    AssertTrue(config.tds.drtp_endpoints.size() == 2, "expected two DRTP endpoints");
    AssertTrue(config.tds.drtp_endpoints[0].host == "10.10.20.30", "primary DRTP host should be first endpoint");
    AssertTrue(config.tds.drtp_endpoints[0].port == 6003, "primary DRTP port should be first endpoint");
    AssertTrue(config.tds.drtp_endpoints[1].host == "10.10.20.31", "second DRTP host should be parsed");
    AssertTrue(config.tds.drtp_endpoints[1].port == 6004, "second DRTP port should be parsed");
}

void TestLoadConfigAppliesDrtpEndpointsCliOverride()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_config_drtp_endpoints_cli_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path config_path = temp_dir / "qa.properties";

    WriteFile(
        config_path,
        "tds.drtp_endpoints=10.10.20.30:6003\n"
        "tds.user=10000\n"
        "tds.password=secret\n"
        "smtp.host=mail.local\n"
        "smtp.port=2587\n"
        "smtp.from=sender@example.com\n"
        "email.subject=QA Report\n");

    report::CliOptions cli;
    cli.env = "qa";
    cli.drtp_endpoints = "192.0.2.20:7101;192.0.2.21:7102";
    const report::AppConfig config = report::LoadConfig(config_path.string(), cli);

    AssertTrue(config.tds.drtp_endpoints.size() == 2, "CLI endpoints override should produce two endpoints");
    AssertTrue(config.tds.drtp_endpoints[0].host == "192.0.2.20", "CLI endpoint host should be parsed");
    AssertTrue(config.tds.drtp_endpoints[0].port == 7101, "CLI endpoint port should be parsed");
    AssertTrue(config.tds.drtp_endpoints[1].host == "192.0.2.21", "second CLI endpoint host should be parsed");
    AssertTrue(config.tds.drtp_endpoints[1].port == 7102, "second CLI endpoint port should be parsed");
}

void TestDefaultConfigPathUsesEnvOverlay()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_default_config_env_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir / "config");
    WriteFile(temp_dir / "config" / "report.properties", "# shared\n");
    WriteFile(temp_dir / "config" / "qa.properties", "# qa\n");

    const ScopedCurrentPath scoped_path(temp_dir);
    const std::string config_path = report::DefaultConfigPath("qa");

    AssertTrue(
        std::filesystem::path(config_path).filename() == "qa.properties",
        "explicit --env should prefer <env>.properties over report.properties");
}

void TestDefaultConfigPathDoesNotFallbackToSharedPropertiesForUnknownEnv()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_default_unknown_env_test";
    std::filesystem::remove_all(temp_dir);
    std::filesystem::create_directories(temp_dir / "config");
    WriteFile(temp_dir / "config" / "report.properties", "# shared\n");

    const ScopedCurrentPath scoped_path(temp_dir);
    const std::string config_path = report::DefaultConfigPath("uat");

    AssertTrue(
        std::filesystem::path(config_path).filename() == "uat.properties",
        "unknown --env should resolve to the requested overlay and fail open if the file is absent");
}

void TestDefaultConfigPathRejectsMissingEnv()
{
    AssertThrows(
        []() { (void)report::DefaultConfigPath(""); },
        "packaged runs should require --env instead of using shared report.properties alone");
}

void TestVaultSecretReferenceDoesNotUseCurlCommand()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "report_vault_test";
    std::filesystem::create_directories(temp_dir);
    const std::filesystem::path config_path = temp_dir / "vault.properties";

    std::ofstream output(config_path);
    output
        << "tds.drtp_endpoints=10.0.0.1:6003\n"
        << "tds.user=10000\n"
        << "tds.password=vault://tds/qa#password\n"
        << "vault.address=http://127.0.0.1:1\n"
        << "vault.secret_engine=secret\n"
        << "smtp.host=mail.local\n"
        << "smtp.port=2587\n"
        << "smtp.from=sender@example.com\n"
        << "email.subject=QA Report\n";
    output.close();

    report::CliOptions cli;
    cli.env = "qa";
    std::string error_message;
    try
    {
        (void)report::LoadConfig(config_path.string(), cli);
    }
    catch (const std::exception& ex)
    {
        error_message = ex.what();
    }

    AssertTrue(!error_message.empty(), "test Vault endpoint should fail without a local Vault server");
    AssertNotContains(error_message, "fake-vault-token", "Vault failure must not expose tokens");
    AssertNotContains(error_message, "vault-secret-value", "Vault failure must not expose secret values");
    AssertNotContains(error_message, "X-Vault-Token", "Vault failure must not expose token headers");

    const std::string app_source = ReadFile(std::filesystem::path("src") / "app.cpp");
    AssertNotContains(app_source, "RunCommandCapture", "Vault runtime must not shell out through a command wrapper");
    AssertNotContains(app_source, "vault.curl_executable", "Vault runtime must not use a curl executable setting");
    AssertNotContains(app_source, "curl --negotiate", "Vault runtime must use C++ libcurl instead of curl CLI");
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

void TestDecodedVendorTextFlowsToCsvAndEmail()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "report_gbk_output_test";
    std::filesystem::create_directories(output_dir);
    const report::AppConfig config = BuildTestConfig(output_dir);
    const std::string customer_name = report::DecodeVendorText("\xC9\xCF\xBA\xA3\xBF\xCD\xBB\xA7");

    const std::vector<report::CustomerFundRecord> records {
        {20260418, "1001", customer_name, "FA1001", 1000.5, 12.5, 800.0, 0.12, 0.15}
    };
    const std::string csv_path = report::WriteCsvReport(records, config, 20260418);
    const std::string csv_content = ReadFile(csv_path);

    report::CliOptions cli;
    cli.to = {"dest@example.com"};
    const report::MailRequest request =
        report::BuildMailRequest(records, config, cli, 20260418, csv_path);
    const std::string mime = report::BuildMimeMessage(request);

    AssertTrue(customer_name == u8"\u4E0A\u6D77\u5BA2\u6237", "GBK customer name should decode to UTF-8");
    AssertContains(csv_content, u8"\u4E0A\u6D77\u5BA2\u6237", "csv should contain UTF-8 customer name");
    AssertContains(mime, u8"\u4E0A\u6D77\u5BA2\u6237", "email body should contain UTF-8 customer name");
    AssertContains(
        mime,
        "Content-Type: text/csv; charset=\"utf-8\";",
        "csv attachment should declare UTF-8");
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
    AssertTrue(curl_config.find("pass = ") == std::string::npos, "smtp key passphrase must not be configured");
}

void TestVendorTextDecodingAndErrorFormatting()
{
    const std::string gbk_error = "\xB4\xB4\xBD\xA8\x54\x44\x53\xBE\xE4\xB1\xFA\xCA\xA7\xB0\xDC";
    const std::string gbk_valid_utf8_bytes("\xD2\xBB", 2);
    const std::string decoded = report::DecodeVendorText(gbk_error);
    const std::string formatted =
        report::FormatTdsApiError("TdsApi_reqLogin", 430000103, gbk_error);

    AssertTrue(
        decoded == u8"\u521B\u5EFATDS\u53E5\u67C4\u5931\u8D25",
        "GBK vendor text should decode to UTF-8");
    AssertTrue(
        report::DecodeVendorText(gbk_valid_utf8_bytes) == u8"\u4E00",
        "GBK vendor text should be decoded before UTF-8 fallback");
    AssertContains(formatted, "430000103", "formatted error code");
    AssertContains(
        formatted,
        u8"\u521B\u5EFATDS\u53E5\u67C4\u5931\u8D25",
        "formatted vendor error reason");
    AssertTrue(
        formatted.find("failed to create TDS handle") == std::string::npos,
        "formatted TDS error should not include maintained internal descriptions");

    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "report_gbk_log_test";
    std::filesystem::create_directories(output_dir);
    report::AppConfig config = BuildTestConfig(output_dir);
    report::InitializeLogger(config);
    report::LogError("tds_vendor_error", formatted);
    const std::string log_path = report::CurrentLogFilePath();
    report::ShutdownLogger();
    AssertContains(ReadFile(log_path), u8"\u521B\u5EFATDS\u53E5\u67C4\u5931\u8D25", "log should contain UTF-8 vendor reason");
}

void TestNoMoreDataDetection()
{
    const std::string gbk_no_more_data =
        "\xC3\xBB\xD3\xD0\xB8\xFC\xB6\xE0\xCA\xFD\xBE\xDD";
    AssertTrue(
        report::IsTdsNoMoreDataResult(1009, ""),
        "error code 1009 should be treated as end-of-data");
    AssertTrue(
        report::IsTdsNoMoreDataResult(1, gbk_no_more_data),
        "GBK vendor message should be treated as end-of-data");
    AssertTrue(
        !report::IsTdsNoMoreDataResult(430000111, "snapshot failed"),
        "real snapshot errors must not be ignored");
}

void TestLoggerWritesJsonLine()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "report_logger_test";
    std::filesystem::remove_all(output_dir);
    std::filesystem::create_directories(output_dir);

    report::AppConfig config = BuildTestConfig(output_dir);
    report::InitializeLogger(config);
    std::ostringstream console_output;
    std::streambuf* original_stdout = std::cout.rdbuf(console_output.rdbuf());
    report::LogInfo(
        "logger_test",
        "Logger writes json lines",
        {
            {"trade_date", "20260418"},
            {"mode", "test"}
        });
    std::cout.rdbuf(original_stdout);
    const std::string log_path = report::CurrentLogFilePath();
    report::ShutdownLogger();

    AssertTrue(!log_path.empty(), "logger should expose the current log file path");
    AssertTrue(std::filesystem::exists(log_path), "log file should be created");

    const std::string log_content = ReadFile(log_path);
    AssertTrue(console_output.str() == log_content, "console log output should match file log output");
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
        TestLoadConfigRejectsMissingCliEnv();
        TestLoadConfigMergesSharedPropertiesAndEnvOverlay();
        TestLoadConfigParsesMultipleDrtpEndpoints();
        TestLoadConfigAppliesDrtpEndpointsCliOverride();
        TestDefaultConfigPathUsesEnvOverlay();
        TestDefaultConfigPathDoesNotFallbackToSharedPropertiesForUnknownEnv();
        TestDefaultConfigPathRejectsMissingEnv();
        TestVaultSecretReferenceDoesNotUseCurlCommand();
        TestStubClientAndCsv();
        TestMimeAndDryRun();
        TestDecodedVendorTextFlowsToCsvAndEmail();
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
