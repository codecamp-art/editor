#include "app.h"
#include "logging.h"

#include <iostream>
#include <set>

namespace {

void PrintUsage()
{
    std::cout
        << "Usage:\n"
        << "  tds_reporter --env dev [--config path] [--to a@x.com,b@y.com] [--cc c@x.com]\n"
        << "               [--cust-list 1001,1002] [--output-dir path] [--trade-date YYYYMMDD]\n"
        << "               [--dry-run] [--stub-file path]\n\n"
        << "Notes:\n"
        << "  --stub-file lets you debug on Windows without the vendor RHEL8 .so file.\n"
        << "  --dry-run writes an .eml preview instead of calling curl SMTP.\n";
}

std::size_t CountUniqueCustomers(const std::vector<tds_reporter::CustomerFundRecord>& records)
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
        const tds_reporter::CliOptions cli = tds_reporter::ParseCli(argc, argv);
        if (cli.help)
        {
            PrintUsage();
            return 0;
        }

        const std::string config_path =
            cli.config_path.empty() ? tds_reporter::DefaultConfigPath(cli.env) : cli.config_path;
        const tds_reporter::AppConfig config = tds_reporter::LoadConfig(config_path, cli);
        tds_reporter::InitializeLogger(config);
        tds_reporter::LogInfo(
            "startup",
            "tds_reporter run started",
            {
                {"config_path", config_path},
                {"dry_run", cli.dry_run ? "true" : "false"},
                {"stub_mode", cli.stub_file.empty() ? "false" : "true"},
                {"log_file", tds_reporter::CurrentLogFilePath()}
            });

        std::unique_ptr<tds_reporter::ITdsClient> client = tds_reporter::CreateClient(config, cli);

        const int trade_date = cli.trade_date_override > 0 ? cli.trade_date_override : client->FetchTradeDate();
        tds_reporter::LogInfo(
            "trade_date_ready",
            "Trade date resolved",
            {{"trade_date", std::to_string(trade_date)}});

        const std::vector<tds_reporter::CustomerFundRecord> records =
            client->FetchCustomerFunds(trade_date, cli.cust_filters);
        tds_reporter::LogInfo(
            "snapshot_ready",
            "Customer fund snapshot loaded",
            {
                {"trade_date", std::to_string(trade_date)},
                {"snapshot_rows", std::to_string(records.size())},
                {"unique_customers", std::to_string(CountUniqueCustomers(records))}
            });

        const std::string report_path = tds_reporter::WriteCsvReport(records, config, trade_date);
        const tds_reporter::MailRequest mail_request =
            tds_reporter::BuildMailRequest(records, config, cli, trade_date, report_path);
        const tds_reporter::SendMailResult mail_result =
            tds_reporter::SendMailWithCurl(mail_request, config, cli.dry_run);

        std::cout << "Environment: " << config.env_name << '\n';
        std::cout << "Trade date: " << trade_date << '\n';
        std::cout << "Snapshot rows: " << records.size() << '\n';
        std::cout << "Unique customers: " << CountUniqueCustomers(records) << '\n';
        std::cout << "CSV report: " << report_path << '\n';
        if (mail_result.sent)
        {
            std::cout << "Email status: sent\n";
            tds_reporter::LogInfo("mail_sent", "SMTP message sent", {{"csv_report", report_path}});
        }
        else
        {
            std::cout << "Email status: dry-run preview generated\n";
            std::cout << "Preview file: " << mail_result.preview_path << '\n';
            tds_reporter::LogInfo(
                "mail_dry_run",
                "Dry-run mail preview generated",
                {
                    {"csv_report", report_path},
                    {"preview_file", mail_result.preview_path}
                });
        }

        tds_reporter::LogInfo("shutdown", "tds_reporter run completed successfully");
        tds_reporter::ShutdownLogger();
        return 0;
    }
    catch (const std::exception& ex)
    {
        if (tds_reporter::IsLoggerInitialized())
        {
            tds_reporter::LogError("run_failed", ex.what());
            tds_reporter::ShutdownLogger();
        }
        std::cerr << "tds_reporter failed: " << ex.what() << '\n';
        return 1;
    }
}
