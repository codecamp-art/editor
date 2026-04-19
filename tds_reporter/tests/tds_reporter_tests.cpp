#include "app.h"

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

tds_reporter::AppConfig BuildTestConfig(const std::filesystem::path& output_dir)
{
    tds_reporter::AppConfig config;
    config.env_name = "test";
    config.email_subject = "Daily Snapshot";
    config.attachment_name = "fund_snapshot";
    config.output_dir = output_dir.string();
    config.default_to = {"ops@example.com"};
    config.default_cc = {"cc@example.com"};
    config.smtp.host = "localhost";
    config.smtp.port = 1025;
    config.smtp.from = "sender@example.com";
    config.smtp.security = "plain";
    config.smtp.insecure = true;
    return config;
}

std::string ReadFile(const std::filesystem::path& path)
{
    std::ifstream input(path, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
}

void TestParseCli()
{
    const char* argv[] = {
        "tds_reporter",
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

    const tds_reporter::CliOptions options =
        tds_reporter::ParseCli(static_cast<int>(std::size(argv)), const_cast<char**>(argv));

    AssertTrue(options.env == "qa", "expected env=qa");
    AssertTrue(options.to.size() == 2, "expected two To recipients");
    AssertTrue(options.cc.size() == 1, "expected one Cc recipient");
    AssertTrue(options.cust_filters.size() == 2, "expected two customer filters");
    AssertTrue(options.dry_run, "expected dry_run=true");
    AssertTrue(options.trade_date_override == 20260419, "expected trade date override");
}

void TestLoadConfig()
{
    const std::filesystem::path temp_dir = std::filesystem::temp_directory_path() / "tds_reporter_config_test";
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
        << "smtp.client_key_path=/app/.cert/server.key\n";
    output.close();

    tds_reporter::CliOptions cli;
    cli.env = "prod";
    cli.output_dir = "custom-output";
    const tds_reporter::AppConfig config = tds_reporter::LoadConfig(config_path.string(), cli);

    AssertTrue(config.env_name == "prod", "cli env should override file env");
    AssertTrue(config.output_dir == "custom-output", "cli output dir should override file output dir");
    AssertTrue(config.tds.password == "super-secret", "env reference should be resolved");
    AssertTrue(config.smtp.port == 2587, "smtp port should be parsed");
    AssertTrue(config.smtp.security == "starttls", "smtp security should be parsed");
    AssertTrue(config.smtp.client_cert_path == "/app/.cert/server.pem", "smtp client cert should be parsed");
    AssertTrue(config.smtp.client_key_path == "/app/.cert/server.key", "smtp client key should be parsed");
}

void TestStubClientAndCsv()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "tds_reporter_output_test";
    std::filesystem::create_directories(output_dir);

    tds_reporter::CliOptions cli;
    cli.stub_file = (std::filesystem::path("tests") / "data" / "stub_snapshot.csv").string();

    const tds_reporter::AppConfig config = BuildTestConfig(output_dir);
    std::unique_ptr<tds_reporter::ITdsClient> client = tds_reporter::CreateClient(config, cli);

    const int trade_date = client->FetchTradeDate();
    const std::vector<tds_reporter::CustomerFundRecord> records =
        client->FetchCustomerFunds(trade_date, {"1001"});

    AssertTrue(trade_date == 20260418, "stub trade date should come from csv");
    AssertTrue(records.size() == 1, "filter should keep exactly one row");
    AssertTrue(records.front().cust_name == "Alpha Capital", "unexpected customer name");

    const std::string csv_path = tds_reporter::WriteCsvReport(records, config, trade_date);
    const std::string csv_content = ReadFile(csv_path);
    AssertContains(csv_content, "cust_no", "csv header");
    AssertContains(csv_content, "1001", "csv row");
    AssertContains(csv_content, "Alpha Capital", "csv customer name");
}

void TestMimeAndDryRun()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "tds_reporter_mail_test";
    std::filesystem::create_directories(output_dir);
    const tds_reporter::AppConfig config = BuildTestConfig(output_dir);

    const std::vector<tds_reporter::CustomerFundRecord> records {
        {20260418, "1001", "Alpha Capital", "FA1001", "CNY", 1000.5, 12.5, 800.0, 0.12, 0.15},
        {20260418, "1002", "Beta Futures", "FA1002", "CNY", 2000.5, -8.5, 1200.0, 0.08, 0.11}
    };
    const std::string csv_path = tds_reporter::WriteCsvReport(records, config, 20260418);

    tds_reporter::CliOptions cli;
    cli.to = {"dest@example.com"};
    const tds_reporter::MailRequest request =
        tds_reporter::BuildMailRequest(records, config, cli, 20260418, csv_path);
    const std::string mime = tds_reporter::BuildMimeMessage(request);

    AssertContains(mime, "Subject: [test] Daily Snapshot - 20260418", "mime subject");
    AssertContains(mime, "Content-Disposition: attachment;", "mime attachment");
    AssertContains(mime, "Alpha Capital", "mime html body");
    AssertContains(mime, "Beta Futures", "mime html body should contain every customer");
    AssertContains(mime, "All customer rows are listed below", "mime body summary");

    const tds_reporter::SendMailResult result = tds_reporter::SendMailWithCurl(request, config, true);
    AssertTrue(!result.sent, "dry run should not mark mail as sent");
    AssertTrue(std::filesystem::exists(result.preview_path), "dry run preview should exist");
}

