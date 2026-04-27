#include "app.h"
#include "logging.h"

#include <iostream>
#include <set>
#include <stdexcept>

namespace {

void PrintUsage()
{
    std::cout
        << "Usage:\n"
        << "  report --env dev|qa|prod [--config path] [--to a@x.com,b@y.com] [--cc c@x.com]\n"
        << "               [--drtp-endpoints host1:port1,host2:port2]\n"
        << "               [--cust-list 1001,1002] [--output-dir path] [--trade-date YYYYMMDD]\n"
        << "               [--dry-run] [--stub-file path]\n\n"
        << "Notes:\n"
        << "  --stub-file lets you debug without a local supplier TDS runtime.\n"
        << "  Windows local builds can read DRTP with win32 supplier files, but must use --dry-run.\n"
        << "  --env dev|qa|prod loads config/report.properties first, then config/<env>.properties.\n"
        << "  --drtp-endpoints temporarily overrides all configured DRTP access points.\n"
        << "  All runs require --env; packaged runs auto-discover config beside the executable.\n"
        << "  --config only changes which config file is loaded; it does not select the environment.\n"
        << "  --dry-run writes an .eml preview instead of calling curl SMTP.\n";
}

std::size_t CountUniqueCustomers(const std::vector<report::CustomerFundRecord>& records)
{
    std::set<std::string> cust_numbers;
    for (const auto& record : records)
    {
        cust_numbers.insert(record.cust_no);
    }
    return cust_numbers.size();
}

} // namespace

int main(int argc, char** argv)
{
    try
    {
        const report::CliOptions cli = report::ParseCli(argc, argv);
        if (cli.help)
        {
            PrintUsage();
            return 0;
        }
        if (cli.env.empty())
        {
            throw std::runtime_error("missing --env; use --env dev|qa|prod");
        }

        const std::string config_path =
            cli.config_path.empty() ? report::DefaultConfigPath(cli.env) : cli.config_path;
        const report::AppConfig config = report::LoadConfig(config_path, cli);
#ifdef _WIN32
        if (!cli.dry_run)
        {
            throw std::runtime_error(
                "Windows local builds only support --dry-run mail preview; live SMTP delivery runs on Linux/Jenkins.");
        }
#endif
        report::InitializeLogger(config);
        report::LogInfo(
            "startup",
            "report run started",
            {
                {"config_path", config_path},
                {"dry_run", cli.dry_run ? "true" : "false"},
                {"stub_mode", cli.stub_file.empty() ? "false" : "true"},
                {"log_file", report::CurrentLogFilePath()}
            });

        std::unique_ptr<report::ITdsClient> client = report::CreateClient(config, cli);

        const int trade_date = cli.trade_date_override > 0 ? cli.trade_date_override : client->FetchTradeDate();
        report::LogInfo(
            "trade_date_ready",
            "Trade date resolved",
            {{"trade_date", std::to_string(trade_date)}});

        const std::vector<report::CustomerFundRecord> records =
            client->FetchCustomerFunds(trade_date, cli.cust_filters);
        report::LogInfo(
            "snapshot_ready",
            "Customer fund snapshot loaded",
            {
                {"trade_date", std::to_string(trade_date)},
                {"snapshot_rows", std::to_string(records.size())},
                {"unique_customers", std::to_string(CountUniqueCustomers(records))}
            });

        const std::string report_path = report::WriteCsvReport(records, config, trade_date);
        const report::MailRequest mail_request =
            report::BuildMailRequest(records, config, cli, trade_date, report_path);
        const report::SendMailResult mail_result =
            report::SendMailWithCurl(mail_request, config, cli.dry_run);

        const std::size_t unique_customers = CountUniqueCustomers(records);
        if (mail_result.sent)
        {
            report::LogInfo("mail_sent", "SMTP message sent", {{"csv_report", report_path}});
        }
        else
        {
            report::LogInfo(
                "mail_dry_run",
                "Dry-run mail preview generated",
                {
                    {"csv_report", report_path},
                    {"preview_file", mail_result.preview_path}
                });
        }
        report::LogInfo(
            "run_summary",
            "Report run summary",
            {
                {"trade_date", std::to_string(trade_date)},
                {"snapshot_rows", std::to_string(records.size())},
                {"unique_customers", std::to_string(unique_customers)},
                {"csv_report", report_path},
                {"email_status", mail_result.sent ? "sent" : "dry-run preview generated"},
                {"preview_file", mail_result.preview_path}
            });

        report::LogInfo("shutdown", "report run completed successfully");
        report::ShutdownLogger();
        return 0;
    }
    catch (const std::exception& ex)
    {
        if (report::IsLoggerInitialized())
        {
            report::LogError("run_failed", ex.what());
            report::ShutdownLogger();
        }
        else
        {
            std::cerr << "report failed: " << ex.what() << '\n';
        }
        return 1;
    }
}