void TestCurlConfigWithClientCertificate()
{
    const std::filesystem::path output_dir = std::filesystem::temp_directory_path() / "tds_reporter_curl_test";
    std::filesystem::create_directories(output_dir);
    tds_reporter::AppConfig config = BuildTestConfig(output_dir);
    config.smtp.host = "mta-hub.example.com.cn";
    config.smtp.port = 2587;
    config.smtp.security = "starttls";
    config.smtp.insecure = false;
    config.smtp.client_cert_path = "/app/.cert/server.pem";
    config.smtp.client_key_path = "/app/.cert/server.key";
    config.smtp.client_cert_type = "PEM";
    config.smtp.client_key_type = "PEM";

    const std::vector<tds_reporter::CustomerFundRecord> records {
        {20260418, "1001", "Alpha Capital", "FA1001", "CNY", 1000.5, 12.5, 800.0, 0.12, 0.15}
    };
    const std::string csv_path = tds_reporter::WriteCsvReport(records, config, 20260418);

    tds_reporter::CliOptions cli;
    cli.to = {"dest@example.com"};
    const tds_reporter::MailRequest request =
        tds_reporter::BuildMailRequest(records, config, cli, 20260418, csv_path);
    const std::string curl_config =
        tds_reporter::BuildCurlConfig(request, config, "D:/mail/message.eml");

    AssertContains(curl_config, "url = \"smtp://mta-hub.example.com.cn:2587\"", "curl smtp url");
    AssertContains(curl_config, "cert = \"/app/.cert/server.pem\"", "curl client cert");
    AssertContains(curl_config, "key = \"/app/.cert/server.key\"", "curl client key");
    AssertContains(curl_config, "cert-type = \"PEM\"", "curl cert type");
    AssertContains(curl_config, "key-type = \"PEM\"", "curl key type");
    AssertContains(curl_config, "ssl-reqd", "curl starttls requirement");
}

} // namespace

int main()
{
    try
    {
        TestParseCli();
        TestLoadConfig();
        TestStubClientAndCsv();
        TestMimeAndDryRun();
        TestCurlConfigWithClientCertificate();
        std::cout << "All tds_reporter tests passed\n";
        return 0;
    }
    catch (const std::exception& ex)
    {
        std::cerr << "tds_reporter_tests failed: " << ex.what() << '\n';
        return 1;
    }
}
